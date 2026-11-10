import json
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mobile_bridge import create_server, load_or_create_token


class MobileBridgeTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.server = create_server(lambda text: self.calls.append(text) or f"Reply: {text}", token="t" * 43, port=0)
        self.server.status_provider = lambda: {"mode": "offline", "ai": "codex->opencode"}
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.base = f"http://{host}:{port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, path, body=None, token=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base + path, data=data, method="GET" if data is None else "POST")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if token is not None:
            request.add_header("Authorization", f"Bearer {token}")
        return urlopen(request, timeout=3)

    def test_health_is_local_service_metadata(self):
        self.assertEqual("127.0.0.1", self.server.server_address[0])
        response = self.request("/health")
        self.assertEqual(200, response.status)
        self.assertEqual("jarvis-mobile", json.loads(response.read())["service"])
        response = self.request("/health", token="t" * 43)
        self.assertTrue(json.loads(response.read())["authenticated"])
        self.assertEqual("offline", json.loads(self.request("/health", token="t" * 43).read())["runtime"]["mode"])
        self.assertNotIn("runtime", json.loads(self.request("/health").read()))
        with self.assertRaises(HTTPError) as caught:
            self.request("/health", token="wrong")
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()

    def test_chat_requires_auth_and_structured_action(self):
        body = {"action": "chat", "request_id": "request_1234", "text": "hello"}
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", body, "wrong")
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()
        body["action"] = "run_shell"
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", body, "t" * 43)
        self.assertEqual(400, caught.exception.code)
        caught.exception.close()
        self.assertEqual([], self.calls)

    def test_duplicate_request_id_does_not_repeat_backend_call(self):
        body = {"action": "chat", "request_id": "request_5678", "text": "hello"}
        replies = []
        for _ in range(2):
            response = self.request("/v1/command", body, "t" * 43)
            replies.append(json.loads(response.read()))
        self.assertEqual(["hello"], self.calls)
        self.assertEqual("Reply: hello", replies[0]["text"])
        self.assertFalse(replies[0]["duplicate"])
        self.assertTrue(replies[1]["duplicate"])

    def test_reused_request_id_with_different_text_is_rejected(self):
        first = {"action": "chat", "request_id": "request_9999", "text": "first"}
        response = self.request("/v1/command", first, "t" * 43)
        response.read()
        changed = {**first, "text": "different"}
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", changed, "t" * 43)
        self.assertEqual(409, caught.exception.code)
        caught.exception.close()
        self.assertEqual(["first"], self.calls)

    def test_non_ascii_authorization_returns_401_instead_of_crashing(self):
        request = Request(self.base + "/health")
        request.add_header("Authorization", "Bearer caf\u00e9")
        with self.assertRaises(HTTPError) as caught:
            urlopen(request, timeout=3)
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()

        body = {"action": "chat", "request_id": "request_unicode", "text": "hello"}
        request = Request(self.base + "/v1/command", data=json.dumps(body).encode(), method="POST")
        request.add_header("Content-Type", "application/json")
        request.add_header("Authorization", "Bearer caf\u00e9")
        with self.assertRaises(HTTPError) as caught:
            urlopen(request, timeout=3)
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()
        self.assertEqual([], self.calls)

    def test_health_filters_invalid_and_raising_status_provider(self):
        self.server.status_provider = lambda: {
            "mode": "invalid",
            "ai": "bad value with spaces",
            "search": "also bad",
        }
        runtime = json.loads(self.request("/health", token="t" * 43).read())["runtime"]
        self.assertEqual({}, runtime)

        self.server.status_provider = lambda: ["not", "a", "dict"]
        runtime = json.loads(self.request("/health", token="t" * 43).read())["runtime"]
        self.assertEqual({"mode": "unknown"}, runtime)

        def broken_status():
            raise RuntimeError("status failure")

        self.server.status_provider = broken_status
        runtime = json.loads(self.request("/health", token="t" * 43).read())["runtime"]
        self.assertEqual({"mode": "unknown"}, runtime)

    def test_mobile_token_override_must_be_ascii(self):
        with tempfile.TemporaryDirectory() as folder:
            token_path = Path(folder) / "config" / "mobile.token"
            with mock.patch.dict(os.environ, {"JARVIS_MOBILE_TOKEN": "\u00e9" * 32}):
                with self.assertRaises(ValueError):
                    load_or_create_token(token_path)

    def test_health_filters_unapproved_runtime_metadata(self):
        self.server.status_provider = lambda: {
            "mode": "offline",
            "openrouter": "not_configured",
            "free_only": True,
            "model": "example-model",
            "ai": "codex->opencode",
            "api_key": "must-not-leak",
            "unexpected": "must-not-leak",
        }

        response = self.request("/health", token="t" * 43)
        runtime = json.loads(response.read())["runtime"]

        self.assertEqual("offline", runtime["mode"])
        self.assertEqual("codex->opencode", runtime["ai"])
        self.assertNotIn("openrouter", runtime)
        self.assertNotIn("free_only", runtime)
        self.assertNotIn("model", runtime)
        self.assertNotIn("api_key", runtime)
        self.assertNotIn("unexpected", runtime)

    def test_in_progress_request_cannot_be_repeated(self):
        started = threading.Event()
        release = threading.Event()
        first_result = []
        first_errors = []

        def blocking_process(text):
            self.calls.append(text)
            started.set()
            if not release.wait(timeout=3):
                raise TimeoutError("test processing timed out")
            return f"Reply: {text}"

        self.server.process_message = blocking_process
        body = {
            "action": "chat",
            "request_id": "request_inflight",
            "text": "hello",
        }

        def send_first_request():
            try:
                response = self.request("/v1/command", body, "t" * 43)
                first_result.append(json.loads(response.read()))
            except Exception as exc:
                first_errors.append(exc)

        worker = threading.Thread(target=send_first_request)
        worker.start()

        try:
            self.assertTrue(started.wait(timeout=2), "first request did not start")
            with self.assertRaises(HTTPError) as caught:
                self.request("/v1/command", body, "t" * 43)

            self.assertEqual(409, caught.exception.code)
            self.assertEqual(
                "request_in_progress",
                json.loads(caught.exception.read())["error"],
            )
            caught.exception.close()
        finally:
            release.set()
            worker.join(timeout=4)

        self.assertFalse(worker.is_alive(), "first request did not finish")
        self.assertEqual([], first_errors)
        self.assertEqual(1, len(first_result))
        self.assertEqual(["hello"], self.calls)
        self.assertFalse(first_result[0]["duplicate"])

    def test_token_is_private_and_stable(self):
        with tempfile.TemporaryDirectory() as folder:
            token_path = Path(folder) / "config" / "mobile.token"
            first = load_or_create_token(token_path)
            second = load_or_create_token(token_path)
            self.assertEqual(first, second)
            self.assertGreaterEqual(len(first), 32)
            self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            self.assertEqual(0o700, token_path.parent.stat().st_mode & 0o777)


    def test_device_command_returns_structured_action_request(self):
        self.server.action_planner = lambda text: {
            "action_id": "a" * 32,
            "action": "open_app",
            "args": {"name": "youtube"},
            "requires_confirmation": False,
            "sensitive": False,
        } if text.casefold().startswith("open") else None
        body = {"action": "chat", "request_id": "request_action_1", "text": "open YouTube"}

        response = self.request("/v1/command", body, "t" * 43)
        payload = json.loads(response.read())

        self.assertEqual("open_app", payload["action_request"]["action"])
        self.assertEqual("youtube", payload["action_request"]["args"]["name"])
        self.assertEqual("device", payload["provider"])
        self.assertEqual([], self.calls)

    def test_action_result_is_authenticated_and_replay_protected(self):
        action_id = "b" * 32
        self.server.action_planner = lambda text: {
            "action_id": action_id,
            "action": "open_app",
            "args": {"name": "youtube"},
            "requires_confirmation": False,
            "sensitive": False,
        } if text.casefold().startswith("open") else None
        issue = {"action": "chat", "request_id": "issue_request_1", "text": "open YouTube"}
        issued = json.loads(self.request("/v1/command", issue, "t" * 43).read())
        self.assertEqual(action_id, issued["action_request"]["action_id"])

        result = {
            "action": "action_result",
            "request_id": "result_request_1",
            "action_id": action_id,
            "status": "succeeded",
            "message": "YouTube opened.",
        }
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", result, "wrong")
        self.assertEqual(401, caught.exception.code)
        caught.exception.close()

        payload = json.loads(self.request("/v1/command", result, "t" * 43).read())
        self.assertEqual("YouTube opened.", payload["text"])

        replay = {**result, "request_id": "result_request_2"}
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", replay, "t" * 43)
        self.assertEqual(409, caught.exception.code)
        self.assertEqual("action_replayed", json.loads(caught.exception.read())["error"])
        caught.exception.close()
        self.assertEqual([], self.calls)

    def test_action_result_for_an_unissued_action_is_rejected(self):
        result = {
            "action": "action_result",
            "request_id": "result_unissued_1",
            "action_id": "c" * 32,
            "status": "succeeded",
            "message": "Nothing should run.",
        }
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", result, "t" * 43)
        self.assertEqual(409, caught.exception.code)
        self.assertEqual("unknown_action", json.loads(caught.exception.read())["error"])
        caught.exception.close()

    def test_planner_cannot_downgrade_sensitive_action_confirmation(self):
        self.server.action_planner = lambda text: {
            "action_id": "d" * 32,
            "action": "call",
            "args": {"number": "14155550199"},
            "requires_confirmation": False,
            "sensitive": False,
        } if text.casefold().startswith("call") else None
        body = {"action": "chat", "request_id": "request_call_1", "text": "call +1 415 555 0199"}

        payload = json.loads(self.request("/v1/command", body, "t" * 43).read())
        self.assertNotIn("action_request", payload)
        self.assertEqual(["call +1 415 555 0199"], self.calls)

    def test_action_result_rejects_invalid_status_and_identifier(self):
        invalid = {
            "action": "action_result",
            "request_id": "result_request_bad",
            "action_id": "short",
            "status": "done",
            "message": "x",
        }
        with self.assertRaises(HTTPError) as caught:
            self.request("/v1/command", invalid, "t" * 43)
        self.assertEqual(400, caught.exception.code)
        caught.exception.close()
        self.assertEqual([], self.calls)

    def test_invalid_planner_output_is_ignored_for_chat(self):
        self.server.action_planner = lambda text: {"action": "rm_rf", "args": {}}
        body = {"action": "chat", "request_id": "request_bad_plan", "text": "hello"}
        payload = json.loads(self.request("/v1/command", body, "t" * 43).read())
        self.assertEqual("Reply: hello", payload["text"])
        self.assertEqual(["hello"], self.calls)


if __name__ == "__main__":
    unittest.main()
