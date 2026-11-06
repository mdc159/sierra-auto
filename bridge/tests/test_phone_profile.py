"""Credential publication must be scoped and reject unsafe destinations."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("profile", Path(__file__).parents[1] / "deploy/publish_phone_profile.py")
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class PhoneProfileTest(unittest.TestCase):
    def test_requires_private_https_base_and_token(self):
        for url in ("http://victoria.ts.net", "https://public.example/v1", "https://user:secret@victoria.ts.net",
                    "https://victoria.ts.net/v1?key=secret", "https://victoria.ts.net/chat/completions"):
            with self.assertRaises(ValueError):
                profile.profile_values(url, "token")
        with self.assertRaises(ValueError):
            profile.profile_values("https://victoria.ts.net/v1", "")

    def test_reconciles_only_profile_in_existing_project(self):
        values = profile.profile_values("https://victoria.ts.net:8443/v1/", "example-test-token")
        self.assertEqual({"SIERRA_HERMES_BASE_URL", "SIERRA_HERMES_API_KEY"}, set(values))
        class Vault:
            def find_project(self, slug):
                return "phone-id" if slug == "existing" else None
            def set_secrets(self, project_id, path, secrets):
                self.written = (project_id, path, secrets)
                return 2, 0
        vault = Vault()
        with self.assertRaises(ValueError):
            profile.reconcile(vault, "missing", values)
        self.assertFalse(hasattr(vault, "written"))
        self.assertEqual((2, 0), profile.reconcile(vault, "existing", values))
        self.assertEqual(("phone-id", "/", values), vault.written)
