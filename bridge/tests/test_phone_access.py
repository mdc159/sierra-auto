import copy
import errno
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

import pytest

spec = importlib.util.spec_from_file_location("phone_access", Path(__file__).parents[1] / "deploy" / "ensure_phone_access.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_adds_only_one_exact_device_port_grant_and_is_idempotent():
    original = {"grants": [{"src": ["tag:workstation"], "dst": ["*"], "ip": ["*"]}], "ssh": [{"action": "check"}]}
    before = copy.deepcopy(original)
    updated, changed = module.reconcile(original, "100.92.69.24", "100.112.150.24", 8443)
    assert changed and original == before
    assert updated["ssh"] == original["ssh"]
    assert updated["grants"][:-1] == original["grants"]
    assert updated["grants"][-1] == {"src": ["100.92.69.24"], "dst": ["100.112.150.24"], "ip": ["tcp:8443"]}
    again, changed = module.reconcile(updated, "100.92.69.24", "100.112.150.24", 8443)
    assert not changed and again == updated


def test_hujson_preserves_quoted_comment_like_content():
    assert module.parse_policy('{// comment\n"note":"https://host/,}/*value*/", "grants":[],}') == {
        "note": "https://host/,}/*value*/", "grants": []}


@pytest.mark.parametrize("address", ["*", "0.0.0.0/0", "8.8.8.8", "192.168.0.1"])
def test_refuses_wildcards_or_non_tailnet_addresses(address):
    with pytest.raises(ValueError):
        module.reconcile({}, address, "100.112.150.24", 8443)


class Response:
    def __init__(self, data, etag=None):
        self.data = json.dumps(data).encode()
        self.headers = {"ETag": etag}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def read(self):
        return self.data


class Opener:
    def __init__(self, *replies):
        self.replies, self.requests = iter(replies), []

    def open(self, request, timeout):
        self.requests.append(request)
        reply = next(self.replies)
        if isinstance(reply, Exception):
            raise reply
        return reply


OAUTH = {"TAILSCALE_OAUTH_CLIENT_ID": "test-client", "TAILSCALE_OAUTH_CLIENT_SECRET": "test-secret"}


def token(value):
    return Response({"access_token": value, "token_type": "Bearer", "expires_in": 3600})


def denied(status):
    return urllib.error.HTTPError(module.API, status, "refused", {}, io.BytesIO(b"private error"))


def test_oauth_preferred_over_stale_api_key_and_refreshes_before_expiry():
    now = [0.0]
    opener = Opener(token("first"), token("second"))
    auth = module.TailnetAuth(opener, {**OAUTH, "TAILSCALE_API_KEY": "stale"}, lambda: now[0])
    assert auth.header() == "Bearer first"
    now[0] = 3539
    assert auth.header() == "Bearer first" and len(opener.requests) == 1
    now[0] = 3540
    assert auth.header() == "Bearer second"
    request = opener.requests[0]
    assert request.full_url == module.TOKEN_URL and request.method == "POST"
    assert "test-secret" not in request.full_url
    assert urllib.parse.parse_qs(request.data.decode()) == {
        "grant_type": ["client_credentials"], "client_id": ["test-client"], "client_secret": ["test-secret"]}


def test_access_token_rejection_refreshes_once_and_retains_policy_precondition():
    opener = Opener(token("first"), denied(401), token("second"), Response({"saved": True}, '"next"'))
    auth = module.TailnetAuth(opener, OAUTH)
    result, etag = module.api_request(opener, auth, "POST", b'{"grants":[]}', etag='"before"')
    assert json.loads(result) == {"saved": True} and etag == '"next"'
    updates = [r for r in opener.requests if r.full_url == module.API]
    assert [r.get_header("Authorization") for r in updates] == ["Bearer first", "Bearer second"]
    assert all(r.get_header("If-match") == '"before"' and r.data == b'{"grants":[]}' for r in updates)


@pytest.mark.parametrize("status", [401, 403, 412])
def test_persistent_denial_or_conflict_does_not_loop_or_fall_back(status):
    replies = [token("first"), denied(status)]
    if status == 401:
        replies += [token("second"), denied(status)]
    opener = Opener(*replies)
    auth = module.TailnetAuth(opener, {**OAUTH, "TAILSCALE_API_KEY": "old-key"})
    with pytest.raises(urllib.error.HTTPError) as error:
        module.api_request(opener, auth)
    assert error.value.code == status
    assert len(opener.requests) == (4 if status == 401 else 2)


def test_rejected_oauth_client_does_not_retry_or_use_static_key():
    opener = Opener(denied(401))
    auth = module.TailnetAuth(opener, {**OAUTH, "TAILSCALE_API_KEY": "old-key"})
    with pytest.raises(urllib.error.HTTPError):
        module.api_request(opener, auth)
    assert len(opener.requests) == 1


def test_partial_oauth_configuration_fails_before_any_request():
    opener = Opener()
    with pytest.raises(RuntimeError, match="Incomplete"):
        module.TailnetAuth(opener, {"TAILSCALE_OAUTH_CLIENT_ID": "test", "TAILSCALE_API_KEY": "old"})
    assert not opener.requests


def test_malformed_token_response_does_not_expose_values():
    opener = Opener(Response({"access_token": "secret-value", "token_type": "Bearer", "expires_in": 0}))
    with pytest.raises(RuntimeError) as error:
        module.TailnetAuth(opener, OAUTH).header()
    assert "secret-value" not in str(error.value)


def test_legacy_static_key_and_no_redirect_support_remain():
    assert module.TailnetAuth(Opener(), {"TAILSCALE_API_KEY": "test"}).header() == "Basic dGVzdDo="
    assert module.NoRedirect().redirect_request(None, None, 302, "", {}, "https://unrelated.test") is None


def checkout(tmp_path: Path) -> Path:
    """A fake canonical checkout root with a .git directory and no .local yet."""
    root = tmp_path / "checkout"
    (root / ".git").mkdir(parents=True)
    return root


def test_canonical_root_of_main_checkout(tmp_path):
    root = checkout(tmp_path)
    script = root / "bridge" / "deploy" / "ensure_phone_access.py"
    script.parent.mkdir(parents=True)
    script.touch()
    assert module.canonical_root(script) == root


def test_canonical_root_follows_linked_worktree_git_pointer(tmp_path):
    root = checkout(tmp_path)
    worktree = tmp_path / "worktrees" / "probe"
    script = worktree / "bridge" / "deploy" / "ensure_phone_access.py"
    script.parent.mkdir(parents=True)
    script.touch()
    (worktree / ".git").write_text(
        f"gitdir: {root / '.git' / 'worktrees' / 'probe'}\n", encoding="utf-8")
    assert module.canonical_root(script) == root


def test_evidence_destination_inside_canonical_local_is_accepted(tmp_path, monkeypatch):
    root = checkout(tmp_path)
    nested = root / ".local" / "reviews" / "phone-access-run"
    assert module.validate_evidence(nested, root) == Path(os.path.abspath(nested))
    # Lexical traversal that stays inside .local is fine, and nothing is created.
    assert module.validate_evidence(root / ".local" / "runs" / ".." / "runs2" / "ev", root) \
        == Path(os.path.abspath(root / ".local" / "runs2" / "ev"))
    monkeypatch.chdir(root)
    assert module.validate_evidence(Path(".local") / "ev", root) \
        == Path(os.path.abspath(root / ".local" / "ev"))
    assert not (root / ".local").exists()


@pytest.mark.parametrize("tracked", ["evidence", "bridge/captures", ".git/hooks/ev"])
def test_evidence_destination_on_tracked_paths_rejected(tmp_path, tracked):
    root = checkout(tmp_path)
    with pytest.raises(ValueError, match="must be a new directory under"):
        module.validate_evidence(root / tracked, root)


def test_evidence_destination_outside_the_checkout_rejected(tmp_path):
    root = checkout(tmp_path)
    with pytest.raises(ValueError, match="must be a new directory under"):
        module.validate_evidence(tmp_path / "elsewhere" / "ev", root)


@pytest.mark.parametrize("relative", [".local/../escape", ".local/runs/../../escape"])
def test_evidence_traversal_escape_rejected(tmp_path, relative):
    root = checkout(tmp_path)
    with pytest.raises(ValueError, match="must be a new directory under"):
        module.validate_evidence(root / relative, root)


def test_evidence_destination_equal_to_local_root_rejected(tmp_path):
    root = checkout(tmp_path)
    with pytest.raises(ValueError, match="must be a new directory under"):
        module.validate_evidence(root / ".local", root)


def test_existing_evidence_destination_rejected(tmp_path):
    root = checkout(tmp_path)
    existing = root / ".local" / "ev"
    existing.mkdir(parents=True)
    with pytest.raises(ValueError, match="already exists"):
        module.validate_evidence(existing, root)


def make_symlink(link: Path, target_dir: Path):
    link.parent.mkdir(parents=True)
    try:
        os.symlink(target_dir, link, target_is_directory=True)
    except OSError as error:
        privileged = getattr(error, "winerror", None) == 1314 or error.errno in (errno.EPERM, errno.EACCES)
        if not privileged:
            raise  # a fixture error must fail, not silently skip
        pytest.skip(f"symlink privilege unavailable: {error}")


def test_evidence_symlink_escape_rejected(tmp_path):
    root = checkout(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    link = root / ".local" / "link"
    make_symlink(link, outside)
    with pytest.raises(ValueError, match="must not traverse a link"):
        module.validate_evidence(link / "ev", root)
    # The link itself is an existing (link) destination and is refused too.
    with pytest.raises(ValueError, match="already exists"):
        module.validate_evidence(link, root)


def test_evidence_junction_escape_rejected(tmp_path):
    if sys.platform != "win32":
        pytest.skip("junction escape is a Windows-only mechanism")
    import _winapi
    if not hasattr(_winapi, "CreateJunction"):
        pytest.skip("stdlib junction support unavailable")
    root = checkout(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    junction = root / ".local" / "junction"
    junction.parent.mkdir()
    _winapi.CreateJunction(str(outside), str(junction))
    try:
        with pytest.raises(ValueError, match="must not traverse a link"):
            module.validate_evidence(junction / "ev", root)
    finally:
        os.rmdir(junction)


def test_invalid_evidence_rejected_before_any_api_call(tmp_path, monkeypatch):
    root = checkout(tmp_path)
    monkeypatch.setattr(module, "canonical_root", lambda start=None: root)
    opener = Opener()
    monkeypatch.setattr(urllib.request, "build_opener", lambda *args, **kwargs: opener)
    monkeypatch.setenv("TAILSCALE_OAUTH_CLIENT_ID", "test-client")
    monkeypatch.setenv("TAILSCALE_OAUTH_CLIENT_SECRET", "test-secret")
    monkeypatch.setattr(sys, "argv", [
        "ensure_phone_access.py", "--source", "100.92.69.24",
        "--destination", "100.112.150.24", "--port", "8443",
        "--evidence", str(root / "bridge" / "captures")])
    with pytest.raises(ValueError, match="must be a new directory under"):
        module.main()
    assert not opener.requests  # no token mint, no policy read: validation came first
    assert not (root / "bridge" / "captures").exists()
