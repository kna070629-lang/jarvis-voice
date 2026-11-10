import tempfile
import json
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import mobile_host
from jarvis_config import JarvisSettings
from jarvis_conversation import ConversationStore
from jarvis_engine import JarvisEngine
from jarvis_memory import MemoryStore
from mobile_bridge import create_server


class FakeAI:
    def __init__(self, response="A genuine mocked AI response"):
        self.calls = []
        self.response = response

    def chat(self, messages):
        self.calls.append(messages)
        return self.response


class FakeSearch:
    def __init__(self):
        self.calls = []

    def search(self, query):
        self.calls.append(query)
        return []


class MobileHostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.settings = JarvisSettings(
            api_key="test-key-not-real", model="google/gemma-4-31b-it:free",
            memory_path=base / "memory.sqlite3", conversation_path=base / "turns.sqlite3",
        )
        self.ai = FakeAI()
        self.search = FakeSearch()
        self.engine = JarvisEngine(
            "test-session", settings=self.settings,
            memory=MemoryStore(self.settings.memory_path),
            conversation=ConversationStore(self.settings.conversation_path),
            ai=self.ai, search=self.search,
        )
        self.old_engine = mobile_host.ENGINE
        mobile_host.ENGINE = self.engine

    def tearDown(self):
        mobile_host.ENGINE = self.old_engine
        self.temp.cleanup()

    def test_memory_command_is_local_and_confirmed_by_database(self):
        self.assertIn("saved", mobile_host.process_message("Remember this: I use Android"))
        self.assertEqual([], self.ai.calls)
        self.assertEqual(1, len(self.engine.memory.list_memories()))

    def test_local_time_never_calls_ai_or_search(self):
        reply = mobile_host.process_message("What time is it?")
        self.assertTrue(reply.startswith("It's "))
        self.assertEqual([], self.ai.calls)
        self.assertEqual([], self.search.calls)

    def test_natural_prompt_uses_ai_and_persists_context(self):
        self.engine.memory.save("I prefer concise answers")
        reply = mobile_host.process_message("How should you format answers?")
        self.assertEqual("A genuine mocked AI response", reply)
        self.assertIn("I prefer concise answers", str(self.ai.calls[0]))
        self.assertEqual(2, len(self.engine.conversation.history()))

    def test_offline_natural_prompt_does_not_call_ai(self):
        offline = JarvisSettings(api_key="test-key-not-real", online_enabled=False,
                                 memory_path=self.settings.memory_path,
                                 conversation_path=self.settings.conversation_path)
        ai = FakeAI()
        engine = JarvisEngine("offline", settings=offline, ai=ai,
                              memory=MemoryStore(offline.memory_path),
                              conversation=ConversationStore(offline.conversation_path))
        result = engine.process("Explain quantum computing")
        self.assertIn("offline mode", result)
        self.assertEqual([], ai.calls)

    def test_memory_clear_requires_confirmation(self):
        self.engine.memory.save("I use Android")
        self.assertIn("confirm clear", self.engine.process("Clear my memory").casefold())
        self.assertEqual(1, len(self.engine.memory.list_memories()))
        self.assertIn("cleared", self.engine.process("confirm clear my memory").casefold())
        self.assertEqual([], self.engine.memory.list_memories())

    def test_credential_like_text_is_not_sent_or_saved(self):
        reply = mobile_host.process_message("My API key is a private credential value")
        self.assertIn("did not save it or send it", reply)
        self.assertEqual([], self.ai.calls)
        self.assertEqual([], self.engine.memory.list_memories())

    def test_conversation_context_persists_and_is_session_scoped(self):
        self.engine.conversation.append("user", "We are discussing a local garden")
        reopened = ConversationStore(self.settings.conversation_path)
        reopened.set_scope("test-session")
        self.assertEqual("We are discussing a local garden", reopened.history()[0]["content"])
        reopened.set_scope("another-session")
        self.assertEqual([], reopened.history())

    def test_authenticated_bridge_to_local_engine_end_to_end(self):
        server = create_server(mobile_host.process_message, token="b" * 43, port=0)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/v1/command"
            body = json.dumps({"action": "chat", "request_id": "req_local_1234", "text": "What time is it?"}).encode()
            request = Request(url, body, method="POST", headers={"Authorization": "Bearer " + "b" * 43})
            with urlopen(request, timeout=3) as response:
                payload = json.loads(response.read())
            self.assertTrue(payload["text"].startswith("It's "))
            self.assertFalse(payload["duplicate"])
            self.assertEqual([], self.ai.calls)

            unauthenticated = Request(url, body, method="POST")
            with self.assertRaises(HTTPError) as caught:
                urlopen(unauthenticated, timeout=3)
            self.assertEqual(401, caught.exception.code)
            caught.exception.close()
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
