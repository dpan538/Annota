import io
import json
import threading
import unittest
from datetime import datetime, timezone
from wsgiref.simple_server import make_server, WSGIRequestHandler
import urllib.request
from github_relay import FeedbackApp, GitHubRelay, RelayError, API


class GitHub:
    def __init__(self):
        self.issues = []
        self.posts = 0
        self.fail_after_write = False
        self.invalid_receipt = False

    def __call__(self, method, url, data=None):
        if method == "GET":
            return 200, self.issues
        assert method == "POST" and url == API
        self.posts += 1
        issue = {"html_url": "https://github.com/dpan538/Annota/issues/2",
                 "body": data["body"], "created_at": datetime.now(timezone.utc).isoformat()}
        self.issues.append(issue)
        if self.fail_after_write:
            self.fail_after_write = False
            raise RelayError(503, "github_unavailable")
        if self.invalid_receipt:
            return 201, {**issue, "html_url": "https://other.invalid/2"}
        return 201, issue


class Tests(unittest.TestCase):
    def setUp(self):
        self.github = GitHub()
        self.app = FeedbackApp(GitHubRelay("server-secret", request=self.github))
        self.payload = {"body": "测试反馈\nReading feedback", "requestId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "version": "1.0.0"}

    def call(self, payload=None, **env):
        raw = json.dumps(payload if payload is not None else self.payload).encode()
        status = []
        response = self.app({"REQUEST_METHOD": "POST", "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)),
                             "wsgi.input": io.BytesIO(raw), **env}, lambda s, h: status.append(s))
        return int(status[0].split()[0]), json.loads(b"".join(response))

    def test_automatic_submit_returns_real_issue_and_same_request_reuses_it(self):
        code, result = self.call()
        self.assertEqual(code, 200)
        self.assertEqual(result, {"accepted": True, "requestId": self.payload["requestId"], "receipt": "https://github.com/dpan538/Annota/issues/2"})
        self.assertEqual(self.call(), (code, result))
        self.assertEqual(self.github.posts, 1)
        self.assertNotIn("server-secret", self.github.issues[0]["body"])

    def test_timeout_after_github_write_keeps_identity_for_recovery(self):
        self.github.fail_after_write = True
        self.assertEqual(self.call()[0], 503)
        self.assertTrue(self.call()[1]["accepted"])
        self.assertEqual(self.github.posts, 1)

    def test_success_requires_valid_github_receipt(self):
        self.github.invalid_receipt = True
        self.assertEqual(self.call(), (502, {"error": "github_response_invalid"}))

    def test_conflicting_request_cannot_acknowledge_other_content(self):
        self.call()
        self.assertEqual(self.call({**self.payload, "body": "different"})[0], 409)

    def test_missing_credentials_or_github_failure_never_accepts(self):
        self.app = FeedbackApp(GitHubRelay(""))
        self.assertEqual(self.call(), (503, {"error": "service_unconfigured"}))
        self.app = FeedbackApp(GitHubRelay("secret", request=lambda *_: (403, {})))
        self.assertEqual(self.call()[0], 503)

    def test_validation_and_body_limit(self):
        for payload in ({}, {**self.payload, "body": " "}, {**self.payload, "body": "字"*1001},
                        {**self.payload, "contact": "private"}, {**self.payload, "requestId": "bad"}):
            self.assertEqual(self.call(payload)[0], 400)
        self.assertEqual(self.call(CONTENT_LENGTH="20000")[0], 413)
        self.assertEqual(self.call(CONTENT_TYPE="text/plain")[0], 415)
        self.assertEqual(self.call(REQUEST_METHOD="DELETE")[0], 405)

    def test_repository_hourly_guard(self):
        self.call()
        self.github.issues = [self.github.issues[0]] * 20
        self.assertEqual(self.call({**self.payload, "requestId": "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee"})[0], 429)

    def test_real_http_request_and_automatic_response(self):
        class Quiet(WSGIRequestHandler):
            def log_message(self, *_): pass
        server = make_server("127.0.0.1", 0, self.app, handler_class=Quiet)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            request = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/v1/feedback", json.dumps(self.payload).encode(), {"Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response:
                self.assertTrue(json.load(response)["accepted"])
            self.assertEqual(self.github.posts, 1)
        finally:
            server.shutdown();server.server_close();thread.join()


if __name__ == "__main__": unittest.main()
