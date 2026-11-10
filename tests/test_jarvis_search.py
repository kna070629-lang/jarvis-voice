import json
import unittest
from urllib.parse import urlparse

from jarvis_search import OpenMeteoWeatherProvider, WikipediaSearchProvider


class Response:
    status = 200

    def __init__(self, data): self.data = json.dumps(data).encode()
    def __enter__(self): return self
    def __exit__(self, *_args): return False
    def read(self, _limit=-1): return self.data


class SearchTests(unittest.TestCase):
    def test_wikipedia_search_returns_live_source_urls_and_escaped_snippets(self):
        provider = WikipediaSearchProvider(opener=lambda *_a, **_kw: Response({
            "query": {"search": [{"title": "JARVIS (assistant)", "snippet": "A <b>sample</b> result"}]}
        }))
        results = provider.search("JARVIS", limit=2)
        self.assertEqual(1, len(results))
        self.assertEqual("A sample result", results[0].snippet)
        self.assertTrue(results[0].url.startswith("https://en.wikipedia.org/wiki/"))

    def test_weather_fetches_geocode_then_current_conditions(self):
        requests = []
        payloads = [
            {"results": [{"name": "Mumbai", "country": "India", "latitude": 19.0, "longitude": 72.0}]},
            {"current": {"temperature_2m": 30, "relative_humidity_2m": 70, "apparent_temperature": 35,
                          "weather_code": 2, "wind_speed_10m": 10}},
        ]

        def opener(request, timeout):
            requests.append(urlparse(request.full_url).netloc)
            return Response(payloads.pop(0))

        text, source = OpenMeteoWeatherProvider(opener=opener).current("Mumbai")
        self.assertIn("Current weather for Mumbai, India", text)
        self.assertIn("partly cloudy", text)
        self.assertEqual(["geocoding-api.open-meteo.com", "api.open-meteo.com"], requests)
        self.assertEqual("https://open-meteo.com/", source)


if __name__ == "__main__":
    unittest.main()
