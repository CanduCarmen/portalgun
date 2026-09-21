#!/usr/bin/env python3
"""
Relay для мода Portal Gun: пересылает порталы между игроками. Только стандартная библиотека Python 3.8+.

Запуск:   python3 portalgun_relay.py [--port 8765] [--token СЕКРЕТ]
В моде:   вкладка "Сеть" -> адрес релея http://<ip-этого-компьютера>:8765 (и тот же токен, если задан).

Что хранится: только в памяти и только пока игрок онлайн (запись живёт 15 с без обновлений) —
uuid, ник, адрес сервера, измерение и до 4 порталов (координаты + куда ведут). Ничего не пишется на диск.
Игрок видит порталы только тех, кто находится на том же сервере и в том же измерении.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TTL = 15.0            # сек без обновлений -> игрок пропадает
MIN_INTERVAL = 0.3    # не чаще ~3 запросов/с от одного id
MAX_BODY = 32 * 1024
MAX_PORTALS = 4

players = {}          # id -> dict
lock = threading.Lock()
TOKEN = ""


def s(v, n):
    return str(v)[:n] if v is not None else ""


def f(v):
    x = float(v)
    if x != x or x in (float("inf"), float("-inf")) or abs(x) > 3.0e7:
        raise ValueError("bad number")
    return x


def clean_portal(p):
    kind = s(p.get("kind"), 10)
    if kind not in ("CURRENT", "SERVER"):
        raise ValueError("kind")
    out = {
        "pid": s(p.get("pid"), 64), "x": f(p["x"]), "y": f(p["y"]), "z": f(p["z"]), "yaw": f(p["yaw"]),
        "age": max(0, min(int(p.get("age", 0)), 100000)),
        "kind": kind, "value": s(p.get("value"), 128), "label": s(p.get("label"), 64),
        "hasCoord": bool(p.get("hasCoord")), "cx": 0.0, "cy": 0.0, "cz": 0.0, "cname": s(p.get("cname"), 64),
    }
    if out["hasCoord"]:
        out["cx"], out["cy"], out["cz"] = f(p["cx"]), f(p["cy"]), f(p["cz"])
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "PortalGunRelay/1.0"

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            with lock:
                n = len(players)
            self._send(200, {"ok": True, "players": n})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/sync":
            return self._send(404, {"error": "not found"})
        if TOKEN and self.headers.get("X-Token", "") != TOKEN:
            return self._send(403, {"error": "bad token"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n <= 0 or n > MAX_BODY:
                return self._send(413, {"error": "bad size"})
            d = json.loads(self.rfile.read(n))
            pid = s(d["id"], 64)
            if not pid:
                raise ValueError("id")
            me = {
                "name": s(d.get("name"), 32), "place": s(d.get("place"), 128).lower(), "dim": s(d.get("dim"), 128),
                "portals": [clean_portal(p) for p in (d.get("portals") or [])[:MAX_PORTALS]],
            }
        except Exception:
            return self._send(400, {"error": "bad request"})

        now = time.time()
        with lock:
            for k in [k for k, v in players.items() if now - v["ts"] > TTL]:
                del players[k]
            old = players.get(pid)
            if old and now - old["ts"] < MIN_INTERVAL:
                return self._send(429, {"error": "too fast"})
            me["ts"] = now
            players[pid] = me
            result, count = [], 0
            for oid, o in players.items():
                if oid == pid or o["place"] != me["place"] or o["dim"] != me["dim"]:
                    continue
                count += 1
                for p in o["portals"]:
                    q = dict(p)
                    q["owner"], q["ownerName"] = oid, o["name"]
                    result.append(q)
        self._send(200, {"portals": result, "players": count})

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", default="")
    a = ap.parse_args()
    TOKEN = a.token
    print(f"Portal Gun relay: http://{a.host}:{a.port}  (токен: {'да' if TOKEN else 'нет'})")
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
