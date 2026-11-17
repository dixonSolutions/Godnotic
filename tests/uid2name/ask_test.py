#!/usr/bin/env python3
"""The player-statistics question: asked at most once, answer saved.

A source guard, not a behavioural run: the menu VM needs an engine and a
display. It reads the shipped QuakeC and the native Settings screen and fails
when a property the fix depends on goes away:

  * the answer is written with seta + saveconfig (on disk, not just this run);
  * the question is refused once `_touch_uid2name_asked` is set, and the latch
    is written with seta + saveconfig the first time it is shown;
  * the answer still tells the game code (setreport) and drops the dialog;
  * Settings keeps a row for cl_allow_uid2name, so it stays changeable there.
"""
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[2]
qc = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uid2name.qc').read_text()
settings = (root / 'engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_settings.qc').read_text()

failures = []


def body(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def want(ok, what):
    if not ok:
        failures.append(what)


open_ = body(qc, 'bool UIB_Uid2NameOpen()')
answer = body(qc, 'void UIB_Uid2NameAnswer(bool yes)')

want('cl_allow_uid2name") != -1' in open_, 'an answered question is not shown again')
want('_touch_uid2name_asked' in open_, 'the once-only latch is consulted')
want(open_.index('_touch_uid2name_asked') < open_.index('UIB_Modal("uid2name")'),
     'the latch is checked before the dialog opens')
want('seta _touch_uid2name_asked 1' in open_, 'the latch is saved when asked')
want('saveconfig' in open_, 'the latch reaches the disk at once')

want('seta cl_allow_uid2name 1' in answer and 'seta cl_allow_uid2name 0' in answer,
     'the answer is archived, not only set for this run')
want('saveconfig' in answer, 'the answer reaches the disk at once')
want('setreport cl_allow_uid2name 1' in answer and 'setreport cl_allow_uid2name 0' in answer,
     'the server is still told the choice')
want('UIB_DropLast(true)' in answer, 'the dialog closes on an answer')

want('cl_allow_uid2name' in settings and 'UIB_Choice(' in settings,
     'Settings has a row that changes cl_allow_uid2name')

for f in failures:
    print('FAIL', f)
if failures:
    sys.exit(1)
print('uid2name: asked at most once, answer persisted, Settings row present')
