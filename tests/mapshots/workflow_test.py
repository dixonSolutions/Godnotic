"""The mapshots workflow publishes only by hand, only to a Godnotic-data release, never touches a branch; the
harvester's own logic passes; MapShots.sources points at the release it fills.

  python3 -I tests/mapshots/workflow_test.py
"""
import os, re, subprocess, sys
import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
wf = yaml.safe_load(open(os.path.join(ROOT, ".github", "workflows", "mapshots.yml")))
text = open(os.path.join(ROOT, ".github", "workflows", "mapshots.yml")).read()
fails = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what)
    if not ok:
        fails.append(what)


on = wf.get(True, wf.get("on"))  # YAML reads a bare "on" as True
check(set(on) == {"workflow_dispatch"}, "runs by hand only (no schedule, no push trigger): %s" % sorted(on))
check(wf["permissions"] == {"contents": "read"}, "the workflow itself can only read the repository")
check("git push" not in text and "git commit" not in text and "checkout -b" not in text, "it never pushes or commits a branch (main included)")
check("gh release upload mapshots" in text and "--repo dixonSolutions/Godnotic-data" in text, "pictures go to a release of Godnotic-data")
check("mapshots-harvest.py --self-test" in text, "the harvester's self-test runs first")
kt = open(os.path.join(ROOT, "android/app/src/main/kotlin/io/github/dixonsolutions/godnotic/fe/MapShots.kt")).read()
check("Godnotic-data/releases/download/mapshots/%s.jpg" in kt, "MapShots.sources reads the release this workflow fills")
check(os.path.getsize(os.path.join(ROOT, "scripts", "mapshots-stock.txt")) > 100, "the stock maps list exists")
r = subprocess.run([sys.executable, "-I", os.path.join(ROOT, "scripts", "mapshots-harvest.py"), "--self-test"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
check(r.returncode == 0 and "PASS" in r.stdout, "harvester self-test: " + r.stdout.strip().splitlines()[-1])
sys.exit(1 if fails else 0)
