import json
import tempfile
import unittest
from pathlib import Path

from jarvis_setup import save_openrouter_key


class SetupTests(unittest.TestCase):
    def test_key_saved_only_to_owner_private_configuration(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "private" / "secrets.json"
            save_openrouter_key("test-credential-value-for-permission-checks", path)
            self.assertEqual(0o700, path.parent.stat().st_mode & 0o777)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual("test-credential-value-for-permission-checks", json.loads(path.read_text())["openrouter_api_key"])

    def test_invalid_key_is_not_written(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "private" / "secrets.json"
            with self.assertRaises(ValueError):
                save_openrouter_key("short", path)
            self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
