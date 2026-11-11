"""Python-first JARVIS request engine. Local commands never fall through to AI."""

from __future__ import annotations

import logging
import re
import threading
from datetime import datetime

from ai_provider import AIReply, AllProvidersFailed, FallbackAIProvider, ProviderError, build_default_provider
from jarvis_brain import build_action_system, extract_action, strip_action_blocks
from jarvis_config import load_settings
from jarvis_conversation import ConversationStore
from jarvis_memory import CLEAR_MEMORY_RE, MemoryStore, handle_memory_command
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
        ai: FallbackAIProvider | object | None = None,
        search: WikipediaSearchProvider | None = None,
        weather: OpenMeteoWeatherProvider | None = None,
    ):
        self.settings = settings or load_settings()
        self.session_key = session_key
        self.memory = memory or MemoryStore(self.settings.memory_path)
        self.conversation = conversation or ConversationStore(self.settings.conversation_path)
        self.memory.set_scope(session_key)
        self.conversation.set_scope(session_key)
        self.ai = ai or build_default_provider(self.settings)
        self.last_provider = ""
        self.search = search or WikipediaSearchProvider()
        self.weather = weather or OpenMeteoWeatherProvider()
        self._pending_clear = False
        self._process_lock = threading.Lock()
        # Initialize and validate the existing SQLite databases during startup.
        self.memory.list_memories()
        self.conversation.history(limit=0)

    def status(self) -> dict[str, object]:
        try:
            self.memory.list_memories()
            memory_status = "available"
        except Exception:
            memory_status = "unavailable"
        online = self.settings.online_enabled and self.settings.ai_enabled
        provider_names = getattr(self.ai, "names", None)
        provider = "->".join(provider_names) if provider_names else getattr(self.ai, "name", "custom")
        return {
            "mode": "online" if online else "offline",
            "ai": provider if online else "disabled",
            "ai_primary": self.settings.ai_primary,
            "ai_fallback": self.settings.ai_fallback,
            "last_provider": self.last_provider or "none",
            "search": "wikipedia_and_open_meteo_no_key",
            "memory": memory_status,
        }

    def process(self, text: str, device: dict | None = None, on_delta=None) -> str | dict:
        # The bridge is threaded and the TUI voice loop shares this engine, so
        # serialize turns per engine to keep pending-confirmation state coherent.
        with self._process_lock:
            return self._process(text, device, on_delta)

    def _process(self, text: str, device: dict | None = None, on_delta=None) -> str | dict:
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
            # The bridge returns a structured action request for the Android
            # companion before this method is called. Reaching this branch means
            # a plain-text client (CLI/TUI) asked for a device action.
            if device and device.get("apps"):
                # A paired companion is present, so let the model plan the
                # action instead of refusing.
                return self._answer_with_ai(text, device=device, on_delta=on_delta)
            return "This device action can only be executed by the paired Android companion. Nothing was executed here."
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

        return self._answer_with_ai(text, device=device, on_delta=on_delta)

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
        if not self.settings.ai_enabled:
            return "Live search results (Wikipedia):\n" + source_text
        prompt = (
            "Answer the user's request using only the live search results below. "
            "State that these are search results, distinguish source statements from your explanation, "
            "and include the source URLs in the answer. Do not claim broader research.\n\n"
            f"User request: {query}\n\nSearch results:\n{source_text}"
        )
        return self._answer_with_ai(prompt, original_text=query)

    def _answer_with_ai(
        self,
        prompt: str,
        *,
        original_text: str | None = None,
        device: dict | None = None,
        on_delta=None,
    ) -> str | dict:
        if not self.settings.online_enabled:
            return "JARVIS is in offline mode. Local commands and saved memory remain available, but the AI provider was not contacted."
        if not self.settings.ai_enabled:
            return "AI providers are disabled in JARVIS settings. Local commands, search, and saved memory remain available."
        try:
            history = self.conversation.history(limit=12, max_chars=10000)
            memories = self.memory.retrieve(original_text or prompt, limit=5, max_chars=1800)
        except Exception:
            log.exception("Could not load local context")
            return "Local conversation context or memory is unavailable. This request was not sent to an AI provider."

        apps = None
        if device:
            apps = device.get("apps") if isinstance(device, dict) else None

        if apps:
            # The companion can execute device actions, so the model is taught
            # the device vocabulary and may answer with a structured action.
            system = build_action_system(apps)
        else:
            system = (
                "You are JARVIS, a composed and sophisticated British personal assistant. "
                "You are calm, confident and quietly witty. "
                "Speak in plain natural English that sounds like a person, not a readout. "
                "Keep a reply to one or two sentences unless the user asks for detail. "
                "Address the user as sir now and then, not in every reply. "
                "A little dry humour is welcome, but never at the expense of being useful. "
                "Do not repeat yourself and do not announce what you are about to do before you do it. "
                "You have no shell, Android-control, messaging, calling, payment, or file-deletion tools. "
                "Never claim that you performed an external action. "
                "Ask for clarification when the request is ambiguous. "
                "Treat remembered facts as user-provided context that may be outdated."
            )
        if memories:
            system += "\nRelevant explicitly saved local memories:\n" + "\n".join("- " + item for item in memories)

        try:
            response, provider = self._ask_provider(prompt, system, history, on_delta=on_delta)
        except AllProvidersFailed as exc:
            log.info("No AI provider answered: %s", exc)
            return "No AI provider could answer. " + str(exc)
        except ProviderError as exc:
            log.info("AI provider failed: %s", exc)
            return "AI provider error. " + str(exc)
        except Exception:
            log.exception("Unexpected AI provider failure")
            return "The AI provider failed unexpectedly. Local JARVIS functions are still available."

        self.last_provider = provider

        if apps:
            action = extract_action(response, apps)
            if action is not None:
                return action
            spoken = strip_action_blocks(response)
            if spoken.strip():
                response = spoken.strip()

        user_turn = original_text or prompt
        try:
            self.conversation.append("user", user_turn)
            self.conversation.append("assistant", response)
        except Exception:
            # The answer is real, but do not claim it was saved to history.
            log.exception("Conversation history could not be persisted")
        return response

    def _ask_provider(
        self,
        prompt: str,
        system: str,
        history: list[dict[str, str]],
        on_delta=None,
    ) -> tuple[str, str]:
        """Ask the configured provider while supporting the legacy test double."""
        ask = getattr(self.ai, "ask", None)
        if callable(ask):
            context_lines = [system]
            for turn in history:
                role = str(turn.get("role", "user"))
                content = str(turn.get("content", ""))
                if content:
                    context_lines.append(f"{role}: {content}")
            context = "\n".join(context_lines)
            if on_delta is not None:
                ask_stream = getattr(self.ai, "ask_stream", None)
                if callable(ask_stream):
                    streamed = ask_stream(prompt, context, on_delta)
                    if isinstance(streamed, str) and streamed.strip():
                        return streamed.strip(), getattr(self.ai, "name", "custom")
            reply = ask(prompt, context)
            if isinstance(reply, AIReply):
                return reply.text, reply.provider
            if isinstance(reply, str) and reply.strip():
                return reply.strip(), getattr(self.ai, "name", "custom")
            raise ProviderError("ai", "returned an empty response")

        # Test doubles and older integrations expose chat(messages) only.
        chat = getattr(self.ai, "chat", None)
        if callable(chat):
            messages = [{"role": "system", "content": system}, *history, {"role": "user", "content": prompt}]
            response = chat(messages)
            if not isinstance(response, str) or not response.strip():
                raise ProviderError("ai", "returned an empty response")
            return response.strip(), getattr(self.ai, "name", "custom")

        raise ProviderError("ai", "no provider interface is available")


_ENGINES: dict[str, JarvisEngine] = {}
_ENGINES_LOCK = threading.Lock()


def get_engine(session_key: str = "jarvis-mobile") -> JarvisEngine:
    with _ENGINES_LOCK:
        engine = _ENGINES.get(session_key)
        if engine is None:
            engine = JarvisEngine(session_key)
            _ENGINES[session_key] = engine
        return engine
