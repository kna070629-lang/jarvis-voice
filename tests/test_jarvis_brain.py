import unittest

from jarvis_brain import (
    build_action,
    build_action_system,
    extract_action,
    format_apps,
    strip_action_blocks,
)


APPS = [
    {"label": "YouTube", "package": "com.google.android.youtube"},
    {"label": "WhatsApp", "package": "com.whatsapp"},
    {"label": "Camera", "package": "com.android.camera2"},
]


class BrainTests(unittest.TestCase):
    def test_plain_text_is_not_an_action(self):
        self.assertIsNone(extract_action("The meaning of life is to seek purpose.", APPS))

    def test_open_app_is_accepted_for_an_installed_app(self):
        reply = '```json\n{"action": "open_app", "args": {"name": "YouTube"}}\n```'
        action = extract_action(reply, APPS)
        self.assertIsNotNone(action)
        self.assertEqual("open_app", action["action"])
        self.assertEqual("YouTube", action["args"]["name"])
        self.assertFalse(action["requires_confirmation"])
        self.assertFalse(action["sensitive"])

    def test_open_app_is_rejected_for_an_app_that_is_not_installed(self):
        reply = '```json\n{"action": "open_app", "args": {"name": "Photoshop"}}\n```'
        self.assertIsNone(extract_action(reply, APPS))

    def test_call_is_sensitive_and_requires_confirmation(self):
        reply = '```json\n{"action": "call", "args": {"number": "919876543210"}}\n```'
        action = extract_action(reply, APPS)
        self.assertIsNotNone(action)
        self.assertEqual("call", action["action"])
        self.assertTrue(action["requires_confirmation"])
        self.assertTrue(action["sensitive"])

    def test_call_with_an_invalid_number_is_rejected(self):
        reply = '```json\n{"action": "call", "args": {"number": "12"}}\n```'
        self.assertIsNone(extract_action(reply, APPS))

    def test_whatsapp_needs_a_number_and_a_message(self):
        good = '```json\n{"action": "prepare_whatsapp", "args": {"number": "919876543210", "text": "Hi"}}\n```'
        action = extract_action(good, APPS)
        self.assertIsNotNone(action)
        self.assertTrue(action["requires_confirmation"])
        missing_text = '```json\n{"action": "prepare_whatsapp", "args": {"number": "919876543210"}}\n```'
        self.assertIsNone(extract_action(missing_text, APPS))

    def test_unknown_action_type_is_rejected(self):
        reply = '```json\n{"action": "delete_everything", "args": {}}\n```'
        self.assertIsNone(extract_action(reply, APPS))

    def test_navigation_is_limited_to_known_moves(self):
        good = '```json\n{"action": "accessibility", "args": {"navigation": "home"}}\n```'
        self.assertIsNotNone(extract_action(good, APPS))
        bad = '```json\n{"action": "accessibility", "args": {"navigation": "format_disk"}}\n```'
        self.assertIsNone(extract_action(bad, APPS))

    def test_alarm_time_must_be_a_clock_time(self):
        good = '```json\n{"action": "set_alarm", "args": {"time": "07:30"}}\n```'
        self.assertIsNotNone(extract_action(good, APPS))
        bad = '```json\n{"action": "set_alarm", "args": {"time": "half seven"}}\n```'
        self.assertIsNone(extract_action(bad, APPS))

    def test_malformed_json_is_ignored(self):
        self.assertIsNone(extract_action('```json\n{"action": "open_app",\n```', APPS))
        self.assertIsNone(extract_action("not json at all", APPS))

    def test_action_blocks_are_stripped_from_spoken_text(self):
        reply = "Opening it now.\n```json\n{\"action\": \"open_app\"}\n```"
        self.assertEqual("Opening it now.", strip_action_blocks(reply))

    def test_system_prompt_lists_the_app_vocabulary_and_installed_apps(self):
        system = build_action_system(APPS)
        self.assertIn("open_app", system)
        self.assertIn("prepare_whatsapp", system)
        self.assertIn("YouTube", system)
        self.assertIn("com.google.android.youtube", system)

    def test_app_list_is_bounded_and_deduplicated(self):
        apps = [{"label": "Same", "package": "com.same"}] * 5
        rows = format_apps(apps)
        self.assertEqual(1, len(rows.strip().splitlines()))

    def test_build_action_rejects_a_non_dict(self):
        self.assertIsNone(build_action("open youtube", APPS))


if __name__ == "__main__":
    unittest.main()
