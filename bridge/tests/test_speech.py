from sierra_bridge.speech import ELLIPSIS, SpeechTrimmer, clean_for_speech, trim_for_speech


def test_clean_strips_markdown_and_urls():
    text = "## Title\n\n- **bold** item\n- `code` item\n\nSee [the docs](https://example.com/x) or https://example.com/y.\n\n```py\nprint(1)\n```\n| a | b |\n"
    assert clean_for_speech(text) == "Title bold item code item See the docs or link. (code omitted) a b"


def test_short_reply_untouched():
    text, trimmed = trim_for_speech("Yes. The build passed.", 600)
    assert text == "Yes. The build passed."
    assert not trimmed


def test_cuts_at_sentence_boundary():
    long = "First sentence here. Second sentence follows. " * 6
    text, trimmed = trim_for_speech(long, 100)
    assert trimmed
    assert text.endswith("." + ELLIPSIS)
    assert len(text) <= 100 + len(ELLIPSIS)
    assert text.startswith("First sentence here. Second sentence follows.")


def test_one_giant_sentence_cuts_at_word():
    long = "word " * 200
    text, trimmed = trim_for_speech(long, 60)
    assert trimmed
    assert text.endswith(ELLIPSIS)
    assert len(text) <= 60
    assert " word" + ELLIPSIS in text or text == "word" * 1 + ELLIPSIS or text.endswith("word" + ELLIPSIS)


def test_streaming_matches_whole_text():
    full = "The engine light means the oxygen sensor is slow. Check the gas cap first! Then come back? More and more text follows here for a while. " * 3
    whole, _ = trim_for_speech(full, 150)
    trimmer = SpeechTrimmer(150)
    out = ""
    for i in range(0, len(full), 5):
        out += trimmer.feed(full[i:i + 5])
    out += trimmer.finish()
    assert out == whole
    assert trimmer.full_text == full
    assert trimmer.sent_text == out


def test_streaming_holds_code_fence_until_closed():
    trimmer = SpeechTrimmer(600)
    out = trimmer.feed("Run this. ```sh\necho hi. done.\n")
    assert out == "Run this."
    out += trimmer.feed("``` Then reboot.")
    out += trimmer.finish()
    assert out == "Run this. (code omitted) Then reboot."


def test_nothing_after_trim():
    trimmer = SpeechTrimmer(40)
    first = trimmer.feed("This is a sentence that fits nicely. ")
    rest = trimmer.feed("This one does not fit anymore at all. ")
    tail = trimmer.feed("Nor this.") + trimmer.finish()
    assert first == "This is a sentence that fits nicely."
    assert rest == ELLIPSIS
    assert tail == ""
    assert trimmer.full_text.endswith("Nor this.")
