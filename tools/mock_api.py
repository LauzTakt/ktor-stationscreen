"""Mock of the twin REST API v1 + WebSocket (ktor_app/docs/API.md), served by `./ktesp screen`.

Only what a station screen needs, with the same shapes and rules as the real API:
    GET /api/v1/status
    GET /api/v1/stations[?q=&limit=]           GET /api/v1/stations/{stopId}
    GET /api/v1/stations/{stopId}/board?type=departures|arrivals|all&windowMin=&limit=&includeSkipped=
    GET /api/v1/stations/{stopId}/incidents    GET /api/v1/stations/{stopId}/screen?limit=&windowMin=
    GET /api/v1/incidents[?all=&severity=&scopeKind=]
    GET /api/v1/incidents/{id}
    WS  /api/v1/ws   {"op":"subscribe","topic":"station"|"screen","id":...,"frequencySec":..,"windowMin":..,"limit":..}
                     / unsubscribe / ping

Trains follow a clock-face timetable around Lausanne; incidents are created, updated and removed by the buttons of
the screen app (or automatically) and change the delays/cancellations of the affected lines.
"""
import hashlib
import json
import random
import threading
import time

# UIC / DIDOK stop ids
STATIONS = {
    "8501026": "Genève-Aéroport", "8501008": "Genève", "8501037": "Morges", "8501118": "Renens VD",
    "8501120": "Lausanne", "8501200": "Vevey", "8501300": "Montreux", "8504200": "Yverdon-les-Bains",
    "8504221": "Neuchâtel", "8504300": "Biel/Bienne", "8504100": "Fribourg/Freiburg", "8507000": "Bern",
    "8505000": "Luzern", "8503000": "Zürich HB",
}
GVA, GE, MOR, REN, LS, VV, MX, YV, NE, BI, FR, BN, LU, ZH = (
    "8501026", "8501008", "8501037", "8501118", "8501120", "8501200", "8501300", "8504200", "8504221", "8504300",
    "8504100", "8507000", "8505000", "8503000")

# line -> (category, number base, [(stopId, minutes from origin)], period min, first departure minute)
LINES = {
    "IC1": ("IC", 700, [(GVA, 0), (GE, 7), (LS, 42), (FR, 85), (BN, 107), (ZH, 164)], 30, 15),
    "IC5": ("IC", 500, [(GE, 0), (MOR, 28), (LS, 40), (YV, 62), (NE, 85), (BI, 102), (ZH, 160)], 60, 50),
    "IR15": ("IR", 2500, [(GE, 0), (LS, 36), (FR, 80), (BN, 104), (LU, 168)], 60, 26),
    "RE33": ("RE", 3300, [(GE, 0), (MOR, 25), (LS, 37), (VV, 50), (MX, 57)], 30, 2),
    "S1": ("S", 1100, [(YV, 0), (MOR, 22), (REN, 30), (LS, 36), (VV, 49), (MX, 56)], 30, 0),
}

TYPES = [
    ("overhead_line_fault", "Overhead line fault"), ("signal_failure", "Signal box failure"),
    ("rolling_stock_failure", "Defective train"), ("construction", "Engineering works"),
    ("emergency_services", "Emergency services operation"), ("points_failure", "Points failure"),
    ("staff_shortage", "Staff shortage"), ("weather", "Severe weather"),
]
SEVERITIES = ["info", "minor", "major", "critical"]


def _stable(s, n):
    return int(hashlib.md5(s.encode()).hexdigest()[:8], 16) % n


