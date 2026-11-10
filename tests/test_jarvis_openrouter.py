import json
import unittest
from urllib.error import HTTPError

from jarvis_config import JarvisSettings
from jarvis_openrouter import (
    AllFreeModelsExhausted,
    FreeModelUnavailable,
    OpenRouterClient,
    OpenRouterError,
    OpenRouterRateLimited,
)


PRIMARY = "vendor/primary:free"
SECONDARY = "vendor/secondary:free"
TERTIARY = "vendor/tertiary:free"


class Response:
    def __init__(self, data, status=200):
        self.data = json.dumps(data).encode()
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit=-1):
        return self.data


def free_row(model_id, context=8192):
    return {
        "id": model_id,
        "pricing": {"prompt": "0", "completion": "0"},
        "context_length": context,
    }


def catalog(*rows):
    if not rows:
        rows = (free_row(PRIMARY),)
    return {"data": list(rows)}


def chat_reply(model_id):
    return {"choices": [{"message": {"content": "reply from " + model_id}}]}


class RotatingOpener:
    """Mock transport that rate-limits named models and records every request."""

    def __init__(self, rows, rate_limited=(), timeout_models=(), fail_models=None, catalog_error=None):
        self.rows = rows
        self.rate_limited = set(rate_limited)
        self.timeout_models = set(timeout_models)
        self.fail_models = dict(fail_models or {})
        self.catalog_error = catalog_error
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append(request)
        if request.full_url.endswith("/models"):
            if self.catalog_error is not None:
                raise self.catalog_error
            return Response(catalog(*self.rows))
        body = json.loads(request.data)
        model_id = body["model"]
        if model_id in self.timeout_models:
            raise TimeoutError()
        if model_id in self.fail_models:
            raise HTTPError(request.full_url, self.fail_models[model_id], "failed", {}, None)
        if model_id in self.rate_limited:
            raise HTTPError(request.full_url, 429, "rate limited", {}, None)
        return Response(chat_reply(model_id))

    @property
    def chat_models(self):
        return [
            json.loads(request.data)["model"]
            for request in self.requests
            if request.full_url.endswith("/chat/completions")
        ]


