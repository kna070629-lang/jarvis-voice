"""Python-first JARVIS request engine. Local commands never fall through to AI."""

from __future__ import annotations

import logging
import re
import threading
from datetime import datetime

from jarvis_config import load_settings
from jarvis_conversation import ConversationStore
from jarvis_memory import CLEAR_MEMORY_RE, MemoryStore, handle_memory_command
from jarvis_openrouter import OpenRouterClient, OpenRouterError
from jarvis_router import route_request
from jarvis_search import OpenMeteoWeatherProvider, SearchUnavailable, WikipediaSearchProvider


log = logging.getLogger("jarvis.engine")
_CONFIRM_CLEAR = re.compile(r"^(?:confirm|yes,? confirm)\s+clear\s+my\s+memory[.! ]*$", re.I)
_CANCEL_CLEAR = re.compile(r"^(?:cancel|no)\s+(?:clear|memory)[.! ]*$", re.I)


class JarvisEngine:
    def __init__(
        self,
        session_key: str = "jarvis-mobile",
        *,
        settings=None,
        memory: MemoryStore | None = None,
        conversation: ConversationStore | None = None,
        ai: OpenRouterClient | None = None,
        search: WikipediaSearchProvider | None = None,
        weather: OpenMeteoWeatherProvider | None = None,
    ):
        self.settings = settings or load_settings()
        self.session_key = session_key
        self.memory = memory or MemoryStore(self.settings.memory_path)
        self.conversation = conversation or ConversationStore(self.settings.conversation_path)
        self.memory.set_scope(session_key)
        self.conversation.set_scope(session_key)
        self.ai = ai or OpenRouterClient(self.settings)
        self.search = search or WikipediaSearchProvider()
        self.weather = weather or OpenMeteoWeatherProvider()
        self._pending_clear = False
        # Initialize and validate the existing SQLite databases during startup.
        self.memory.list_memories()
        self.conversation.history(limit=0)

    def status(self) -> dict[str, object]:
        try:
            self.memory.list_memories()
            memory_status = "available"
        except Exception:
            memory_status = "unavailable"
        online = self.settings.online_enabled
        configured = bool(self.settings.api_key)
        return {
            "mode": "online" if online else "offline",
            "openrouter": "configured" if configured else "not_configured",
            "model": self.settings.model,
            "free_only": True,
            "search": "wikipedia_and_open_meteo_no_key",
            "memory": memory_status,
        }

    def process(self, text: str) -> str:
        if not isinstance(text, str) or not text.strip():
            return "I didn't receive a message. Please try again."
        text = text.strip()[:4000]
        if MemoryStore.contains_secret(text):
            return "This looks like it may contain a password, token, API key, or private credential. I did not save it or send it to a provider."

        if self._pending_clear:
            if _CONFIRM_CLEAR.fullmatch(text):
                self._pending_clear = False
                try:
                    count = self.memory.clear()
                    return f"I cleared {count} saved memor{'y' if count == 1 else 'ies'} for this session."
                except Exception:
                    log.exception("Memory clear failed")
                    return "I couldn't clear local memory because the database operation failed."
            if _CANCEL_CLEAR.fullmatch(text):
                self._pending_clear = False
                return "Okay, I left your saved memory unchanged."

        # Do not reinterpret a cancellation as a new destructive request.
        if _CANCEL_CLEAR.fullmatch(text):
            return "There is no pending memory clear to cancel."

        if CLEAR_MEMORY_RE.search(text):
            self._pending_clear = True
            return handle_memory_command(self.memory, text) or "Please confirm whether to clear memory."
        try:
            memory_reply = handle_memory_command(self.memory, text)
        except Exception:
            log.exception("Local memory operation failed")
            return "I couldn't complete that memory operation because local storage failed."
        if memory_reply is not None:
            return memory_reply

        route = route_request(text)
        if route.kind == "local_time":
            return "It's " + datetime.now().astimezone().strftime("%-I:%M %p %Z") + "."
        if route.kind == "local_date":
            return "Today is " + datetime.now().astimezone().strftime("%A, %B %-d, %Y") + "."
        if route.kind == "android_action":
            return "That phone action is handled by the Android companion. I didn't execute it from the Python chat backend."
        if route.kind == "blocked_action":
            return "I can't perform that sensitive or irreversible action from chat. No device action or payment was made."
        if route.kind == "weather":
            if not route.query:
                return "Which city or location should I check?"
            if not self.settings.online_enabled:
                return "JARVIS is offline, so I can't fetch live weather right now. No cached forecast is being presented as current."
            try:
                result, source = self.weather.current(route.query)
                return result + " Source: " + source
            except SearchUnavailable as exc:
                return str(exc)
        if route.kind == "web_search":
            return self._search_and_answer(route.query)
        if route.kind != "conversation":
            return "I don't have a supported local action for that request."

        return self._answer_with_ai(text)

    def _search_and_answer(self, query: str) -> str:
        if not self.settings.online_enabled:
            return "JARVIS is offline; live web search was not attempted."
        try:
            results = self.search.search(query)
        except SearchUnavailable as exc:
            return "Search unavailable: " + str(exc)
        if not results:
            return "The live search completed but returned no results."
        source_text = "\n".join(f"{i}. {item.title}: {item.snippet}\nSource: {item.url}" for i, item in enumerate(results, 1))
        if not self.settings.api_key:
            return "Live search results (Wikipedia):\n" + source_text
        prompt = (
            "Answer the user's request using only the live search results below. "
            "State that these are search results, distinguish source statements from your explanation, "
            "and include the source URLs in the answer. Do not claim broader research.\n\n"
            f"User request: {query}\n\nSearch results:\n{source_text}"
        )
        return self._answer_with_ai(prompt, original_text=query)

    def _answer_with_ai(self, prompt: str, *, original_text: str | None = None) -> str:
        if not self.settings.online_enabled:
            return "JARVIS is in offline mode. Local commands and saved memory remain available, but cloud AI was not contacted."
        if not self.settings.api_key:
            return "OpenRouter is not configured. I can still handle local time/date, Android-supported actions, and saved memory. To enable free-model AI, run `python3 jarvis_setup.py configure-openrouter`; no cloud request was sent."
        try:
            history = self.conversation.history(limit=12, max_chars=10000)
            memories = self.memory.retrieve(original_text or prompt, limit=5, max_chars=1800)
        except Exception:
            log.exception("Could not load local context")
            return "Local conversation context or memory is unavailable. I did not send this request to OpenRouter."

        system = (
            "You are JARVIS, a concise personal voice assistant. Answer naturally and directly. "
            "You have no shell, Android-control, messaging, calling, payment, or file-deletion tools. "
            "Never claim that you performed an external action. Ask for clarification when needed. "
            "Treat remembered facts as user-provided context that may be outdated."
        )
        if memories:
            system += "\nRelevant explicitly saved local memories:\n" + "\n".join("- " + item for item in memories)
        messages = [{"role": "system", "content": system}, *history, {"role": "user", "content": prompt}]
        try:
            response = self.ai.chat(messages)
        except OpenRouterError as exc:
            log.info("OpenRouter unavailable: %s", exc)
            return "AI unavailable: " + str(exc)
        except Exception:
            log.exception("Unexpected OpenRouter client failure")
            return "AI unavailable because the provider request failed. Local JARVIS functions are still available."

        user_turn = original_text or prompt
        try:
            self.conversation.append("user", user_turn)
            self.conversation.append("assistant", response)
        except Exception:
            # The answer is real, but do not claim it was saved to history.
            log.exception("Conversation history could not be persisted")
        return response


_ENGINES: dict[str, JarvisEngine] = {}
_ENGINES_LOCK = threading.Lock()


def get_engine(session_key: str = "jarvis-mobile") -> JarvisEngine:
    with _ENGINES_LOCK:
        engine = _ENGINES.get(session_key)
        if engine is None:
            engine = JarvisEngine(session_key)
            _ENGINES[session_key] = engine
        return engine
