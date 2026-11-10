"""jarvis_agents.py — The three agents behind one spoken reply.

Thinker   produces the answer, ideally as a stream.
Searcher  gathers facts from the live web when the question needs them.
Speaker   turns the answer into speakable sentences so the voice can start
          talking before the thinker has finished writing.

The point of splitting them is latency. The speaker only needs the first
complete sentence to begin talking, so the gap between the request and the
first audible word shrinks to roughly one sentence of generation time.
"""

from __future__ import annotations

import re
import threading
from typing import Callable, Iterable


# A sentence ends in a full stop, question mark or exclamation mark, optionally
# followed by a closing quote or bracket.
_SENTENCE_END = re.compile(r"(?<=[.!?])[\"')\]]*\s+")
_MIN_SPEECH_CHUNK = 2
_MAX_SPEECH_CHUNK = 400

# Titles and initials that end in a full stop without ending a sentence.
_ABBREVIATIONS = {
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
    "e.g", "i.e", "no", "fig", "approx", "inc", "ltd", "co",
}


def _ends_a_sentence(text: str) -> bool:
    """True when the text ends on punctuation that closes a sentence.

    The stream often delivers the last sentence without trailing whitespace,
    so the end of the buffer has to count too. Abbreviations such as "Dr." are
    excluded so the voice does not break in the middle of a name.
    """
    stripped = text.rstrip()
    if not stripped:
        return False
    if stripped[-1] not in ".!?":
        return False
    tail = stripped.rstrip("\"')]").rstrip()
    if not tail:
        return False
    word = re.split(r"[\s(]", tail.rsplit(" ", 1)[-1])[-1].lower() if " " in tail else tail.lower()
    if word in _ABBREVIATIONS:
        return False
    if len(word) == 1 and word.isalpha():
        return False
    return True


class Speaker:
    """Splits a growing text into sentences that are safe to say out loud.

    A chunk is only released when it ends on real sentence punctuation, so the
    voice never says half a word. If the thinker writes a very long sentence
    the chunk is cut at a comma instead of being held back indefinitely.
    """

    def __init__(self, max_chunk: int = _MAX_SPEECH_CHUNK):
        self.max_chunk = max_chunk
        self._buffer = ""
        self._pending = ""

    def reset(self) -> None:
        self._buffer = ""
        self._pending = ""

    def feed(self, text: str) -> list[str]:
        """Add new text and return any sentences that are now complete."""
        if not text:
            return []
        chunks: list[str] = []
        # A sentence that ended at the end of the previous chunk is only
        # released once the next chunk proves it really was a boundary.
        if self._pending:
            if text[:1].isspace():
                chunks.append(self._pending)
            else:
                # Not a boundary after all, so put it back together.
                text = self._pending + text
            self._pending = ""

        self._buffer += text
        while True:
            match = _SENTENCE_END.search(self._buffer)
            if not match:
                break
            candidate = self._buffer[: match.end()].strip()
            self._buffer = self._buffer[match.end():]
            if candidate:
                chunks.extend(self._split_long(candidate))
        if len(self._buffer) > self.max_chunk:
            cut = self._buffer.rfind(",", 0, self.max_chunk)
            if cut < self.max_chunk // 2:
                cut = self.max_chunk
            else:
                cut += 1
            head = self._buffer[:cut].strip()
            self._buffer = self._buffer[cut:]
            if head:
                chunks.extend(self._split_long(head))
        if self._buffer and _ends_a_sentence(self._buffer):
            self._pending = self._buffer.strip()
            self._buffer = ""
        return chunks

    def flush(self) -> list[str]:
        """Return whatever is left when the thinker has finished."""
        rest = (self._pending + " " + self._buffer).strip() if self._pending else self._buffer.strip()
        self._pending = ""
        self._buffer = ""
        if not rest:
            return []
        return self._split_long(rest)

    def _split_long(self, text: str) -> list[str]:
        if len(text) <= self.max_chunk:
            return [text]
        parts: list[str] = []
        remaining = text
        while len(remaining) > self.max_chunk:
            cut = remaining.rfind(" ", 0, self.max_chunk)
            if cut <= 0:
                cut = self.max_chunk
            else:
                cut += 1
            piece = remaining[:cut].strip()
            if piece:
                parts.append(piece)
            remaining = remaining[cut:].strip()
        if remaining:
            parts.append(remaining)
        return [part for part in parts if len(part) >= _MIN_SPEECH_CHUNK or part]


class Searcher:
    """Runs the live web search in the background while the thinker works."""

    def __init__(self, search_provider):
        self._search = search_provider
        self._thread: threading.Thread | None = None
        self._result: str | None = None
        self._error: str | None = None

    def start(self, query: str) -> "Searcher":
        """Kick off a search without waiting for it."""
        self._result = None
        self._error = None
        if not query or not query.strip():
            return self

        def run() -> None:
            try:
                results = self._search.search(query)
            except Exception as exc:
                self._error = str(exc)
                return
            if not results:
                return
            lines = [
                f"{index}. {item.title}: {item.snippet}"
                for index, item in enumerate(results[:5], 1)
            ]
            self._result = "\n".join(lines)

        self._thread = threading.Thread(target=run, daemon=True)
        self._thread.start()
        return self

    def wait(self, timeout: float = 12.0) -> str | None:
        """Return the search text if it finished in time."""
        if self._thread is not None:
            self._thread.join(timeout)
        return self._result

    @property
    def error(self) -> str | None:
        return self._error


class Thinker:
    """Produces the answer, optionally streaming it."""

    def __init__(self, provider):
        self._provider = provider

    def think(self, prompt: str, context: str | None = None) -> str:
        reply = self._provider.ask(prompt, context)
        text = getattr(reply, "text", reply)
        if not isinstance(text, str):
            return ""
        return text.strip()

    def think_streaming(
        self,
        prompt: str,
        context: str | None = None,
        on_delta: Callable[[str], None] | None = None,
    ) -> str:
        ask_stream = getattr(self._provider, "ask_stream", None)
        if callable(ask_stream):
            return ask_stream(prompt, context, on_delta)
        answer = self.think(prompt, context)
        if on_delta is not None and answer:
            on_delta(answer)
        return answer


def speakable(chunks: Iterable[str]) -> list[str]:
    """Clean the spoken chunks so the voice sounds natural."""
    cleaned: list[str] = []
    for chunk in chunks:
        text = " ".join(str(chunk).split()).strip()
        if not text:
            continue
        # Strip markdown that the model sometimes adds to a spoken answer.
        text = re.sub(r"^[\-*•]\s*", "", text)
        text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
        text = re.sub(r"[*_`#]+", "", text)
        text = text.strip()
        if text:
            cleaned.append(text)
    return cleaned
