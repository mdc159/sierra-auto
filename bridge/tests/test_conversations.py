from sierra_bridge.conversations import ConversationRegistry, explicit_id, message_text


def test_message_text_handles_parts():
    assert message_text("hi") == "hi"
    assert message_text([{"type": "text", "text": "a"}, {"type": "image_url", "image_url": {}}, {"type": "text", "text": "b"}]) == "ab"
    assert message_text(None) == ""


def test_explicit_id_validation():
    assert explicit_id("truck-1") == "truck-1"
    assert explicit_id("  abc.def_1 ") == "abc.def_1"
    assert explicit_id("has space") is None
    assert explicit_id("../etc") is None
    assert explicit_id("x" * 65) is None
    assert explicit_id(5) is None


def test_chains_on_last_reply_even_with_rolling_window():
    reg = ConversationRegistry()
    cid, new = reg.resolve([{"role": "user", "content": "hello"}])
    assert new
    reg.remember(cid, "Hi there.")
    # Second turn: the client sends the reply back.
    cid2, new2 = reg.resolve([{"role": "user", "content": "hello"}, {"role": "assistant", "content": "Hi there."}, {"role": "user", "content": "more"}])
    assert (cid2, new2) == (cid, False)
    reg.remember(cid, "Sure.")
    # Window rolled: the first exchange is gone, only the last reply remains.
    cid3, new3 = reg.resolve([{"role": "assistant", "content": "Sure."}, {"role": "user", "content": "again"}])
    assert (cid3, new3) == (cid, False)


def test_unknown_reply_starts_new_conversation():
    reg = ConversationRegistry()
    a, _ = reg.resolve([{"role": "assistant", "content": "never seen"}, {"role": "user", "content": "x"}])
    b, _ = reg.resolve([{"role": "assistant", "content": "never seen"}, {"role": "user", "content": "x"}])
    assert a != b


def test_explicit_id_wins():
    reg = ConversationRegistry()
    cid, new = reg.resolve([{"role": "user", "content": "x"}], explicit="truck")
    assert (cid, new) == ("truck", False)


def test_entries_expire():
    now = [0.0]
    reg = ConversationRegistry(ttl_seconds=10, clock=lambda: now[0])
    reg.remember("c1", "reply")
    now[0] = 5
    assert reg.resolve([{"role": "assistant", "content": "reply"}])[0] == "c1"
    now[0] = 20
    assert reg.resolve([{"role": "assistant", "content": "reply"}])[1] is True


def test_capacity_bound():
    reg = ConversationRegistry(max_entries=3)
    for i in range(10):
        reg.remember(f"c{i}", f"reply {i}")
    assert len(reg._by_reply) == 3
