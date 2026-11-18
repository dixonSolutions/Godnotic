#!/usr/bin/env python3
"""Regression tests for the SDL Android chat keyboard build check."""

import importlib.util
from pathlib import Path
import unittest

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_sdl_chat_ime", root / "scripts/check-sdl-chat-ime.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

SOURCE = """public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                          EditorInfo.IME_FLAG_NO_FULLSCREEN /* API 11 */;
    return ic;
}"""


class ChatImeTest(unittest.TestCase):
    def test_sdl_flags(self):
        self.assertTrue(module.check(SOURCE))

    def test_missing_flags_fail(self):
        for flag in ("NO_EXTRACT_UI", "NO_FULLSCREEN"):
            with self.subTest(flag=flag):
                self.assertFalse(module.check(SOURCE.replace("EditorInfo.IME_FLAG_" + flag, "0")))

    def test_flags_outside_assignment_do_not_count(self):
        self.assertFalse(module.check(SOURCE.replace("outAttrs.imeOptions =", "int other =")))
        self.assertFalse(module.check(SOURCE.replace("EditorInfo.IME_FLAG_NO_FULLSCREEN", "0 // EditorInfo.IME_FLAG_NO_FULLSCREEN\n")))

    def test_duplicate_assignment_fails(self):
        self.assertFalse(module.check(SOURCE.replace("return ic;", "outAttrs.imeOptions = 0; return ic;")))


if __name__ == "__main__":
    unittest.main()
