"""Anonymous feedback -> one public GitHub Issue. Credentials never leave this service."""
import hashlib
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime

REPO = "dpan538/Annota"
API = "https://api.github.com/repos/" + REPO + "/issues"
ISSUE = re.compile(r"https://github\.com/dpan538/Annota/issues/[1-9][0-9]*")
_lock = threading.Lock()


class RelayError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


class GitHubRelay:
    def __init__(self, token=None, *, request=None, clock=time.time):
        self.token = token if token is not None else (os.environ.get("ANNOTA_GITHUB_TOKEN") or os.environ.get("NNOTA_GITHUB_TOKEN", ""))
        self.request = request or self._request
        self.clock = clock

    def _request(self, method, url, data=None):
        req = urllib.request.Request(url, None if data is None else json.dumps(data).encode(), {
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "Annota-feedback/1.0",
            "Cache-Control": "no-cache",
            "X-GitHub-Api-Version": "2022-11-28",
        }, method=method)
        # GitHub API URLs are fixed; never send the credential to a redirected host.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *_args, **_kwargs):
                return None
        try:
            with urllib.request.build_opener(NoRedirect).open(req, timeout=12) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
                if len(raw) > 4 * 1024 * 1024:
                    raise RelayError(502, "github_response_invalid")
                return response.status, json.loads(raw)
        except (urllib.error.URLError, TimeoutError, ValueError, OSError):
            raise RelayError(503, "github_unavailable") from None

    @staticmethod
    def validate(data):
        if not isinstance(data, dict) or set(data) != {"body", "requestId", "version"}:
            raise RelayError(400, "invalid_input")
        body, request_id, version = (data[k] for k in ("body", "requestId", "version"))
        if not all(isinstance(v, str) for v in (body, request_id, version)):
            raise RelayError(400, "invalid_input")
        body = body.strip()
        if (not 1 <= len(body) <= 1000 or not re.fullmatch(r"[a-zA-Z0-9-]{16,80}", request_id)
                or not re.fullmatch(r"[a-zA-Z0-9.+-]{1,40}", version)
                or any(ord(c) < 32 and c not in "\n\r\t" for c in body)):
            raise RelayError(400, "invalid_input")
        return body, request_id, version

    def submit(self, data):
        body, request_id, version = self.validate(data)
        if not self.token:
            raise RelayError(503, "service_unconfigured")
        marker = "<!-- annota-feedback:" + request_id + " -->"
        digest = "<!-- annota-content:" + hashlib.sha256(body.encode()).hexdigest() + " -->"
        with _lock:
            # Read authoritative issues (including closed), without search-index delay.
            recent = 0
            for page in range(1, 6):
                status, issues = self.request("GET", API + f"?state=all&sort=created&direction=desc&per_page=100&page={page}")
                if status != 200 or not isinstance(issues, list):
                    raise RelayError(503, "github_unavailable")
                for issue in issues:
                    if not isinstance(issue, dict) or "pull_request" in issue:
                        continue
                    text = issue.get("body") or ""
                    if marker in text:
                        if digest not in text:
                            raise RelayError(409, "request_conflict")
                        return self.receipt(issue, request_id, marker)
                    if "<!-- annota-feedback:" in text:
                        try:
                            created = datetime.fromisoformat(issue["created_at"].replace("Z", "+00:00")).timestamp()
                            if created >= self.clock() - 3600:
                                recent += 1
                        except (KeyError, ValueError, TypeError):
                            raise RelayError(502, "github_response_invalid") from None
                if len(issues) < 100:
                    break
            # Conservative repository-wide guard, shared through GitHub rather than process RAM.
            if recent >= 20:
                raise RelayError(429, "rate_limited")
            title = "[Feedback] " + body.splitlines()[0][:70]
            text = body + "\n\n---\nAnnota " + version + "\n" + marker + "\n" + digest
            status, issue = self.request("POST", API, {"title": title, "body": text})
            if status != 201:
                raise RelayError(503, "github_unavailable")
            # No blind POST retry after a timeout: retrying the same request first checks GitHub.
            return self.receipt(issue, request_id, marker)

    @staticmethod
    def receipt(issue, request_id, marker):
        if (not isinstance(issue, dict) or "pull_request" in issue
                or not ISSUE.fullmatch(issue.get("html_url", ""))
                or marker not in (issue.get("body") or "")):
            raise RelayError(502, "github_response_invalid")
        return {"accepted": True, "requestId": request_id, "receipt": issue["html_url"]}


class FeedbackApp:
    def __init__(self, relay=None):
        self.relay = relay or GitHubRelay()

    def __call__(self, env, start_response):
        def reply(code, data):
            raw = json.dumps(data, ensure_ascii=False).encode()
            start_response(str(code) + " " + {200:"OK",400:"Bad Request",405:"Method Not Allowed",409:"Conflict",413:"Payload Too Large",415:"Unsupported Media Type",429:"Too Many Requests",502:"Bad Gateway",503:"Service Unavailable"}[code],
                           [("Content-Type", "application/json; charset=utf-8"), ("Content-Length", str(len(raw))),
                            ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")])
            return [raw]
        if env.get("REQUEST_METHOD") == "GET":
            return reply(200, {"service": "annota-feedback", "configured": bool(self.relay.token)})
        if env.get("REQUEST_METHOD") != "POST":
            return reply(405, {"error": "method_not_allowed"})
        if env.get("CONTENT_TYPE", "").split(";")[0].lower() != "application/json":
            return reply(415, {"error": "json_required"})
        try:
            length = int(env.get("CONTENT_LENGTH") or "0")
            if not 0 < length <= 16384:
                return reply(413, {"error": "invalid_size"})
            raw = env["wsgi.input"].read(length)
            if len(raw) != length:
                raise ValueError()
            data = json.loads(raw)
        except (ValueError, KeyError, TypeError, UnicodeError):
            return reply(400, {"error": "invalid_input"})
        try:
            return reply(200, self.relay.submit(data))
        except RelayError as error:
            return reply(error.status, {"error": error.code})
        except Exception:
            # Never log message text, request headers or credentials.
            return reply(503, {"error": "service_unavailable"})
