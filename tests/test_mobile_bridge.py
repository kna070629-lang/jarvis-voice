import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mobile_bridge import create_server, load_or_create_token


class MobileBridgeTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.server = create_server(lambda text: self.calls.append(text) or f"Reply: {text}", token="t" * 43, port=0)
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

    def test_token_is_private_and_stable(self):
        with tempfile.TemporaryDirectory() as folder:
            token_path = Path(folder) / "config" / "mobile.token"
            first = load_or_create_token(token_path)
            second = load_or_create_token(token_path)
            self.assertEqual(first, second)
            self.assertGreaterEqual(len(first), 32)
            self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            self.assertEqual(0o700, token_path.parent.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
