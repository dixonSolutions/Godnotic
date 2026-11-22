#!/usr/bin/env python3
"""Check the SDL text input connection retains Android's non-fullscreen flags."""

import re
import sys
from pathlib import Path


def check(source):
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.DOTALL)
    methods = re.findall(
        r"\bInputConnection\s+onCreateInputConnection\s*\(\s*EditorInfo\s+(\w+)\s*\)\s*\{([^{}]*)\}",
        source,
    )
    if len(methods) != 1:
        return False
    editor, body = methods[0]
    assignments = re.findall(r"\b" + re.escape(editor) + r"\.imeOptions\s*=\s*([^;]+);", body)
    if len(assignments) != 1:
        return False
    flags = {part.strip() for part in assignments[0].split("|")}
    return {"EditorInfo.IME_FLAG_NO_EXTRACT_UI", "EditorInfo.IME_FLAG_NO_FULLSCREEN"} <= flags


def check_ordering(source):
    """The typing-order guards of scripts/android-patch-sdl-java.sh are in SDLInputConnection."""
    m = re.search(r"class SDLInputConnection\b.*?\n}\n", source, flags=re.DOTALL)
    if not m:
        return False
    body = m.group(0)
    pins = len(re.findall(r"Selection\.setSelection\(\s*\w+\s*,\s*\w+\.length\(\)\s*\)", body))
    override = re.search(r"public\s+boolean\s+setSelection\s*\(\s*int\s+\w+\s*,\s*int\s+\w+\s*\)", body)
    del_key = "KeyEvent.KEYCODE_DEL" in body and "field.delete(" in body
    # the pin in updateText comes before the committed text is recorded
    ordered = re.search(r"Selection\.setSelection\([^;]*;[^\n]*\n\s*mCommittedText\s*=\s*text;", body)
    return pins >= 2 and bool(override) and del_key and bool(ordered)


if __name__ == "__main__":
    ordering = len(sys.argv) == 3 and sys.argv[2] == "--ordering"
    if len(sys.argv) not in (2, 3) or (len(sys.argv) == 3 and not ordering):
        sys.exit("usage: check-sdl-chat-ime.py SDLActivity.java [--ordering]")
    text = Path(sys.argv[1]).read_text()
    if ordering:
        if not check_ordering(text):
            sys.exit("SDL chat input: the cursor pin or the Backspace mirror is missing from SDLInputConnection")
        print("SDL chat input: typing-order guards present")
    else:
        if not check(text):
            sys.exit("SDL chat input must set both IME_FLAG_NO_EXTRACT_UI and IME_FLAG_NO_FULLSCREEN")
        print("SDL chat input: non-fullscreen IME flags present")
