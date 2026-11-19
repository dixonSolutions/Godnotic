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


if __name__ == "__main__":
    if len(sys.argv) != 2 or not check(Path(sys.argv[1]).read_text()):
        sys.exit("SDL chat input must set both IME_FLAG_NO_EXTRACT_UI and IME_FLAG_NO_FULLSCREEN")
    print("SDL chat input: non-fullscreen IME flags present")
