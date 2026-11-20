"""Credential publication must be scoped and reject unsafe destinations."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("profile", Path(__file__).parents[1] / "deploy/publish_phone_profile.py")
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class PhoneProfileTest(unittest.TestCase):
    def test_vault_transport_rejects_public_cleartext_and_url_credentials(self):
        for url in (None, "http://public.example", "http://192.168.1.2", "ftp://vault.ts.net",
                    "https://user:secret@vault.ts.net", "https://vault.ts.net?token=secret",
                    "https://vault.ts.net/other", "https://", "http://100.128.0.1"):
            with self.subTest(url=url), self.assertRaises(profile.ProfileError):
                profile.vault_url(url)
        for url in ("https://vault.example", "http://studio54.ts.net:8080", "http://100.88.239.16:8080",
                    "http://[fd7a:115c:a1e0::1]:8080"):
            self.assertEqual(url, profile.vault_url(url + "/"))

    def test_alias_cleanup_is_scoped_and_verified(self):
        values = profile.profile_values("https://victoria.ts.net/v1", "test-token")
        class Vault:
            def __init__(self, old, verify=True, status=200):
                self.old, self.verify, self.status, self.deleted = old, verify, status, []
            def find_project(self, slug):
                return "existing-id"
            def set_secrets(self, project_id, path, secrets):
                self.values = secrets
                return 0, 0
            def get_secret(self, project_id, path, key):
                if key == "SIERRA_HERMES_API_KEY":
                    return self.old
                return self.values.get(key) if self.verify else None
            def api(self, method, path, body):
                self.deleted.append((method, path, body))
                return self.status, {}
        for status in (200, 404):
            vault = Vault("test-token", status=status)
            profile.reconcile(vault, "phone", values)
            self.assertEqual([("DELETE", "/api/v4/secrets/SIERRA_HERMES_API_KEY",
                               {"projectId": "existing-id", "environment": "prod", "secretPath": "/"})], vault.deleted)
        vault = Vault("subsequently-changed")
        profile.reconcile(vault, "phone", values)
        self.assertEqual([], vault.deleted)
        vault = Vault("test-token", verify=False)
        with self.assertRaisesRegex(profile.ProfileError, "verification failed"):
            profile.reconcile(vault, "phone", values)
        self.assertEqual([], vault.deleted)
        vault = Vault("test-token", status=500)
        with self.assertRaisesRegex(profile.ProfileError, "saved but alias cleanup failed"):
            profile.reconcile(vault, "phone", values)

    def test_requires_private_https_base_and_token(self):
        for url in ("http://victoria.ts.net", "https://public.example/v1", "https://user:secret@victoria.ts.net",
                    "https://victoria.ts.net/v1?key=secret", "https://victoria.ts.net/chat/completions"):
            with self.assertRaises(ValueError):
                profile.profile_values(url, "token")
        with self.assertRaises(ValueError):
            profile.profile_values("https://victoria.ts.net/v1", "")

    def test_reconciles_only_profile_in_existing_project(self):
        values = profile.profile_values("https://victoria.ts.net:8443/v1/", "example-test-token")
        self.assertEqual({"SIERRA_BRIDGE_URL", "SIERRA_BRIDGE_TOKEN"}, set(values))
        class Vault:
            def find_project(self, slug):
                return "phone-id" if slug == "existing" else None
            def set_secrets(self, project_id, path, secrets):
                self.written = (project_id, path, secrets)
                return 2, 0
            def get_secret(self, project_id, path, key):
                return None
        vault = Vault()
        with self.assertRaises(ValueError):
            profile.reconcile(vault, "missing", values)
        self.assertFalse(hasattr(vault, "written"))
        self.assertEqual((2, 0), profile.reconcile(vault, "existing", values))
        self.assertEqual(("phone-id", "/", values), vault.written)
