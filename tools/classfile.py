"""Minimal Java .class file parser (enough for linking Kotlin/Java bytecode)."""
import struct

# constant pool tags
UTF8, INTEGER, FLOAT, LONG, DOUBLE, CLASS, STRING = 1, 3, 4, 5, 6, 7, 8
FIELDREF, METHODREF, IMETHODREF, NAMEANDTYPE = 9, 10, 11, 12
METHODHANDLE, METHODTYPE, DYNAMIC, INVOKEDYNAMIC, MODULE, PACKAGE = 15, 16, 17, 18, 19, 20

ACC_PUBLIC, ACC_PRIVATE, ACC_STATIC, ACC_FINAL = 0x1, 0x2, 0x8, 0x10
ACC_NATIVE, ACC_INTERFACE, ACC_ABSTRACT = 0x100, 0x200, 0x400


def decode_mutf8(b: bytes) -> str:
    """Decode Java 'modified UTF-8' (CESU-8 surrogates, 0xC0 0x80 for NUL)."""
    out = []
    i, n = 0, len(b)
    while i < n:
        c = b[i]
        if c < 0x80:
            out.append(c); i += 1
        elif (c & 0xE0) == 0xC0:
            out.append(((c & 0x1F) << 6) | (b[i + 1] & 0x3F)); i += 2
        else:
            out.append(((c & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F)); i += 3
    # join UTF-16 surrogate pairs
    res, j = [], 0
    while j < len(out):
        u = out[j]
        if 0xD800 <= u < 0xDC00 and j + 1 < len(out) and 0xDC00 <= out[j + 1] < 0xE000:
            res.append(chr(0x10000 + ((u - 0xD800) << 10) + (out[j + 1] - 0xDC00))); j += 2
        else:
            res.append(chr(u)); j += 1
    return "".join(res)


class Method:
    def __init__(self, access, name, desc):
        self.access, self.name, self.desc = access, name, desc
        self.code = None
        self.max_stack = self.max_locals = 0
        self.exc_table = []  # (start, end, handler, catch_cp_index)
        self.lines = []  # (start_pc, line)

    @property
    def is_static(self): return bool(self.access & ACC_STATIC)

    @property
    def is_native(self): return bool(self.access & ACC_NATIVE)

    @property
    def is_abstract(self): return bool(self.access & ACC_ABSTRACT)

    def __repr__(self): return f"<Method {self.name}{self.desc}>"


class Field:
    def __init__(self, access, name, desc):
        self.access, self.name, self.desc = access, name, desc
        self.const_cp = None  # ConstantValue attribute cp index

    @property
    def is_static(self): return bool(self.access & ACC_STATIC)


class ClassFile:
    def __init__(self, data: bytes, origin="", rename=None):
        self.origin = origin
        self._d, self._p = data, 0
        if self._u4() != 0xCAFEBABE:
            raise ValueError(f"{origin}: not a class file")
        self._u2(); self._u2()  # minor, major
        n = self._u2()
        self.cp = [None] * n
        i = 1
        while i < n:
            tag = self._u1()
            if tag == UTF8:
                ln = self._u2(); u = decode_mutf8(self._take(ln))
                self.cp[i] = (UTF8, rename(u) if rename else u)
            elif tag == INTEGER:
                self.cp[i] = (INTEGER, struct.unpack(">i", self._take(4))[0])
            elif tag == FLOAT:
                self.cp[i] = (FLOAT, struct.unpack(">I", self._take(4))[0])  # raw bits
            elif tag in (LONG, DOUBLE):
                self.cp[i] = (tag, struct.unpack(">Q", self._take(8))[0])  # raw bits
                i += 1
            elif tag in (CLASS, STRING, METHODTYPE, MODULE, PACKAGE):
                self.cp[i] = (tag, self._u2())
            elif tag in (FIELDREF, METHODREF, IMETHODREF, NAMEANDTYPE, DYNAMIC, INVOKEDYNAMIC):
                self.cp[i] = (tag, self._u2(), self._u2())
            elif tag == METHODHANDLE:
                self.cp[i] = (tag, self._u1(), self._u2())
            else:
                raise ValueError(f"{origin}: bad cp tag {tag}")
            i += 1
        self.access = self._u2()
        self.name = self.class_name(self._u2())
        sup = self._u2()
        self.super = self.class_name(sup) if sup else None
        self.interfaces = [self.class_name(self._u2()) for _ in range(self._u2())]
        self.fields = []
        for _ in range(self._u2()):
            f = Field(self._u2(), self.utf8(self._u2()), self.utf8(self._u2()))
            for an, ad in self._attrs():
                if an == "ConstantValue":
                    f.const_cp = struct.unpack(">H", ad)[0]
            self.fields.append(f)
        self.methods = []
        for _ in range(self._u2()):
            m = Method(self._u2(), self.utf8(self._u2()), self.utf8(self._u2()))
            for an, ad in self._attrs():
                if an == "Code":
                    m.max_stack, m.max_locals, clen = struct.unpack(">HHI", ad[:8])
                    m.code = ad[8:8 + clen]
                    p = 8 + clen
                    (ne,) = struct.unpack(">H", ad[p:p + 2]); p += 2
                    for _k in range(ne):
                        m.exc_table.append(struct.unpack(">HHHH", ad[p:p + 8])); p += 8
                    (na,) = struct.unpack(">H", ad[p:p + 2]); p += 2
                    for _k in range(na):
                        ani, aln = struct.unpack(">HI", ad[p:p + 6]); p += 6
                        if self.utf8(ani) == "LineNumberTable":
                            (nl,) = struct.unpack(">H", ad[p:p + 2])
                            for q in range(nl):
                                m.lines.append(struct.unpack(">HH", ad[p + 2 + 4 * q:p + 6 + 4 * q]))
                        p += aln
            self.methods.append(m)
        self.source_file = None
        for an, ad in self._attrs():
            if an == "SourceFile":
                self.source_file = self.utf8(struct.unpack(">H", ad)[0])
        del self._d

    # --- helpers
    def _take(self, n):
        b = self._d[self._p:self._p + n]; self._p += n; return b

    def _u1(self): return self._take(1)[0]

    def _u2(self): return struct.unpack(">H", self._take(2))[0]

    def _u4(self): return struct.unpack(">I", self._take(4))[0]

    def _attrs(self):
        res = []
        for _ in range(self._u2()):
            name = self.utf8(self._u2())
            ln = self._u4()
            res.append((name, self._take(ln)))
        return res

    def utf8(self, i):
        return self.cp[i][1]

    def class_name(self, i):
        return self.utf8(self.cp[i][1])

    def name_and_type(self, i):
        _, n, t = self.cp[i]
        return self.utf8(n), self.utf8(t)

    def member_ref(self, i):
        """Return (owner, name, descriptor) of a Field/Method/InterfaceMethod ref."""
        _, ci, nti = self.cp[i]
        n, t = self.name_and_type(nti)
        return self.class_name(ci), n, t

    @property
    def is_interface(self): return bool(self.access & ACC_INTERFACE)

    def find_method(self, name, desc):
        for m in self.methods:
            if m.name == name and m.desc == desc:
                return m
        return None

    def find_field(self, name):
        for f in self.fields:
            if f.name == name:
                return f
        return None


def parse_descriptor_args(desc):
    """Return (list_of_arg_descriptors, return_descriptor)."""
    assert desc[0] == "("
    i, args = 1, []
    while desc[i] != ")":
        j = i
        while desc[j] == "[":
            j += 1
        if desc[j] == "L":
            j = desc.index(";", j)
        args.append(desc[i:j + 1])
        i = j + 1
    return args, desc[i + 1:]


def slots_of(t):
    return 0 if t == "V" else (2 if t in ("J", "D") else 1)


def arg_slots(desc):
    a, _ = parse_descriptor_args(desc)
    return sum(slots_of(x) for x in a)


def ret_slots(desc):
    return slots_of(parse_descriptor_args(desc)[1])