class OpenRouterTests(unittest.TestCase):
    def make(self, opener, **overrides):
        values = {"api_key": "key-not-real", "model": PRIMARY}
        values.update(overrides)
        settings = JarvisSettings(**values)
        return OpenRouterClient(settings, opener=opener, clock=lambda: 1)

    def test_primary_model_succeeds_without_rotation(self):
        opener = RotatingOpener([free_row(PRIMARY), free_row(SECONDARY)])
        client = self.make(opener)

        self.assertEqual("reply from " + PRIMARY, client.chat([{"role": "user", "content": "hello"}]))
        self.assertEqual([PRIMARY], opener.chat_models)
        body = json.loads(opener.requests[-1].data)
        self.assertEqual({"allow_fallbacks": False}, body["provider"])
        self.assertEqual("Bearer key-not-real", opener.requests[-1].get_header("Authorization"))

    def test_primary_rate_limit_then_secondary_success(self):
        opener = RotatingOpener(
            [free_row(PRIMARY, context=16384), free_row(SECONDARY, context=32768)],
            rate_limited={PRIMARY},
        )
        client = self.make(opener)

        self.assertEqual("reply from " + SECONDARY, client.chat([{"role": "user", "content": "hello"}]))
        self.assertEqual([PRIMARY, SECONDARY], opener.chat_models)
        for request in opener.requests:
            if request.full_url.endswith("/chat/completions"):
                self.assertEqual({"allow_fallbacks": False}, json.loads(request.data)["provider"])

    def test_multiple_rate_limits_advance_sequentially(self):
        opener = RotatingOpener(
            [
                free_row(PRIMARY, context=32768),
                free_row(SECONDARY, context=16384),
                free_row(TERTIARY, context=8192),
            ],
            rate_limited={PRIMARY, SECONDARY},
        )
        client = self.make(opener)

        self.assertEqual("reply from " + TERTIARY, client.chat([{"role": "user", "content": "hello"}]))
        self.assertEqual([PRIMARY, SECONDARY, TERTIARY], opener.chat_models)

    def test_all_models_exhausted_reports_honestly(self):
        opener = RotatingOpener(
            [free_row(PRIMARY), free_row(SECONDARY), free_row(TERTIARY)],
            rate_limited={PRIMARY, SECONDARY, TERTIARY},
        )
        client = self.make(opener)

        with self.assertRaises(AllFreeModelsExhausted) as caught:
            client.chat([{"role": "user", "content": "hello"}])
        self.assertIsInstance(caught.exception, OpenRouterRateLimited)
        self.assertIn("3 verified zero-price free models", str(caught.exception))
        self.assertIn("does not guarantee live capacity", str(caught.exception))
        self.assertIn("no paid fallback", str(caught.exception))
        self.assertEqual([PRIMARY, SECONDARY, TERTIARY], opener.chat_models)

    def test_rotation_never_uses_paid_models_or_persists_model_changes(self):
        opener = RotatingOpener(
            [
                free_row(PRIMARY),
                {
                    "id": "vendor/paid",
                    "pricing": {"prompt": "0.01", "completion": "0.02"},
                    "context_length": 128000,
                },
                free_row(SECONDARY),
            ],
            rate_limited={PRIMARY},
        )
        client = self.make(opener)
        original_model = client.settings.model

        self.assertEqual("reply from " + SECONDARY, client.chat([{"role": "user", "content": "hello"}]))
        self.assertNotIn("vendor/paid", opener.chat_models)
        self.assertEqual(original_model, client.settings.model)

    def test_rotation_candidates_are_unique_with_primary_first(self):
        opener = RotatingOpener(
            [free_row(SECONDARY), free_row(PRIMARY), free_row(TERTIARY)]
        )
        client = self.make(opener)
        catalog = client.free_models(force_refresh=True)

        candidates = [info.model_id for info in client.rotation_candidates(catalog)]

        self.assertEqual(PRIMARY, candidates[0])
        self.assertEqual(len(candidates), len(set(candidates)))
        self.assertEqual({PRIMARY, SECONDARY, TERTIARY}, set(candidates))

    def test_paid_model_is_rejected_before_chat(self):
        opener = RotatingOpener(
            [{"id": "vendor/paid", "pricing": {"prompt": "0.01", "completion": "0.02"}}]
        )
        client = self.make(opener, model="vendor/paid")

        with self.assertRaises(FreeModelUnavailable):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([], opener.chat_models)

    def test_free_alias_is_rejected_without_catalog_or_chat(self):
        calls = []
        client = OpenRouterClient(
            JarvisSettings(api_key="key-not-real", model="openrouter/free"),
            opener=lambda *_args, **_kwargs: calls.append(1),
        )
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

    def test_timeout_is_not_replayed_or_rotated(self):
        opener = RotatingOpener(
            [free_row(PRIMARY), free_row(SECONDARY)],
            timeout_models={PRIMARY},
        )
        client = self.make(opener)

        with self.assertRaisesRegex(OpenRouterError, "timed out"):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([PRIMARY], opener.chat_models)

    def test_non_429_chat_error_does_not_rotate(self):
        opener = RotatingOpener(
            [free_row(PRIMARY), free_row(SECONDARY)],
            fail_models={PRIMARY: 500},
        )
        client = self.make(opener)

        with self.assertRaisesRegex(OpenRouterError, "HTTP 500"):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([PRIMARY], opener.chat_models)

    def test_nonzero_request_or_image_price_is_not_free(self):
        opener = RotatingOpener([
            free_row(PRIMARY),
            {
                "id": "vendor/request-fee:free",
                "pricing": {"prompt": "0", "completion": "0", "request": "0.001"},
            },
            {
                "id": "vendor/image-fee:free",
                "pricing": {"prompt": "0", "completion": "0", "image": "0.002"},
            },
        ])
        client = self.make(opener)
        catalog = client.free_models(force_refresh=True)

        self.assertIn(PRIMARY, catalog)
        self.assertNotIn("vendor/request-fee:free", catalog)
        self.assertNotIn("vendor/image-fee:free", catalog)

    def test_non_text_free_model_is_excluded_from_rotation(self):
        text_row = free_row(PRIMARY)
        text_row["architecture"] = {"modality": "text+image->text"}
        image_only_row = free_row(SECONDARY)
        image_only_row["architecture"] = {"modality": "image->image"}
        image_input_row = free_row(TERTIARY)
        image_input_row["architecture"] = {"modality": "image->text"}
        image_output_row = free_row("vendor/image-output:free")
        image_output_row["architecture"] = {"modality": "text->image"}
        textual_input_row = free_row("vendor/textual-input:free")
        textual_input_row["architecture"] = {"modality": "textual->text"}
        textual_output_row = free_row("vendor/textual-output:free")
        textual_output_row["architecture"] = {"modality": "text->textual"}
        opener = RotatingOpener([
            text_row,
            image_only_row,
            image_input_row,
            image_output_row,
            textual_input_row,
            textual_output_row,
        ])
        client = self.make(opener)
        catalog = client.free_models(force_refresh=True)

        candidates = [info.model_id for info in client.rotation_candidates(catalog)]

        self.assertEqual([PRIMARY], candidates)

    def test_text_capable_modalities_are_accepted(self):
        text_row = free_row(PRIMARY)
        text_row["architecture"] = {"modality": "text+image->text"}
        text_only_row = free_row(SECONDARY)
        text_only_row["architecture"] = {"modality": "text->text"}
        reversed_row = free_row(TERTIARY)
        reversed_row["architecture"] = {"modality": "image+text->text"}
        unspecified_row = free_row("vendor/unspecified:free")
        opener = RotatingOpener([text_row, text_only_row, reversed_row, unspecified_row])
        client = self.make(opener)
        catalog = client.free_models(force_refresh=True)

        candidates = [info.model_id for info in client.rotation_candidates(catalog)]

        self.assertEqual(PRIMARY, candidates[0])
        self.assertEqual(
            {PRIMARY, SECONDARY, TERTIARY, "vendor/unspecified:free"},
            set(candidates),
        )

    def test_http_error_response_is_closed(self):
        closed = []

        class TrackingHTTPError(HTTPError):
            def close(self):
                closed.append(True)

        def opener(request, timeout):
            if request.full_url.endswith("/models"):
                return Response(catalog(free_row(PRIMARY)))
            raise TrackingHTTPError(request.full_url, 429, "rate limited", {}, None)

        client = OpenRouterClient(
            JarvisSettings(api_key="key-not-real", model=PRIMARY),
            opener=opener,
            clock=lambda: 1,
        )
        with self.assertRaises(AllFreeModelsExhausted):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([True], closed)

    def test_catalog_rate_limit_sends_no_chat_request(self):
        opener = RotatingOpener(
            [free_row(PRIMARY)],
            catalog_error=HTTPError("https://openrouter.ai/api/v1/models", 429, "rate limited", {}, None),
        )
        client = self.make(opener)

        with self.assertRaises(OpenRouterRateLimited):
            client.chat([{"role": "user", "content": "hello"}])
        self.assertEqual([], opener.chat_models)


if __name__ == "__main__":
    unittest.main()
