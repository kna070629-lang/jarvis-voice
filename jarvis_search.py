"""Separate live-search providers using public, no-key APIs."""

from __future__ import annotations

import html
import json
import re
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class SearchResult:
    title: str
    snippet: str
    url: str


class SearchUnavailable(RuntimeError):
    pass


class WikipediaSearchProvider:
    """Live encyclopedic web search through the documented MediaWiki API."""

    endpoint = "https://en.wikipedia.org/w/api.php"

    def __init__(self, *, opener=urlopen, timeout: float = 8.0):
        self._opener = opener
        self.timeout = timeout

    def search(self, query: str, *, limit: int = 5) -> list[SearchResult]:
        params = urlencode({
            "action": "query", "list": "search", "srsearch": query[:300],
            "format": "json", "utf8": "1", "srlimit": str(min(max(limit, 1), 8)),
            "srprop": "snippet",
        })
        request = Request(self.endpoint + "?" + params, headers={
            "Accept": "application/json",
            "User-Agent": "JARVISVoiceAssistant/1.0 (local personal assistant)",
        })
        try:
            with self._opener(request, timeout=self.timeout) as response:
                if response.status != 200:
                    raise SearchUnavailable(f"Wikipedia search returned HTTP {response.status}.")
                payload = json.loads(response.read(1_000_000).decode("utf-8"))
        except SearchUnavailable:
            raise
        except HTTPError as exc:
            try:
                exc.close()
            except Exception:
                pass
            if exc.code == 429:
                raise SearchUnavailable("Live web search is rate limited; try again later.") from None
            raise SearchUnavailable(f"Live web search returned HTTP {exc.code}.") from None
        except Exception:
            raise SearchUnavailable("Live web search is unavailable right now.") from None
        rows = payload.get("query", {}).get("search", []) if isinstance(payload, dict) else []
        results = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            title = str(row.get("title", "")).strip()
            if not title:
                continue
            snippet = re.sub(r"<[^>]+>", "", str(row.get("snippet", "")))
            snippet = html.unescape(snippet).strip()
            results.append(SearchResult(
                title=title,
                snippet=snippet[:600],
                url="https://en.wikipedia.org/wiki/" + quote(title.replace(" ", "_"), safe="()'!"),
            ))
        return results


class OpenMeteoWeatherProvider:
    """Current weather and short forecast; no key or device location permission."""

    geocoding_endpoint = "https://geocoding-api.open-meteo.com/v1/search"
    forecast_endpoint = "https://api.open-meteo.com/v1/forecast"

    def __init__(self, *, opener=urlopen, timeout: float = 8.0):
        self._opener = opener
        self.timeout = timeout

    def _get_json(self, url: str) -> dict:
        request = Request(url, headers={"Accept": "application/json", "User-Agent": "JARVISVoiceAssistant/1.0"})
        try:
            with self._opener(request, timeout=self.timeout) as response:
                if response.status != 200:
                    raise SearchUnavailable(f"Weather service returned HTTP {response.status}.")
                payload = json.loads(response.read(1_000_000).decode("utf-8"))
        except SearchUnavailable:
            raise
        except HTTPError as exc:
            try:
                exc.close()
            except Exception:
                pass
            if exc.code == 429:
                raise SearchUnavailable("Weather service is rate limited; try again later.") from None
            raise SearchUnavailable(f"Weather service returned HTTP {exc.code}.") from None
        except Exception:
            raise SearchUnavailable("Live weather is unavailable right now.") from None
        if not isinstance(payload, dict):
            raise SearchUnavailable("Weather service returned an invalid response.")
        return payload

    def current(self, location: str) -> tuple[str, str]:
        query = urlencode({"name": location[:100], "count": 1, "language": "en", "format": "json"})
        location_data = self._get_json(self.geocoding_endpoint + "?" + query)
        places = location_data.get("results")
        if not places:
            raise SearchUnavailable(f"I could not find a location named {location}.")
        place = places[0]
        weather_query = urlencode({
            "latitude": place["latitude"], "longitude": place["longitude"],
            "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m",
            "timezone": "auto",
        })
        weather = self._get_json(self.forecast_endpoint + "?" + weather_query)
        current = weather.get("current")
        if not isinstance(current, dict):
            raise SearchUnavailable("Weather service did not return current conditions.")
        place_name = ", ".join(str(x) for x in (place.get("name"), place.get("admin1"), place.get("country") or place.get("country_code")) if x)
        temp = current.get("temperature_2m")
        feels = current.get("apparent_temperature")
        humidity = current.get("relative_humidity_2m")
        wind = current.get("wind_speed_10m")
        code = current.get("weather_code")
        condition = _weather_description(code)
        response = f"Current weather for {place_name}: {condition}, {temp}°C"
        if feels is not None: response += f", feels like {feels}°C"
        if humidity is not None: response += f", humidity {humidity}%"
        if wind is not None: response += f", wind {wind} km/h"
        response += "."
        url = "https://open-meteo.com/"
        return response, url


def _weather_description(code) -> str:
    try:
        value = int(code)
    except (TypeError, ValueError):
        return "conditions not provided"
    if value == 0: return "clear sky"
    if value in (1, 2, 3): return "partly cloudy"
    if value in (45, 48): return "fog"
    if value in (51, 53, 55, 56, 57): return "drizzle"
    if value in (61, 63, 65, 66, 67): return "rain"
    if value in (71, 73, 75, 77): return "snow"
    if value in (80, 81, 82): return "rain showers"
    if value in (85, 86): return "snow showers"
    if value in (95, 96, 99): return "thunderstorm"
    return "conditions not provided"
