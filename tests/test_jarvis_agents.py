import json
import tempfile
import unittest
from pathlib import Path

from jarvis_agents import Searcher, Speaker, speakable
from jarvis_selfcheck import SelfCheck


class SpeakerTests(unittest.TestCase):
    def test_a_sentence_is_released_when_the_next_one_starts(self):
        speaker = Speaker()
        chunks = speaker.feed("Paris is the capital of France. ")
        self.assertEqual(["Paris is the capital of France."], chunks)

    def test_a_partial_sentence_is_withheld(self):
        speaker = Speaker()
        self.assertEqual([], speaker.feed("Paris is the cap"))
        self.assertEqual(["Paris is the capital of France."], speaker.feed("ital of France. "))

    def test_the_last_sentence_is_held_until_the_stream_ends(self):
        speaker = Speaker()
        self.assertEqual([], speaker.feed("Paris is the capital."))
        self.assertEqual(["Paris is the capital."], speaker.flush())

    def test_a_decimal_is_not_mistaken_for_a_sentence_end(self):
        speaker = Speaker()
        self.assertEqual([], speaker.feed("It weighs 3."))
        self.assertEqual([], speaker.feed("5 kilograms."))
        self.assertEqual(["It weighs 3.5 kilograms."], speaker.flush())

    def test_a_very_long_sentence_is_cut_at_a_comma(self):
        speaker = Speaker(max_chunk=40)
        chunks = speaker.feed("This is a long sentence with, a comma in it somewhere, and more. ")
        self.assertGreater(len(chunks), 1)

    def test_markdown_is_removed_from_spoken_text(self):
        self.assertEqual(["bold thing"], speakable(["- **bold** thing"]))
        self.assertEqual(["plain"], speakable(["`plain`"]))


class SearcherTests(unittest.TestCase):
    def test_the_search_runs_in_the_background(self):
        class Fake:
            def search(self, query):
                return [type("Item", (), {"title": "T", "snippet": "S"})()]

        searcher = Searcher(Fake()).start("eiffel tower")
        text = searcher.wait(2)
        self.assertIsNotNone(text)
        self.assertIn("T", text)

    def test_a_failing_search_reports_the_error(self):
        class Broken:
            def search(self, query):
                raise RuntimeError("no network")

        searcher = Searcher(Broken()).start("anything")
        searcher.wait(2)
        self.assertIsNotNone(searcher.error)


class SelfCheckTests(unittest.TestCase):
    def test_a_healthy_engine_reports_no_problems(self):
        class Memory:
            def list_memories(self):
                return []

        class Engine:
            memory = Memory()
            ai = None

        check = SelfCheck(engine=Engine())
        report = check.run()
        self.assertIn("checks", report)
        self.assertEqual([], report["problems"])

    def test_a_broken_memory_is_reported(self):
        class Memory:
            def list_memories(self):
                raise RuntimeError("database locked")

        class Engine:
            memory = Memory()
            ai = None

        check = SelfCheck(engine=Engine())
        report = check.run()
        self.assertTrue(report["problems"])

    def test_recurring_failures_are_counted_and_suggested(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "bridge.log"
            log.write_text(
                "\n".join(
                    [
                        "AI provider opencode-server failed: timed out",
                        "No AI provider answered: AI: opencode: timed out",
                        "AI provider opencode-server failed: timed out",
                    ]
                ),
                encoding="utf-8",
            )
            report = SelfCheck().learn_from_logs(log)
            self.assertTrue(report["read"])
            self.assertEqual(2, report["patterns"]["provider_timeout"]["count"])
            self.assertTrue(report["patterns"]["provider_timeout"]["suggestion"])

    def test_a_missing_log_does_not_raise(self):
        report = SelfCheck().learn_from_logs(Path("/definitely/not/here.log"))
        self.assertFalse(report["read"])

    def test_a_failed_request_triggers_a_session_reset(self):
        class Candidate:
            def __init__(self):
                self.reset_called = False
                self.warm_called = False

            def reset(self):
                self.reset_called = True

            def warm(self):
                self.warm_called = True
                return True

        class Provider:
            providers = [Candidate()]

        class Engine:
            ai = Provider()

        candidate = Provider.providers[0]
        check = SelfCheck(engine=Engine())
        actions = check.repair_after_failure("the OpenCode server did not answer")
        self.assertTrue(candidate.reset_called)
        self.assertTrue(actions)


if __name__ == "__main__":
    unittest.main()