class MockTwinApi:
    def __init__(self, log=lambda m: None):
        self.log = log
        self.lock = threading.Lock()
        self.incidents = {}      # id -> incident dto (+ "_lines", "_stations")
        self.sessions = []       # live WebSocket sessions
        self.auto = True
        self.seq = 0
        for _ in range(2):
            self._create(random.choice(["minor", "major"]))
        threading.Thread(target=self._auto_loop, daemon=True).start()

    # ---------------------------------------------------------------- clock / timetable
    @staticmethod
    def now():
        return int(time.time() * 1000)

    def _effect(self, line):
        """(extra delay s, cancelled) on [line] from the active incidents."""
        delay, cancelled = 0, False
        for inc in self.incidents.values():
            if line in inc["_lines"] and inc["active"]:
                sev = inc["severity"]
                if sev == "critical":
                    cancelled = True
                elif sev == "major":
                    delay = max(delay, 60 * (15 + _stable(inc["id"], 16)))
                elif sev == "minor":
                    delay = max(delay, 60 * (3 + _stable(inc["id"], 6)))
        return delay, cancelled

    def _calls(self, stop_id, now, window_min):
        """Every train call at [stop_id] around now: (line, direction stops, index, run start ms)."""
        out = []
        day = now - now % 86400000
        for line, (cat, numbase, stops, period, first) in LINES.items():
            for direction in (0, 1):
                seq = stops if direction == 0 else [(s, stops[-1][1] - m) for s, m in reversed(stops)]
                idx = next((i for i, (s, _) in enumerate(seq) if s == stop_id), None)
                if idx is None:
                    continue
                off = seq[idx][1] * 60000
                base = day + ((first + 17 * direction) % period) * 60000
                k0 = (now - off - base - 3 * 3600000) // (period * 60000)
                k1 = (now + window_min * 60000 - off - base) // (period * 60000) + 1
                for k in range(int(k0), int(k1) + 1):
                    start = base + k * period * 60000
                    out.append((line, direction, seq, idx, start))
        return out

    def _train(self, line, direction, seq, start):
        cat, numbase, _, _, _ = LINES[line]
        number = numbase + direction + 2 * (int(start // 60000) % 500)
        tid = time.strftime("%Y%m%d", time.gmtime(start / 1000)) + f"_mock-{line}-{direction}.{number}"
        delay, cancelled = self._effect(line)
        delay += _stable(tid, 4) * 30
        return tid, cat, str(number), delay, cancelled

    def _stop(self, seq, i, start, delay, now):
        sid, m = seq[i]
        planned = start + m * 60000
        dwell = 0 if i in (0, len(seq) - 1) else 60000
        exp = planned + delay * 1000
        state = "passed" if exp + dwell < now else ("current" if exp <= now else "next")
        return {"seq": i, "stopId": sid, "name": STATIONS[sid], "lat": None, "lon": None,
                "plannedArr": planned if i else None, "plannedDep": planned + dwell if i < len(seq) - 1 else None,
                "expectedArr": exp if i else None, "expectedDep": exp + dwell if i < len(seq) - 1 else None,
                "actualArr": None, "actualDep": None, "arrDelaySec": delay if i else None,
                "depDelaySec": delay if i < len(seq) - 1 else None, "skipped": False, "state": state}

    def board(self, stop_id, typ="departures", window_min=120, limit=50, include_skipped=True, now=None):
        now = now or self.now()
        entries = []
        for line, direction, seq, idx, start in self._calls(stop_id, now, window_min):
            if start > now:
                continue          # only trains already in service are known (see API.md)
            starts, ends = idx == 0, idx == len(seq) - 1
            if (typ == "departures" and ends) or (typ == "arrivals" and starts):
                continue
            tid, cat, number, delay, cancelled = self._train(line, direction, seq, start)
            stop = self._stop(seq, idx, start, delay, now)
            planned = stop["plannedDep"] if typ != "arrivals" and not ends else stop["plannedArr"]
            t = planned + delay * 1000
            at = stop["state"] == "current"
            if stop["state"] == "passed" or (t > now + window_min * 60000 and not at):
                continue
            entries.append({
                "trainId": tid, "line": line, "category": cat, "number": number,
                "operator": "Schweizerische Bundesbahnen SBB", "headsign": STATIONS[seq[-1][0]],
                "status": "cancelled" if cancelled else "active",
                "origin": {"stopId": seq[0][0], "name": STATIONS[seq[0][0]]},
                "destination": {"stopId": seq[-1][0], "name": STATIONS[seq[-1][0]]},
                "stop": stop, "time": t, "plannedTime": planned, "delaySec": delay,
                "atStation": at, "startsHere": starts, "terminatesHere": ends})
        entries.sort(key=lambda e: (not e["atStation"], e["time"]))
        return {"station": self.station(stop_id), "type": typ, "now": now, "windowMin": window_min,
                "entries": entries[:limit]}

    def screen(self, stop_id, limit=6, window_min=120):
        """Compact view for small displays (docs/API.md, "Station screen")."""
        b = self.board(stop_id, "departures", window_min, limit, False)
        rank = {"critical": 0, "major": 1, "minor": 2, "info": 3}
        incs = []
        for inc in self.incidents.values():
            if not inc["active"]:
                continue
            rel = self.relate(inc, stop_id)
            if rel:
                incs.append({"id": inc["id"], "severity": inc["severity"], "title": inc["title"], "where": inc["where"],
                             "atStation": rel[0]["match"] is not None, "trains": len(rel[1]),
                             "updatedAt": inc["updatedAt"]})
        incs.sort(key=lambda i: (rank.get(i["severity"], 4), not i["atStation"], -i["updatedAt"]))
        return {"stopId": stop_id, "name": STATIONS[stop_id], "now": b["now"],
                "departures": [{"plannedTime": e["plannedTime"], "delaySec": e["delaySec"] or 0, "line": e["line"],
                                "category": e["category"], "to": e["destination"]["name"],
                                "cancelled": e["status"] == "cancelled", "atStation": e["atStation"]}
                               for e in b["entries"]],
                "incidents": incs[:5]}

    @staticmethod
    def station(stop_id):
        return {"stopId": stop_id, "name": STATIONS[stop_id], "lat": None, "lon": None}

    # ---------------------------------------------------------------- incidents
    def _dto(self, inc, match=None):
        d = {k: v for k, v in inc.items() if not k.startswith("_")}
        d["match"] = match
        return d

    def _station_match(self, inc, stop_id):
        sc = inc["scope"]
        if sc["kind"] == "network":
            return "network"
        if sc["kind"] == "station" and sc["stopId"] == stop_id:
            return "station"
        if sc["kind"] == "section" and stop_id in (sc["from"], sc["to"]):
            return "endpoint"
        return None

    def relate(self, inc, stop_id, now=None):
        """(incident with match, trains) for a station subscription, or None (rules of API.md)."""
        m = self._station_match(inc, stop_id)
        trains = []
        if inc["_lines"]:
            for e in self.board(stop_id, "all", 120, 500, True, now)["entries"]:
                if e["line"] in inc["_lines"]:
                    trains.append({"trainId": e["trainId"], "match": "line" if inc["scope"]["kind"] == "line" else "route"})
        if m is None and not trains:
            return None
        return self._dto(inc, m), sorted(trains, key=lambda t: t["trainId"])

    def _create(self, severity=None, kind=None):
        now = self.now()
        self.seq += 1
        line = random.choice(list(LINES))
        stops = [s for s, _ in LINES[line][2]]
        typ, label = random.choice(TYPES)
        kind = kind or random.choice(["section", "section", "station", "line"])
        severity = severity or random.choice(SEVERITIES)
        if kind == "section":
            i = random.randrange(len(stops) - 1)
            a, b = stops[i], stops[i + 1]
            where = f"{STATIONS[a]} – {STATIONS[b]}"
            scope = {"kind": "section", "from": a, "to": b, "stopId": None, "trainId": None, "line": None}
            lines = {ln for ln, v in LINES.items() if a in [s for s, _ in v[2]] and b in [s for s, _ in v[2]]}
        elif kind == "station":
            a = random.choice(stops)
            where = STATIONS[a]
            scope = {"kind": "station", "from": None, "to": None, "stopId": a, "trainId": None, "line": None}
            lines = set() if severity == "info" else {line}
            if severity == "info":
                label = random.choice(["Lift out of service", "Ticket machines out of order", "Platform change"])
        else:
            where = line
            scope = {"kind": "line", "from": None, "to": None, "stopId": None, "trainId": None, "line": line}
            lines = {line}
        iid = "i-" + hashlib.md5(f"{now}{self.seq}".encode()).hexdigest()[:8]
        title = f"{label} on line {line}" if kind == "line" else f"{label}: {where}"
        inc = {"id": iid, "title": title, "description": "", "severity": severity, "type": typ,
               "reasonCode": None, "scope": scope, "where": where, "disruptionId": "d-" + iid[2:],
               "status": "active", "active": True, "createdAt": now, "updatedAt": now, "firstSeenAt": now,
               "removedAt": None, "geometry": None, "_lines": lines}
        self.incidents[iid] = inc
        return inc

    def _notify(self, inc, event):
        for s in list(self.sessions):
            s.incident_changed(inc, event)

    def action(self, what):
        with self.lock:
            active = [i for i in self.incidents.values() if i["active"]]
            if what == "new":
                inc = self._create()
                self.log(f"[api] new incident ({inc['severity']}): {inc['title']}\n")
                self._notify(inc, "created")
            elif what == "update" and active:
                inc = random.choice(active)
                i = SEVERITIES.index(inc["severity"]) + random.choice([-1, 1])
                inc["severity"] = SEVERITIES[max(0, min(3, i))]
                inc["updatedAt"] = self.now()
                self.log(f"[api] incident updated ({inc['severity']}): {inc['title']}\n")
                self._notify(inc, "updated")
            elif what == "resolve" and active:
                self._remove(random.choice(active))
            elif what == "clear":
                for inc in active:
                    self._remove(inc)
            elif what == "auto-on":
                self.auto = True
            elif what == "auto-off":
                self.auto = False
            return {"auto": self.auto, "count": len([i for i in self.incidents.values() if i["active"]]),
                    "ws": len(self.sessions)}

    def _remove(self, inc):
        now = self.now()
        inc.update(status="removed", active=False, removedAt=now, updatedAt=now)
        self.log(f"[api] incident removed: {inc['title']}\n")
        self._notify(inc, "removed")

    def _auto_loop(self):
        while True:
            time.sleep(random.uniform(12, 25))
            if not self.auto:
                continue
            n = len([i for i in self.incidents.values() if i["active"]])
            r = random.random()
            self.action("new" if n < 1 or (r < 0.4 and n < 4) else ("resolve" if r < 0.7 else "update"))

    # ---------------------------------------------------------------- REST
    def get(self, path, query):
        """Returns (http status, json object) for GET /api/v1/..."""
        q = {k: v[0] for k, v in query.items()}
        parts = [p for p in path.split("/") if p][2:]   # after api/v1
        err = lambda code, msg, st=404: (st, {"error": {"code": code, "message": msg}})

        def intp(name, default, lo, hi):
            v = q.get(name)
            if v is None:
                return default
            if not v.isdigit() or not lo <= int(v) <= hi:
                raise ValueError(name)
            return int(v)

        with self.lock:
            try:
                if parts == ["status"]:
                    return 200, {"now": self.now(), "mqttConnected": True, "trains": 0, "positions": 0,
                                 "stations": len(STATIONS), "lastFleetTs": self.now(),
                                 "twin": {"streamId": "mock", "ts": self.now(), "tick": 0, "trainsActive": 0,
                                          "trainsSimulated": 0, "timetableDate": time.strftime("%Y-%m-%d"),
                                          "online": True}}
                if parts == ["stations"]:
                    name = q.get("q", "").lower()
                    res = [self.station(i) for i, n in STATIONS.items() if not name or name in n.lower() or name == i]
                    return 200, res[:intp("limit", 50, 1, 5000)]
                if len(parts) >= 2 and parts[0] == "stations":
                    sid = parts[1]
                    if sid not in STATIONS:
                        return err("station_not_found", f"station '{sid}' not found")
                    if len(parts) == 2:
                        incs = [r[0] for r in (self.relate(i, sid) for i in self.incidents.values() if i["active"])
                                if r and r[0]["match"]]
                        calls = self.board(sid, "all", 1440, 500)["entries"]
                        return 200, {"station": self.station(sid), "trainsPresent": sum(e["atStation"] for e in calls),
                                     "trainsUpcoming": len(calls), "incidents": incs}
                    if parts[2:] == ["board"]:
                        typ = q.get("type", "departures")
                        if typ not in ("departures", "arrivals", "all"):
                            return err("bad_type", "type must be departures, arrivals or all", 400)
                        return 200, self.board(sid, typ, intp("windowMin", 120, 1, 1440), intp("limit", 50, 1, 500),
                                               q.get("includeSkipped", "true") != "false")
                    if parts[2:] == ["screen"]:
                        return 200, self.screen(sid, intp("limit", 6, 1, 20), intp("windowMin", 120, 1, 1440))
                    if parts[2:] == ["incidents"]:
                        return 200, [r[0] for r in (self.relate(i, sid) for i in self.incidents.values() if i["active"])
                                     if r and r[0]["match"]]
                if parts == ["incidents"]:
                    res = [self._dto(i) for i in self.incidents.values() if i["active"] or q.get("all") == "true"]
                    if q.get("severity"):
                        res = [i for i in res if i["severity"] == q["severity"]]
                    if q.get("scopeKind"):
                        res = [i for i in res if i["scope"]["kind"] == q["scopeKind"]]
                    return 200, res
                if len(parts) == 2 and parts[0] == "incidents":
                    inc = self.incidents.get(parts[1])
                    return (200, self._dto(inc)) if inc else err("incident_not_found", f"incident '{parts[1]}' not found")
            except ValueError as e:
                return err(f"bad_{e}", f"invalid {e}", 400)
        return err("not_found", f"unknown endpoint {path}")


class WsSession:
    """One /api/v1/ws connection: station/train subscriptions (stations only in the mock)."""

    def __init__(self, api, send):
        self.api, self.send = api, send
        self.subs = {}           # stopId -> {"freq": s, "window": min, "due": ms}
        self.lock = threading.Lock()

    def on_text(self, text):
        try:
            r = json.loads(text)
        except ValueError:
            return self._err("bad_request", "invalid JSON")
        op, topic, sid = r.get("op"), r.get("topic"), r.get("id")
        if op == "ping":
            return self.send({"type": "pong", "ts": self.api.now()})
        if op not in ("subscribe", "unsubscribe"):
            return self._err("bad_op", f"unknown op {op!r}")
        if topic not in ("station", "train", "screen"):
            return self._err("bad_topic", "topic must be train, station or screen", topic, sid)
        if not sid:
            return self._err("bad_id", "id (trainId or stopId) is required", topic, sid)
        if topic == "train":
            return self._err("train_not_found", f"train '{sid}' not found (the mock only serves stations)", topic, sid)
        if op == "unsubscribe":
            with self.lock:
                self.subs.pop((topic, sid), None)
            return self.send({"type": "unsubscribed", "topic": topic, "id": sid})
        freq, window = r.get("frequencySec", 5), r.get("windowMin", 120)
        if not isinstance(freq, int) or not 1 <= freq <= 3600:
            return self._err("bad_frequencySec", "frequencySec must be between 1 and 3600", topic, sid)
        if not isinstance(window, int) or not 1 <= window <= 1440:
            return self._err("bad_windowMin", "windowMin must be between 1 and 1440", topic, sid)
        limit = r.get("limit", 6)
        if topic == "screen" and (not isinstance(limit, int) or not 1 <= limit <= 20):
            return self._err("bad_limit", "limit must be between 1 and 20", topic, sid)
        if sid not in STATIONS:
            return self._err("station_not_found", f"station '{sid}' not found", topic, sid)
        if topic == "screen":
            self.send({"type": "subscribed", "topic": "screen", "id": sid, "frequencySec": freq, "windowMin": window,
                       "limit": limit})
            with self.lock:
                self.subs[("screen", sid)] = {"freq": freq, "window": window, "limit": limit, "due": 0}
            return
        self.send({"type": "subscribed", "topic": "station", "id": sid, "frequencySec": freq, "windowMin": window,
                   "limit": None})
        with self.api.lock:
            for inc in list(self.api.incidents.values()):
                if inc["active"]:
                    self._incident(sid, inc, "active")
        with self.lock:
            self.subs[("station", sid)] = {"freq": freq, "window": window, "due": 0}

    def _err(self, code, msg, topic=None, sid=None):
        self.send({"type": "error", "code": code, "message": msg, "topic": topic, "id": sid})

    def _incident(self, sid, inc, event):
        rel = self.api.relate(inc, sid)
        if rel is None and event == "removed":
            rel = (self.api._dto(inc, self.api._station_match(inc, sid)), [])
        if rel:
            self.send({"type": "incident", "topic": "station", "id": sid, "event": event, "ts": self.api.now(),
                       "incident": rel[0], "trains": rel[1]})

    def incident_changed(self, inc, event):
        """Called with the API lock held."""
        with self.lock:
            keys = list(self.subs.items())
        for (topic, sid), sub in keys:
            if topic == "station":
                self._incident(sid, inc, event)
            elif event in ("removed", "missing") or self.api.relate(inc, sid):
                self._send_screen(sid, sub)   # screens are refreshed immediately on related incident changes

    def _send_screen(self, sid, sub):
        self.send({"type": "screen_update", "topic": "screen", "id": sid, "ts": self.api.now(),
                   "data": self.api.screen(sid, sub["limit"], sub["window"])})

    def tick(self):
        """Periodic station_update messages (every frequencySec of each subscription)."""
        now = self.api.now()
        with self.lock:
            due = [(key, s) for key, s in self.subs.items() if now >= s["due"]]
            for key, s in due:
                s["due"] = now + s["freq"] * 1000
        for (topic, sid), s in due:
            with self.api.lock:
                if topic == "screen":
                    self._send_screen(sid, s)
                    continue
                b = self.api.board(sid, "all", s["window"], 500, True)
            self.send({"type": "station_update", "topic": "station", "id": sid, "ts": now, "data": b})
