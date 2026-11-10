import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from jarvis_memory import MemoryStore, handle_memory_command
from tui import _reply_from_openclaw_json


class MemoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "jarvis.sqlite3"
        self.memory = MemoryStore(self.path)
        self.memory.set_scope("session-one")

    def tearDown(self):
        self.temp.cleanup()

    def test_save_retrieve_and_persist_between_instances(self):
        ok, message = self.memory.save("I prefer concise answers and use Android")
        self.assertTrue(ok)
        self.assertIn("saved", message)
        self.assertTrue(self.memory.retrieve("How should answers be formatted?"))

        reopened = MemoryStore(self.path)
        reopened.set_scope("session-one")
        self.assertEqual(
            self.memory.list_memories(), reopened.list_memories()
        )
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_memory_is_separated_by_session(self):
        self.memory.save("My project is JARVIS")
        other = MemoryStore(self.path)
        other.set_scope("session-two")
        self.assertEqual([], other.list_memories())

    def test_secret_like_content_is_not_saved(self):
        ok, message = self.memory.save("my password is swordfish")
        self.assertFalse(ok)
        self.assertIn("did not save", message)
        self.assertEqual([], self.memory.list_memories())

    def test_unprefixed_long_tokens_are_not_saved(self):
        ok, message = self.memory.save("A" * 48)
        self.assertFalse(ok)
        self.assertIn("did not save", message)
        self.assertEqual([], self.memory.list_memories())

    def test_delete_by_phrase_and_clear(self):
        self.memory.save("I prefer concise answers")
        self.memory.save("I use Android")
        self.assertEqual(1, self.memory.delete_matching("prefer concise"))
        self.assertEqual(1, len(self.memory.list_memories()))
        self.assertEqual(1, self.memory.clear())
        self.assertEqual([], self.memory.list_memories())

    def test_delete_individual_memory_by_id(self):
        self.memory.save("I prefer concise answers")
        memory_id = self.memory.list_memories()[0][0]
        self.assertTrue(self.memory.delete_id(memory_id))
        self.assertFalse(self.memory.delete_id(memory_id))
        self.assertEqual([], self.memory.list_memories())

    def test_commands_only_claim_success_after_database_operation(self):
        self.assertIn("saved", handle_memory_command(self.memory, "Remember this: I use Termux"))
        self.assertIn("Termux", handle_memory_command(self.memory, "What do you remember about me?"))
        self.assertIn("deleted", handle_memory_command(self.memory, "Forget this: I use Termux"))
        self.assertEqual([], self.memory.list_memories())


class OpenClawJsonTests(unittest.TestCase):
    def test_documented_top_level_final_shape(self):
        self.assertEqual(
            "A real answer",
            _reply_from_openclaw_json({"ok": True, "status": "ok", "final": "A real answer"}),
        )

    def test_top_level_payload_shape(self):
        self.assertEqual(
            "A real answer",
            _reply_from_openclaw_json({"payloads": [{"text": "A real answer"}]}),
        )

    def test_gateway_wrapped_shape_and_errors(self):
        self.assertEqual(
            "Nested answer",
            _reply_from_openclaw_json({"result": {"payloads": [{"text": "Nested answer"}]}}),
        )
        self.assertEqual("", _reply_from_openclaw_json({"ok": False, "final": "not a reply"}))
        self.assertEqual("", _reply_from_openclaw_json({"status": "timeout", "final": "late"}))

    def test_deprecated_openclaw_cli_entry_point_never_starts_a_process(self):
        import tui

        with patch.object(tui.subprocess, "run") as run:
            self.assertEqual("", tui.send_message_cli("hello", local=True))
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
