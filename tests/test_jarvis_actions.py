import re
import unittest

from jarvis_actions import interpret_device_action


class DeviceActionInterpretationTests(unittest.TestCase):
    def test_activate_and_open_app_forms(self):
        for text, expected in (
            ("Jarvis, activate YouTube", "youtube"),
            ("Hey Jarvis, open WhatsApp", "whatsapp"),
            ("activate Chrome", "chrome"),
            ("launch Instagram", "instagram"),
            ("open Google Maps", "google maps"),
            ("open Settings", "settings"),
        ):
            action = interpret_device_action(text)
            self.assertIsNotNone(action, text)
            if expected == "settings":
                self.assertEqual("open_settings", action["action"])
            else:
                self.assertEqual("open_app", action["action"])
                self.assertEqual(expected, action["args"]["name"].casefold())

    def test_actions_have_ids_and_are_not_executable_text(self):
        action = interpret_device_action("open YouTube")
        self.assertRegex(action["action_id"], re.compile(r"^[a-f0-9]{32}$"))
        # The contract states the target, the arguments and what has not
        # happened yet, so the companion can report the real result.
        for field in (
            "action_id", "request_id", "action", "action_type", "args",
            "arguments", "target", "permissions", "permission_status",
            "requires_confirmation", "execution_status", "result", "error",
            "sensitive", "timestamp",
        ):
            self.assertIn(field, action)
        self.assertEqual("pending", action["execution_status"])
        self.assertEqual(action["action_id"], action["request_id"])
        self.assertFalse(action["requires_confirmation"])

    def test_a_call_does_not_need_a_second_confirmation(self):
        # The spoken command is the authorisation for a call, so a second
        # confirmation would be the repetitive prompt the user complained about.
        for text in ("call 919876543210", "call prakash", "call home"):
            action = interpret_device_action(text)
            self.assertIsNotNone(action, text)
            self.assertFalse(action["requires_confirmation"], text)

    def test_messages_still_require_one_confirmation(self):
        action = interpret_device_action("send a whatsapp message to 919876543210 saying hi")
        self.assertIsNotNone(action)
        self.assertTrue(action["requires_confirmation"])

    def test_messages_require_confirmation(self):
        call = interpret_device_action("call +1 415 555 0199")
        self.assertEqual("call", call["action"])
        # A call is authorised by the spoken command itself.
        self.assertFalse(call["requires_confirmation"])

        sms = interpret_device_action("prepare SMS to +1 415 555 0199 saying I am on my way")
        self.assertEqual("prepare_sms", sms["action"])
        self.assertTrue(sms["requires_confirmation"])
        self.assertEqual("14155550199", sms["args"]["number"])

        whatsapp = interpret_device_action("send WhatsApp message to +1 415 555 0199 saying hello")
        self.assertEqual("prepare_whatsapp", whatsapp["action"])
        self.assertTrue(whatsapp["sensitive"])

    def test_notifications_require_confirmation(self):
        action = interpret_device_action("read my notifications")
        self.assertEqual("read_notifications", action["action"])
        self.assertTrue(action["requires_confirmation"])
        self.assertTrue(action["sensitive"])

    def test_alarm_dialer_and_navigation(self):
        alarm = interpret_device_action("set alarm for 7:30 am")
        self.assertEqual("set_alarm", alarm["action"])
        self.assertEqual("7:30 am", alarm["args"]["time"])

        dialer = interpret_device_action("dial +1 415 555 0199")
        self.assertEqual("open_dialer", dialer["action"])
        self.assertFalse(dialer["requires_confirmation"])

        for phrase, target in (
            ("go back", "back"),
            ("go home", "home"),
            ("show recents", "recents"),
            ("go to recent apps", "recents"),
        ):
            action = interpret_device_action(phrase)
            self.assertEqual("accessibility", action["action"])
            self.assertEqual(target, action["args"]["navigation"])

    def test_general_text_is_not_a_device_action(self):
        for text in (
            "Tell me how to open Spotify",
            "Open source is important",
            "Start the meeting notes",
            "Run a quick calculation",
            "open my project",
            "what is the weather in London",
            "Explain quantum computing",
            "tell me about my bank account",
        ):
            self.assertIsNone(interpret_device_action(text), text)

    def test_a_person_can_be_called_by_name(self):
        action = interpret_device_action("call ramesh")
        self.assertEqual("call_contact", action["action"])
        self.assertEqual("ramesh", action["args"]["name"])
        # The command is the authorisation, so no second confirmation.
        self.assertFalse(action["requires_confirmation"])

    def test_a_question_about_a_person_is_not_a_call(self):
        self.assertIsNone(interpret_device_action("call ramesh about the invoice"))
        self.assertIsNone(interpret_device_action("tell me about my bank account"))

    def test_an_existing_chat_can_be_opened_by_name(self):
        action = interpret_device_action("open chat with priya")
        self.assertEqual("open_chat", action["action"])
        self.assertEqual("priya", action["args"]["name"])
        self.assertTrue(action["requires_confirmation"])


if __name__ == "__main__":
    unittest.main()
