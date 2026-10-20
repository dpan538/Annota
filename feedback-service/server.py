"""Minimal anonymous Annota feedback receiver. Deploy behind HTTPS; no public reads."""
from contextlib import closing
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
from pathlib import Path


class FeedbackApp:
    def __init__(self, database, secret, *, clock=time.time, retention_days=90, rate_limit=8):
        if len(secret) < 32:
            raise ValueError("FEEDBACK_SECRET must have at least 32 characters")
        self.path = str(database)
        self.secret = secret.encode()
        self.clock = clock
        if not 1 <= retention_days <= 365:
            raise ValueError("retention_days must be between 1 and 365")
        self.retention = retention_days * 86400
        self.rate_limit = rate_limit
        Path(self.path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(fd)
        except FileExistsError:
            os.chmod(self.path, 0o600)
        with closing(self.connect()) as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS feedback(
                    request_id TEXT PRIMARY KEY, receipt TEXT UNIQUE NOT NULL,
                    digest TEXT NOT NULL, body TEXT NOT NULL, contact TEXT NOT NULL,
                    created INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS rate(key TEXT NOT NULL, hour INTEGER NOT NULL,
                    count INTEGER NOT NULL, PRIMARY KEY(key,hour));
            """)
        os.chmod(self.path, 0o600)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=FULL")
        return db

    def __call__(self, env, start_response):
        def reply(code, payload, extra=()):
            data = json.dumps(payload, ensure_ascii=False).encode()
            start_response(code, [("Content-Type", "application/json; charset=utf-8"),
                                  ("Content-Length", str(len(data))),
                                  ("Cache-Control", "no-store"),
                                  ("X-Content-Type-Options", "nosniff"), *extra])
            return [data]

        if env.get("PATH_INFO") == "/healthz" and env.get("REQUEST_METHOD") == "GET":
            return reply("200 OK", {"ok": True})
        if env.get("PATH_INFO") != "/v1/feedback":
            return reply("404 Not Found", {"error": "not_found"})
        if env.get("REQUEST_METHOD") != "POST":
            return reply("405 Method Not Allowed", {"error": "method_not_allowed"}, [("Allow", "POST")])
        if not env.get("CONTENT_TYPE", "").lower().startswith("application/json"):
            return reply("415 Unsupported Media Type", {"error": "json_required"})
        try:
            length = int(env.get("CONTENT_LENGTH") or "0")
            if not 0 < length <= 16384:
                return reply("413 Payload Too Large", {"error": "invalid_size"})
            raw = env["wsgi.input"].read(length)
            if len(raw) != length:
                raise ValueError("incomplete body")
            data = json.loads(raw)
            if not isinstance(data, dict) or set(data) - {"body", "contact", "requestId"}:
                raise ValueError("unexpected fields")
            body, contact, request_id = data["body"], data.get("contact", ""), data["requestId"]
            if not all(isinstance(x, str) for x in (body, contact, request_id)):
                raise ValueError("fields must be text")
            body, contact = body.strip(), contact.strip()
            if not 1 <= len(body) <= 1000 or len(contact) > 120:
                raise ValueError("field length")
            if not re.fullmatch(r"[a-zA-Z0-9-]{16,80}", request_id):
                raise ValueError("request id")
            if any(ord(c) < 32 and c not in "\r\n\t" for c in body + contact):
                raise ValueError("control character")
        except (ValueError, KeyError, TypeError, UnicodeError):
            return reply("400 Bad Request", {"error": "invalid_input"})
        now = int(self.clock())
        # The transport peer is used; forwarded headers are deliberately untrusted.
        peer = env.get("REMOTE_ADDR", "unknown")
        key = hmac.new(self.secret, peer.encode(), hashlib.sha256).hexdigest()
        digest = hashlib.sha256(json.dumps([body, contact], ensure_ascii=False).encode()).hexdigest()
        try:
            with closing(self.connect()) as db:
                db.execute("BEGIN IMMEDIATE")
                db.execute("DELETE FROM feedback WHERE created < ?", (now - self.retention,))
                db.execute("DELETE FROM rate WHERE hour < ?", (now // 3600 - 2,))
                old = db.execute("SELECT receipt,digest FROM feedback WHERE request_id=?", (request_id,)).fetchone()
                if old:
                    db.commit()
                    if not hmac.compare_digest(old[1], digest):
                        return reply("409 Conflict", {"error": "idempotency_conflict"})
                    return reply("200 OK", {"accepted": True, "receipt": old[0]})
                count = db.execute("SELECT count FROM rate WHERE key=? AND hour=?", (key, now // 3600)).fetchone()
                if count and count[0] >= self.rate_limit:
                    db.rollback()
                    return reply("429 Too Many Requests", {"error": "rate_limited"}, [("Retry-After", "3600")])
                db.execute("INSERT INTO rate(key,hour,count) VALUES(?,?,1) ON CONFLICT(key,hour) DO UPDATE SET count=count+1", (key, now // 3600))
                receipt = secrets.token_urlsafe(24)
                db.execute("INSERT INTO feedback VALUES(?,?,?,?,?,?)", (request_id, receipt, digest, body, contact, now))
                db.commit()
            return reply("201 Created", {"accepted": True, "receipt": receipt})
        except sqlite3.Error:
            return reply("503 Service Unavailable", {"error": "storage_unavailable"})


def create_app():
    return FeedbackApp(os.environ.get("FEEDBACK_DB", "/data/feedback.sqlite"),
                       os.environ["FEEDBACK_SECRET"],
                       retention_days=int(os.environ.get("FEEDBACK_RETENTION_DAYS", "90")))
