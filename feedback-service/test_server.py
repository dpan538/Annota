from contextlib import closing
import io
import json
import os
import stat
import sqlite3
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from wsgiref.simple_server import make_server, WSGIRequestHandler
from server import FeedbackApp

class Quiet(WSGIRequestHandler):
    def log_message(self, *_):
        pass

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "feedback.sqlite"
        self.now = 1000000
        self.app = FeedbackApp(self.path, "x" * 32, clock=lambda: self.now, rate_limit=2)
    def tearDown(self):
        self.temp.cleanup()
    def call(self, data, **env):
        body = json.dumps(data).encode()
        status = []
        result = self.app({"PATH_INFO": "/v1/feedback", "REQUEST_METHOD": "POST",
                           "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(body)),
                           "wsgi.input": io.BytesIO(body), "REMOTE_ADDR": "127.0.0.1", **env},
                          lambda s, h: status.append(s))
        return int(status[0].split()[0]), json.loads(b"".join(result))
    def payload(self, n=0):
        return {"body": "阅读器建议\nhello", "contact": "optional@example.org", "requestId": f"00000000-0000-0000-0000-{n:012d}"}
    def test_receipt_idempotency_and_private_contact(self):
        status, a = self.call(self.payload())
        self.assertEqual(status, 201)
        self.assertTrue(a["accepted"])
        self.assertNotIn("contact", a)
        self.assertEqual(self.call(self.payload()), (200, a))
        changed = self.payload(); changed["body"] = "changed"
        self.assertEqual(self.call(changed)[0], 409)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(db.execute("select count(*) from feedback").fetchone()[0], 1)
    def test_validation_and_no_read_endpoint(self):
        for p in ({}, {**self.payload(), "body": " "}, {**self.payload(), "body": "字"*1001},
                  {**self.payload(), "contact": "x"*121}, {**self.payload(), "logs": "not allowed"}):
            self.assertEqual(self.call(p)[0], 400)
        self.assertEqual(self.call({}, REQUEST_METHOD="GET")[0], 405)
        self.assertEqual(self.call({}, PATH_INFO="/feedback")[0], 404)
        self.assertEqual(self.call({}, CONTENT_TYPE="text/plain")[0], 415)
    def test_rate_limit_and_retention(self):
        self.assertEqual(self.call(self.payload(1))[0], 201)
        self.assertEqual(self.call(self.payload(2))[0], 201)
        self.assertEqual(self.call(self.payload(3))[0], 429)
        self.now += 91*86400
        self.assertEqual(self.call(self.payload(4))[0], 201)
        with closing(sqlite3.connect(self.path)) as db:
            self.assertEqual(db.execute("select count(*) from feedback").fetchone()[0], 1)
    def test_storage_permissions(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)
        self.call(self.payload())
        for f in Path(self.temp.name).glob("feedback.sqlite*"):
            self.assertEqual(stat.S_IMODE(f.stat().st_mode) & 0o077, 0)

    def test_actual_http_receipt(self):
        server = make_server("127.0.0.1", 0, self.app, handler_class=Quiet)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            request = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/v1/feedback",
                         json.dumps(self.payload()).encode(), {"Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(response.status, 201)
                self.assertTrue(json.load(response)["receipt"])
        finally:
            server.shutdown(); server.server_close(); thread.join()

if __name__ == "__main__":
    unittest.main()
