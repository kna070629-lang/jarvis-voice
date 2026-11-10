import json
import unittest
from urllib.error import HTTPError

from jarvis_config import JarvisSettings
from jarvis_openrouter import FreeModelUnavailable, OpenRouterClient, OpenRouterError, OpenRouterRateLimited


FREE_ID = "vendor/model:free"


class Response:
    status = 200

    def __init__(self, data):
        self.data = json.dumps(data).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit=-1):
        return self.data


def catalog(rows=None):
    return {"data": rows or [{"id": FREE_ID, "pricing": {"prompt": "0", "completion": "0"}, "context_length": 8192}]}


class OpenRouterTests(unittest.TestCase):
    def make(self, handler, **overrides):
        settings = JarvisSettings(api_key="key-not-real", model=FREE_ID, **overrides)
        return OpenRouterClient(settings, opener=handler, clock=lambda: 1)

    def test_free_model_is_live_verified_and_provider_fallback_is_disabled(self):
        requests = []

        def opener(request, timeout):
            requests.append((request, timeout))
            if request.full_url.endswith("/models"):
                return Response(catalog())
            return Response({"choices": [{"message": {"content": "real mocked response"}}]})

        client = self.make(opener)
        self.assertEqual("real mocked response", client.chat([{"role": "user", "content": "hello"}]))
        body = json.loads(requests[-1][0].data)
        self.assertEqual(FREE_ID, body["model"])
        self.assertEqual({"allow_fallbacks": False}, body["provider"])
        self.assertEqual("Bearer key-not-real", requests[-1][0].get_header("Authorization"))

    def test_paid_model_is_rejected_before_chat(self):
        requests = []

        def opener(request, timeout):
            requests.append(request.full_url)
            return Response(catalog([{"id": "vendor/paid", "pricing": {"prompt": "0.01", "completion": "0.02"}}]))

        client = self.make(opener)
        with self.assertRaises(FreeModelUnavailable):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual(["https://openrouter.ai/api/v1/models"], requests)

    def test_free_alias_is_rejected_without_catalog_or_chat(self):
        calls = []
        client = OpenRouterClient(JarvisSettings(api_key="key-not-real", model="openrouter/free"),
                                  opener=lambda *_args, **_kwargs: calls.append(1))
        with self.assertRaises(FreeModelUnavailable):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([], calls)

    def test_offline_and_missing_key_make_no_network_request(self):
        for settings in (
            JarvisSettings(api_key="key-not-real", online_enabled=False),
            JarvisSettings(api_key=""),
        ):
            calls = []
            client = OpenRouterClient(settings, opener=lambda *_a, **_kw: calls.append(1))
            with self.assertRaises(OpenRouterError):
                client.chat([{"role": "user", "content": "hello"}])
            self.assertEqual([], calls)

    def test_timeout_is_honest_and_not_retried(self):
        calls = []

        def opener(request, timeout):
            calls.append(request.full_url)
            if request.full_url.endswith("/models"):
                return Response(catalog())
            raise TimeoutError()

        client = self.make(opener)
        with self.assertRaisesRegex(OpenRouterError, "timed out"):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual(2, len(calls))

    def test_rate_limit_does_not_fallback(self):
        calls = []

        def opener(request, timeout):
            calls.append(request.full_url)
            if request.full_url.endswith("/models"):
                return Response(catalog())
            raise HTTPError(request.full_url, 429, "rate limited", {}, None)

        client = self.make(opener)
        with self.assertRaises(OpenRouterRateLimited):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
