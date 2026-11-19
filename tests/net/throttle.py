#!/usr/bin/env python3
"""Run a process at a fraction of its speed: SIGCONT for --run ms, SIGSTOP for
--stop ms, until --until-file exists (or the process is gone, or --seconds).

A slow tablet loading a map is the model: wall-clock time passes at the
normal rate while the process gets through its work several times slower.
The process is always left running (SIGCONT) on exit.
"""
import argparse
import os
import signal
import time

p = argparse.ArgumentParser()
p.add_argument('pid', type=int)
p.add_argument('--run', type=float, default=50, help='ms running per cycle')
p.add_argument('--stop', type=float, default=450, help='ms stopped per cycle')
p.add_argument('--seconds', type=float, default=600)
p.add_argument('--until-file', default='')
a = p.parse_args()
end = time.monotonic() + a.seconds
try:
    while time.monotonic() < end and not (a.until_file and os.path.exists(a.until_file)):
        os.kill(a.pid, signal.SIGCONT)
        time.sleep(a.run / 1000)
        os.kill(a.pid, signal.SIGSTOP)
        time.sleep(a.stop / 1000)
except ProcessLookupError:
    pass
finally:
    try:
        os.kill(a.pid, signal.SIGCONT)
    except ProcessLookupError:
        pass
