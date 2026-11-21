"""Links JVM .class files (user Kotlin code + Java shim stdlib) into a KTV1 image.

Pipeline:
  1. load user classes + shim classes (shim names have their `ktshim/` prefix stripped)
  2. RTA (rapid type analysis) from main(): only reachable methods are kept
  3. per class, the constant pool is compacted to the entries live code uses and every
     symbolic reference is resolved to a number (method id, field slot, class id, string id).
     Bytecode operands are renumbered in place (same operand width), so instruction
     lengths and branch offsets never change.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(__file__))
import classfile as cfm  # noqa: E402
from classfile import ClassFile, arg_slots, ret_slots, slots_of  # noqa: E402
from natives import NATIVE_IDS, NATIVES, split_key, is_static_native  # noqa: E402

SHIM_PREFIX = "ktshim/"

# CP entry tags understood by the firmware
CP_NONE, CP_INT, CP_WIDE, CP_STR, CP_CLASS = 0, 1, 2, 3, 4
CP_FIELD_S, CP_FIELD_I, CP_METHOD, CP_METHOD_V, CP_UNRESOLVED = 5, 6, 7, 8, 9

CID_ANY, CID_ARRAY, CID_UNKNOWN = 0xFFFF, 0xFFFE, 0xFFFD
NO_METHOD = 0xFFFF
NATIVE_BIT = 0x8000

CF_INTERFACE, CF_ABSTRACT, CF_BUILTIN = 1, 2, 4
MF_STATIC = 1

OBJECT, STRING = "java/lang/Object", "java/lang/String"
BUILTINS = {
    OBJECT: (None, []),
    STRING: (OBJECT, ["java/lang/CharSequence", "java/lang/Comparable"]),
}

# well-known classes the VM instantiates itself (index order is ABI with firmware)
WELL_KNOWN = [
    "java/lang/Throwable",
    "java/lang/NullPointerException",
    "java/lang/ArithmeticException",
    "java/lang/ArrayIndexOutOfBoundsException",
    "java/lang/ClassCastException",
    "java/lang/NegativeArraySizeException",
    "java/lang/OutOfMemoryError",
    "java/lang/StackOverflowError",
    "java/lang/StringIndexOutOfBoundsException",
    "java/lang/UnsupportedOperationException",
    "java/lang/NumberFormatException",
    "java/lang/IllegalStateException",
]

# calls redirected to static helpers implemented in the shim (receiver becomes arg 0)
REDIRECTS = {
    "java/lang/String.valueOf(Ljava/lang/Object;)Ljava/lang/String;":
        "ktvm/Helpers.valueOf(Ljava/lang/Object;)Ljava/lang/String;",
    "java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;":
        "ktvm/Helpers.split(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;",
    "java/lang/String.repeat(I)Ljava/lang/String;":
        "ktvm/Helpers.repeat(Ljava/lang/String;I)Ljava/lang/String;",
    "java/lang/String.replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;":
        "ktvm/Helpers.replace(Ljava/lang/String;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
    "java/lang/String.format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;":
        "ktvm/Helpers.format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
    "java/lang/String.getBytes()[B":
        "ktvm/Helpers.getBytes(Ljava/lang/String;)[B",
}

FIXED_SELECTORS = ["toString()Ljava/lang/String;", "equals(Ljava/lang/Object;)Z", "hashCode()I"]


class LinkError(Exception):
    pass


# ---------------------------------------------------------------- bytecode walk
def _insn_len(code, pc):
    op = code[pc]
    if op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc):
        return 2
    if op in (0x11, 0x13, 0x14, 0x84) or 0x99 <= op <= 0xa8 or 0xb2 <= op <= 0xb8 \
            or op in (0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7):
        return 3
    if op in (0xb9, 0xba, 0xc8, 0xc9):
        return 5
    if op == 0xc5:
        return 4
    if op == 0xc4:
        return 6 if code[pc + 1] == 0x84 else 4
    if op in (0xaa, 0xab):
        p = (pc + 4) & ~3
        if op == 0xaa:
            lo, hi = struct.unpack(">ii", code[p + 4:p + 12])
            return p + 12 + 4 * (hi - lo + 1) - pc
        (n,) = struct.unpack(">i", code[p + 4:p + 8])
        return p + 8 + 8 * n - pc
    return 1


CP_OPS = {0x12: 1, 0x13: 2, 0x14: 2, 0xb2: 2, 0xb3: 2, 0xb4: 2, 0xb5: 2, 0xb6: 2, 0xb7: 2, 0xb8: 2,
          0xb9: 2, 0xba: 2, 0xbb: 2, 0xbd: 2, 0xc0: 2, 0xc1: 2, 0xc5: 2}


def code_cp_refs(code):
    """Yield (pc, opcode, operand_width, cp_index) for instructions with a constant-pool operand."""
    pc = 0
    while pc < len(code):
        op = code[pc]
        w = CP_OPS.get(op)
        if w == 1:
            yield pc, op, 1, code[pc + 1]
        elif w == 2:
            yield pc, op, 2, (code[pc + 1] << 8) | code[pc + 2]
        pc += _insn_len(code, pc)


def array_elem_class(name):
    """'[[Lfoo/Bar;' -> 'foo/Bar'; '[I' -> None; 'foo/Bar' -> 'foo/Bar'."""
    if not name.startswith("["):
        return name
    n = name.lstrip("[")
    return n[1:-1] if n.startswith("L") else None


def nice(name):
    return name.replace("/", ".")


# ---------------------------------------------------------------- linker
class Linker:
    def __init__(self, user_classes, shim_classes, entry_hint=None):
        self.classes = {}
        self.user_names = set()
        for cf in shim_classes:
            self.classes[cf.name] = cf
        for cf in user_classes:
            self.classes[cf.name] = cf
            self.user_names.add(cf.name)
        self.entry_hint = entry_hint
        self.warnings = []
        self.strings, self.string_ids = [], {}
        self.selectors = {}
        for s in FIXED_SELECTORS:
            self.sel(s)
        self._vt = {}
        self._anc = {}

    # ------------------------------------------------ small helpers
    def sid(self, s):
        if s not in self.string_ids:
            self.string_ids[s] = len(self.strings)
            self.strings.append(s)
        return self.string_ids[s]

    def sel(self, key):
        if key not in self.selectors:
            self.selectors[key] = len(self.selectors)
        return self.selectors[key]

    def known(self, name):
        return name in self.classes or name in BUILTINS

    def super_of(self, name):
        if name in BUILTINS:
            return BUILTINS[name][0]
        cf = self.classes.get(name)
        return cf.super if cf and self.known(cf.super or "") else None

    def interfaces_of(self, name):
        if name in BUILTINS:
            return [i for i in BUILTINS[name][1] if self.known(i)]
        cf = self.classes.get(name)
        return [i for i in cf.interfaces if self.known(i)] if cf else []

    def ancestors(self, name):
        """All known supertypes (classes + interfaces), excluding self, BFS order."""
        if name in self._anc:
            return self._anc[name]
        seen, order, work = set(), [], [name]
        while work:
            c = work.pop(0)
            nxt = ([self.super_of(c)] if self.super_of(c) else []) + self.interfaces_of(c)
            for n in nxt:
                if n not in seen:
                    seen.add(n); order.append(n); work.append(n)
        self._anc[name] = order
        return order

    def superclass_chain(self, name):
        out, c = [], name
        while c and self.known(c):
            out.append(c)
            c = self.super_of(c)
        return out

    def is_interface(self, name):
        cf = self.classes.get(name)
        return bool(cf and cf.is_interface)

    def is_final_class(self, name):
        if name in BUILTINS:
            return name == STRING
        cf = self.classes.get(name)
        return bool(cf and (cf.access & cfm.ACC_FINAL))

    def method(self, key):
        c, n, d = key
        return self.classes[c].find_method(n, d)

    def warn(self, where, msg):
        w = f"{nice(where)}: {msg}"
        if w not in self.warnings:
            self.warnings.append(w)

    # ------------------------------------------------ resolution (symbolic)
    def native_key(self, c, name, desc):
        k = f"{c}.{name}{desc}"
        if k not in NATIVE_IDS:
            raise LinkError(f"native method {k} is declared but not implemented by the firmware")
        return NATIVE_IDS[k]

    def lookup_method(self, owner, name, desc):
        """Resolve a method ref. Returns ('m', key) | ('n', nid) | ('abstract', None) | None."""
        key = f"{owner}.{name}{desc}"
        if key in REDIRECTS:
            return self.lookup_method(*split_key(REDIRECTS[key]))
        if owner.startswith("["):
            k = f"[.{name}{desc}"
            if k in NATIVE_IDS:
                return ("n", NATIVE_IDS[k])
            owner = OBJECT
        chain = self.superclass_chain(owner)
        for c in chain:
            if c in BUILTINS:
                k = f"{c}.{name}{desc}"
                if k in NATIVE_IDS:
                    return ("n", NATIVE_IDS[k])
                continue
            m = self.classes[c].find_method(name, desc)
            if m:
                if m.is_native:
                    return ("n", self.native_key(c, name, desc))
                if m.is_abstract:
                    break
                return ("m", (c, name, desc))
        abstract = False
        for i in [owner] + self.ancestors(owner):
            if self.is_interface(i):
                m = self.classes[i].find_method(name, desc)
                if m and not m.is_abstract and not m.is_static:
                    return ("m", (i, name, desc))
                if m:
                    abstract = True
        for c in chain:
            cf = self.classes.get(c)
            if cf and cf.find_method(name, desc):
                abstract = True
        return ("abstract", None) if abstract else None

    def vtable(self, n):
        """Symbolic vtable: selector -> ('m', key) | ('n', nid)."""
        if n in self._vt:
            return self._vt[n]
        s = self.super_of(n)
        vt = dict(self.vtable(s)) if s else {}
        if n in BUILTINS:
            for k in NATIVES:
                o, name, desc = split_key(k)
                if o == n and name != "<init>" and not is_static_native(k):
                    vt[self.sel(name + desc)] = ("n", NATIVE_IDS[k])
        cf = self.classes.get(n)
        if cf:
            for m in cf.methods:
                if m.is_static or m.name in ("<init>", "<clinit>") or (m.access & cfm.ACC_PRIVATE) or m.is_abstract:
                    continue
                vt[self.sel(m.name + m.desc)] = ("n", self.native_key(n, m.name, m.desc)) if m.is_native \
                    else ("m", (n, m.name, m.desc))
            if not cf.is_interface:
                for i in self.ancestors(n):
                    if not self.is_interface(i):
                        continue
                    for m in self.classes[i].methods:
                        if m.is_static or m.is_abstract or m.name == "<clinit>" or (m.access & cfm.ACC_PRIVATE):
                            continue
                        vt.setdefault(self.sel(m.name + m.desc), ("m", (i, m.name, m.desc)))
        self._vt[n] = vt
        return vt

    def can_devirtualize(self, owner, name, desc, target):
        if target is None:
            return False
        if owner.startswith("[") or f"{owner}.{name}{desc}" in REDIRECTS or self.is_final_class(owner):
            return True
        c = owner
        while c and c in self.classes:
            m = self.classes[c].find_method(name, desc)
            if m:
                return bool(m.access & (cfm.ACC_FINAL | cfm.ACC_PRIVATE))
            c = self.classes[c].super
        return False

    # ------------------------------------------------ entry point
    def find_entry(self):
        cands = []
        for n in sorted(self.user_names):
            for m in self.classes[n].methods:
                if m.is_static and m.name == "main" and m.desc in ("()V", "([Ljava/lang/String;)V"):
                    cands.append((n, m))
        if not cands:
            raise LinkError("no `fun main()` found")
        if self.entry_hint:
            cands = [c for c in cands if c[0] == self.entry_hint] or cands
        noarg = [c for c in cands if c[1].desc == "()V"]
        return (noarg or cands)[0]

    # ------------------------------------------------ RTA
    def analyze(self, entry_key):
        self.live = []                 # method keys in discovery order
        live_set = set()
        self.needed = set()            # classes that need a record
        instantiated, called = set(), set()
        work = []

        def need(c):
            if c and self.known(c) and c not in self.needed:
                self.needed.add(c)
                for a in self.ancestors(c):
                    need(a)

        def add(key):
            if key not in live_set:
                live_set.add(key)
                self.live.append(key)
                work.append(key)
                need(key[0])

        def init(c):  # class initialization: <clinit> of c and its superclasses
            for k in self.superclass_chain(c):
                need(k)
                cf = self.classes.get(k)
                if cf and cf.find_method("<clinit>", "()V"):
                    add((k, "<clinit>", "()V"))

        def instantiate(c):
            if c in instantiated or not self.known(c):
                return
            instantiated.add(c)
            need(c)
            init(c)
            vt = self.vtable(c)
            for s in called:
                t = vt.get(s)
                if t and t[0] == "m":
                    add(t[1])

        def call_sel(s):
            if s in called:
                return
            called.add(s)
            for c in list(instantiated):
                t = self.vtable(c).get(s)
                if t and t[0] == "m":
                    add(t[1])

        for c in (OBJECT, STRING):
            instantiate(c)
        for w in WELL_KNOWN:
            instantiate(w)
        add(entry_key)
        init(entry_key[0])

        while work:
            cname, mname, mdesc = key = work.pop()
            cf = self.classes[cname]
            m = self.method(key)
            for (_s, _e, _h, ct) in m.exc_table:
                if ct:
                    need(cf.class_name(ct))
            for _pc, op, _w, idx in code_cp_refs(m.code):
                e = cf.cp[idx]
                if e[0] == cfm.CLASS:
                    cn = array_elem_class(cf.class_name(idx))
                    if op == 0xbb:
                        instantiate(cn)
                    else:
                        need(cn)
                elif e[0] == cfm.FIELDREF:
                    owner, name, _d = cf.member_ref(idx)
                    f = self.find_field(owner, name)
                    if f:
                        need(f[0])
                        if f[1][0] == "s":
                            init(f[0])
                elif e[0] in (cfm.METHODREF, cfm.IMETHODREF):
                    owner, name, desc = cf.member_ref(idx)
                    r = self.lookup_method(owner, name, desc)
                    need(array_elem_class(owner))
                    if r is None:
                        continue
                    target = r[1] if r[0] == "m" else None
                    if op in (0xb6, 0xb9):  # virtual / interface
                        concrete = None if r[0] == "abstract" else r[1]
                        if self.can_devirtualize(owner, name, desc, concrete):
                            if target:
                                add(target)
                        else:
                            call_sel(self.sel(name + desc))
                    elif target:  # static / special
                        add(target)
                        if op == 0xb8:
                            init(target[0])
        self.instantiated = instantiated

    # ------------------------------------------------ fields
    def compute_layout(self):
        self.inst_slots, self.field_map = {}, {}
        self.static_slots, self.static_inits = 0, []

        def layout(n):
            if n in self.inst_slots:
                return self.inst_slots[n]
            s = self.super_of(n)
            base = layout(s) if s else 0
            cf = self.classes.get(n)
            if cf:
                for f in cf.fields:
                    w = 2 if f.desc[0] in "JD" else 1
                    if f.is_static:
                        if n in self.needed:
                            self.field_map[(n, f.name)] = ("s", self.static_slots, w)
                            if f.const_cp:
                                self.static_inits.append((self.static_slots, cf, f.const_cp))
                            self.static_slots += w
                    else:
                        self.field_map[(n, f.name)] = ("i", base, w)
                        base += w
            self.inst_slots[n] = base
            return base

        for n in self.class_order:
            layout(n)
        thr = self.field_map.get(("java/lang/Throwable", "message"))
        if "java/lang/Throwable" in self.classes and (not thr or thr[1] != 0):
            raise LinkError("shim: Throwable.message must be the first instance field")

    def find_field(self, owner, name):
        """-> (declaring_class, (kind, slot, width)) using field declarations (before layout)."""
        for c in [owner] + self.ancestors(owner):
            cf = self.classes.get(c)
            if cf:
                f = cf.find_field(name)
                if f:
                    return c, ("s" if f.is_static else "i", 0, 2 if f.desc[0] in "JD" else 1)
        return None

    # ------------------------------------------------ main link
    def link(self):
        entry_class, entry_m = self.find_entry()
        entry_key = (entry_class, entry_m.name, entry_m.desc)
        self.analyze(entry_key)
        names = sorted(n for n in self.needed if n not in BUILTINS)
        self.class_order = [OBJECT, STRING] + names
        self.class_ids = {n: i for i, n in enumerate(self.class_order)}
        # methods: deterministic order (by class, then declaration order)
        live = set(self.live)
        self.method_list, self.method_ids = [], {}
        for n in self.class_order:
            cf = self.classes.get(n)
            if cf:
                for m in cf.methods:
                    if (n, m.name, m.desc) in live:
                        self.method_ids[(n, m.name, m.desc)] = len(self.method_list)
                        self.method_list.append((n, m))
        if len(self.method_list) >= NATIVE_BIT:
            raise LinkError("too many methods")
        self.compute_layout()
        return self.emit(self.method_ids[entry_key], 0 if entry_m.desc == "()V" else 1)

    def target_id(self, r):
        if r is None or r[0] == "abstract":
            return NO_METHOD
        if r[0] == "n":
            return NATIVE_BIT | r[1]
        return self.method_ids.get(r[1], NO_METHOD)

    # ------------------------------------------------ constant pool
    def resolve_entry(self, n, cf, i, op):
        e = cf.cp[i]
        t = e[0]
        if t == cfm.INTEGER:
            return [(CP_INT, 0, 0, e[1] & 0xFFFFFFFF)]
        if t == cfm.FLOAT:
            return [(CP_INT, 0, 0, e[1])]
        if t in (cfm.LONG, cfm.DOUBLE):
            return [(CP_WIDE, 0, 0, e[1] & 0xFFFFFFFF), (CP_WIDE, 0, 0, (e[1] >> 32) & 0xFFFFFFFF)]
        if t == cfm.STRING:
            return [(CP_STR, 0, self.sid(cf.utf8(e[1])), 0)]
        if t == cfm.CLASS:
            cname = cf.class_name(i)
            if cname.startswith("["):
                return [(CP_CLASS, 0, CID_ARRAY, self.sid(cname))]
            if cname in self.class_ids:
                return [(CP_CLASS, 0, self.class_ids[cname], 0)]
            if op == 0xbb:
                self.warn(n, f"class {nice(cname)} is not available on the device")
            return [(CP_CLASS, 0, CID_UNKNOWN, self.sid(nice(cname)))]
        if t == cfm.FIELDREF:
            owner, name, _d = cf.member_ref(i)
            for c in [owner] + self.ancestors(owner):
                f = self.field_map.get((c, name))
                if f:
                    kind, slot, w = f
                    if kind == "s":
                        return [(CP_FIELD_S, w, slot, self.class_ids[c])]
                    return [(CP_FIELD_I, w, slot, 0)]
            self.warn(n, f"field {nice(owner)}.{name} is not available on the device")
            return [(CP_UNRESOLVED, 0, 0, self.sid(f"field {nice(owner)}.{name}"))]
        if t in (cfm.METHODREF, cfm.IMETHODREF):
            owner, name, desc = cf.member_ref(i)
            r = self.lookup_method(owner, name, desc)
            if r is None:
                self.warn(n, f"{nice(owner)}.{name}{desc} is not available on the device")
                return [(CP_UNRESOLVED, 0, 0, self.sid(f"{nice(owner)}.{name}{desc}"))]
            target = self.target_id(r)
            nargs = arg_slots(desc)
            if nargs > 255:
                raise LinkError(f"too many arguments: {owner}.{name}")
            devirt = self.can_devirtualize(owner, name, desc, r[1] if r[0] != "abstract" else None)
            return [(CP_METHOD if devirt else CP_METHOD_V, nargs, target, self.sel(name + desc))]
        return [(CP_NONE, 0, 0, 0)]

    def compact_cp(self, n, cf, methods):
        """Build a compact resolved CP for the live methods of a class and rewrite their code."""
        refs = []  # (method_index, pc, op, width, old_idx)
        for mi, m in enumerate(methods):
            for pc, op, w, idx in code_cp_refs(m.code):
                refs.append((mi, pc, op, w, idx))
        remap, cp = {}, [(CP_NONE, 0, 0, 0)]
        first_op = {}
        for (_mi, _pc, op, _w, idx) in refs:
            first_op.setdefault(idx, op)
        # 1-byte operands (ldc) must land below 256: allocate them first
        order = sorted(first_op, key=lambda i: (0 if any(r[4] == i and r[3] == 1 for r in refs) else 1, i))
        for idx in order:
            remap[idx] = len(cp)
            cp.extend(self.resolve_entry(n, cf, idx, first_op[idx]))
        codes = [bytearray(m.code) for m in methods]
        for (mi, pc, _op, w, idx) in refs:
            new = remap[idx]
            if w == 1:
                if new > 255:
                    raise LinkError(f"{nice(n)}: too many ldc constants")
                codes[mi][pc + 1] = new
            else:
                codes[mi][pc + 1] = new >> 8
                codes[mi][pc + 2] = new & 0xFF
        if len(cp) > 0xFFFF:
            raise LinkError(f"{nice(n)}: constant pool too large")
        return cp, [bytes(c) for c in codes]

    # ------------------------------------------------ emit image
    def emit(self, entry_mid, entry_args):
        class_methods = {}
        for (n, m) in self.method_list:
            class_methods.setdefault(n, []).append(m)

        class_blobs, new_code = [], {}
        for n in self.class_order:
            cf = self.classes.get(n)
            blob = bytearray()
            b16 = lambda v: blob.extend(struct.pack("<H", v & 0xFFFF))  # noqa: E731
            sup = self.super_of(n)
            b16(self.sid(nice(n)))
            b16(self.sid(cf.source_file) if cf and cf.source_file else 0xFFFF)
            b16(self.class_ids.get(sup, 0xFFFF))
            b16(self.inst_slots.get(n, 0))
            b16(self.method_ids.get((n, "<clinit>", "()V"), NO_METHOD))
            flags = (CF_BUILTIN if n in BUILTINS else 0) | (CF_INTERFACE if cf and cf.is_interface else 0) \
                | (CF_ABSTRACT if cf and (cf.access & cfm.ACC_ABSTRACT) else 0)
            blob.append(flags)
            sups = [self.class_ids[a] for a in self.ancestors(n) if a in self.class_ids]
            b16(len(sups))
            for s in sups:
                b16(s)
            vt = []
            if n in self.instantiated and not (cf and cf.is_interface):
                for s, t in sorted(self.vtable(n).items()):
                    tid = self.target_id(t)
                    if tid != NO_METHOD:
                        vt.append((s, tid))
            b16(len(vt))
            for s, t in vt:
                b16(s); b16(t)
            ms = class_methods.get(n, [])
            if cf and ms:
                cp, codes = self.compact_cp(n, cf, ms)
                for m, c in zip(ms, codes):
                    new_code[id(m)] = c
            else:
                cp = []
            b16(len(cp))
            for (tag, aux, a, b) in cp:
                blob.extend(struct.pack("<BBHI", tag, aux, a & 0xFFFF, b & 0xFFFFFFFF))
            class_blobs.append(blob)

        method_blobs = []
        for (n, m) in self.method_list:
            cf = self.classes[n]
            code = new_code[id(m)]
            nargs = arg_slots(m.desc) + (0 if m.is_static else 1)
            blob = bytearray(struct.pack("<HHBHHHBH", self.class_ids[n], self.sid(f"{nice(n)}.{m.name}"),
                                         MF_STATIC if m.is_static else 0, m.max_stack,
                                         max(m.max_locals, nargs), nargs, ret_slots(m.desc), 0))
            blob.extend(struct.pack("<I", len(code)))
            blob.extend(code)
            blob.extend(struct.pack("<H", len(m.exc_table)))
            for (s, e, h, ct) in m.exc_table:
                cid = CID_ANY if ct == 0 else self.class_ids.get(cf.class_name(ct), CID_UNKNOWN)
                blob.extend(struct.pack("<HHHH", s, e, h, cid))
            lines = sorted(m.lines)
            blob.extend(struct.pack("<H", len(lines)))
            for (pc, ln) in lines:
                blob.extend(struct.pack("<HH", pc, ln))
            method_blobs.append(blob)

        sinit = bytearray()
        for (slot, cf, cpi) in self.static_inits:
            e = cf.cp[cpi]
            if e[0] in (cfm.INTEGER, cfm.FLOAT):
                sinit.extend(struct.pack("<HBI", slot, 0, e[1] & 0xFFFFFFFF))
            elif e[0] in (cfm.LONG, cfm.DOUBLE):
                sinit.extend(struct.pack("<HBII", slot, 1, e[1] & 0xFFFFFFFF, (e[1] >> 32) & 0xFFFFFFFF))
            elif e[0] == cfm.STRING:
                sinit.extend(struct.pack("<HBI", slot, 2, self.sid(cf.utf8(e[1]))))

        if len(self.strings) > 0xFFFF:
            raise LinkError("too many strings")
        out = bytearray(b"KTV1")
        out += struct.pack("<HHHHHHB", 1, len(self.strings), len(self.class_order), len(self.method_list),
                           self.static_slots, entry_mid, entry_args)
        out += struct.pack("<B", len(WELL_KNOWN))
        for w in WELL_KNOWN:
            out += struct.pack("<H", self.class_ids.get(w, 0xFFFF))
        out += struct.pack("<H", len(self.static_inits)) + sinit
        for s in self.strings:
            b = s.encode("utf-8", "surrogatepass")
            out += struct.pack("<H", len(b)) + b
        for b in class_blobs:
            out += b
        for b in method_blobs:
            out += b
        self.stats = dict(classes=len(self.class_order), methods=len(self.method_list),
                          strings=len(self.strings), bytes=len(out),
                          code=sum(len(new_code[id(m)]) for _, m in self.method_list))
        return bytes(out)


def load_dir(path, rename=None):
    res = []
    for root, _dirs, files in os.walk(path):
        for f in sorted(files):
            if f.endswith(".class"):
                p = os.path.join(root, f)
                with open(p, "rb") as fh:
                    res.append(ClassFile(fh.read(), p, rename))
    return res


def shim_rename(s):
    return s.replace(SHIM_PREFIX, "")


def link_dirs(user_dir, shim_dir, entry_hint=None):
    lk = Linker(load_dir(user_dir), load_dir(shim_dir, shim_rename), entry_hint)
    return lk.link(), lk
