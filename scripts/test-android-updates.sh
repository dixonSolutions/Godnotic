#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# The Updates screen's decisions on an AndySU install (AndySUState.java): mode for the
# Automatic / Wi-Fi only switches, Check now and Download now, the status record, download
# progress from the host's .part files. Without a host (AppUpdateState.java): when the app
# updater may download, why a found release waits, the last check, the record's details.
# Plain JVM, no Android SDK: javac + JUnit 4.
#
#   scripts/test-android-updates.sh        (JUNIT_JAR / HAMCREST_JAR to point at the jars;
#                                           otherwise they are looked up in ~/.gradle and ~/.m2)
# The same test also runs under Gradle: ./gradlew testDebugUnitTest in android/.
set -euo pipefail
cd "$(dirname "$0")/.."
find_jar() {
	find ~/.gradle ~/.m2 /usr/share/java -name "$1" -print -quit 2>/dev/null || true
}
junit=${JUNIT_JAR:-$(find_jar 'junit-4.13*.jar')}
hamcrest=${HAMCREST_JAR:-$(find_jar 'hamcrest-core-1.3.jar')}
[ -n "$junit" ] && [ -n "$hamcrest" ] || { echo "JUnit 4 and hamcrest-core jars not found; set JUNIT_JAR and HAMCREST_JAR" >&2; exit 1; }
tmp=$(mktemp -d "${TMPDIR:-/tmp}/androidupd.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
pkg=io/github/dixonsolutions/godnotic
javac -Xlint:all -Werror -d "$tmp" -cp "$junit" \
	android/app/src/main/java/$pkg/AndySUState.java \
	android/app/src/main/java/$pkg/AppUpdateState.java \
	android/app/src/main/java/$pkg/UpdateRetry.java \
	android/app/src/main/java/$pkg/CrashReport.java \
	android/app/src/main/java/$pkg/ProcessStateText.java \
	android/app/src/main/java/$pkg/PackageReplace.java \
	android/app/src/main/java/$pkg/PollLoop.java \
	android/app/src/main/java/$pkg/HostProcess.java \
	android/app/src/main/java/$pkg/ReplaceSequence.java \
	android/app/src/main/java/$pkg/SwapTrial.java \
	android/app/src/main/java/$pkg/LiveSwapGuard.java \
	android/app/src/main/java/$pkg/LiveApply.java \
	android/app/src/test/java/$pkg/AndySUStateTest.java \
	android/app/src/test/java/$pkg/AppUpdateStateTest.java \
	android/app/src/test/java/$pkg/UpdateRetryTest.java \
	android/app/src/test/java/$pkg/UpdateBackgroundTest.java \
	android/app/src/test/java/$pkg/CrashReportTest.java \
	android/app/src/test/java/$pkg/ProcessStateTextTest.java \
	android/app/src/test/java/$pkg/PackageReplaceTest.java \
	android/app/src/test/java/$pkg/PollLoopTest.java \
	android/app/src/test/java/$pkg/HostProcessTest.java \
	android/app/src/test/java/$pkg/ReplaceSequenceTest.java \
	android/app/src/test/java/$pkg/SwapTrialTest.java \
	android/app/src/test/java/$pkg/LiveSwapGuardTest.java \
	android/app/src/test/java/$pkg/LiveApplyTest.java
java -cp "$tmp:$junit:$hamcrest" org.junit.runner.JUnitCore \
	io.github.dixonsolutions.godnotic.AndySUStateTest io.github.dixonsolutions.godnotic.AppUpdateStateTest \
	io.github.dixonsolutions.godnotic.UpdateRetryTest io.github.dixonsolutions.godnotic.UpdateBackgroundTest \
	io.github.dixonsolutions.godnotic.CrashReportTest \
	io.github.dixonsolutions.godnotic.ProcessStateTextTest \
	io.github.dixonsolutions.godnotic.PackageReplaceTest \
	io.github.dixonsolutions.godnotic.PollLoopTest \
	io.github.dixonsolutions.godnotic.HostProcessTest \
	io.github.dixonsolutions.godnotic.ReplaceSequenceTest \
	io.github.dixonsolutions.godnotic.SwapTrialTest \
	io.github.dixonsolutions.godnotic.LiveSwapGuardTest \
	io.github.dixonsolutions.godnotic.LiveApplyTest

# Manual update actions must dispatch directly even on metered connections.
python3 - <<'PYTEST'
from pathlib import Path
s = Path('engine/data/xonotic-data.pk3dir/qcsrc/menu/xonotic/touch_uib_settings.qc').read_text()
for action in ('updates.download', 'updates.apply', 'updates.install', 'updates.restart'):
    lines = [line for line in s.splitlines() if 'UIB_Button(' in line and '"' + action + '"' in line]
    assert lines and all('UIB_A(' in line and 'UIB_AC(' not in line for line in lines), action
assert 'Download on mobile data?' not in s
print('Manual update actions dispatch without confirmation.')
# Apply live never restarts: the only restart on the Updates screen is the button that says so.
apply_lines = [line for line in s.splitlines() if '"updates.apply"' in line and 'UIB_Button(' in line]
assert apply_lines and all('Restart' not in line for line in apply_lines), apply_lines
assert 'case "updates.restart": TouchAndySU_request("restart");' in s
restart_lines = [line for line in s.splitlines() if '"updates.restart"' in line and 'UIB_Button(' in line]
assert restart_lines and all('_("Restart now")' in line for line in restart_lines), restart_lines
j = Path('android/app/src/main/java/io/github/dixonsolutions/godnotic/AndySUBridge.java').read_text()
apply_case = j[j.index('case REQUEST_APPLY:'):j.index('case REQUEST_RESTART:')]
import re
code = lambda text: re.sub(r'//[^\n]*|/\*.*?\*/', '', text, flags=re.S)
apply_code = code(apply_case)
for call in ('restartToApply', 'restartNow', 'ReplaceSequence', 'prepareRestart', 'beginReplace'):
    assert call not in apply_code, 'Apply live reaches a restart: ' + call
assert apply_code.count('applyNow()') == 1 and 'shell-update' in apply_code  # the host APK install only
assert 'applyLiveNow()' in apply_code
auto = j[j.index('synchronized void autoApply'):j.index('private String applyLiveNow()')]
auto = code(auto)
for call in ('restartToApply', 'restartNow', 'ReplaceSequence', 'prepareRestart', 'beginReplace', 'applyNow'):
    assert call not in auto, 'the automatic apply restarts: ' + call
print('Apply live and the automatic apply never restart; Restart now is explicit.')
PYTEST
