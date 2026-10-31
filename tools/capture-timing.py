#!/usr/bin/env python3
"""Per-branch frame timings of recorded capture sessions (camera plan, step 0), from the `timing.tsv` debug builds
write next to `live.tsv`. Median and p95 per session, then over all of them. Sessions recorded before step 0 have no
timing.tsv and are skipped.
  tools/capture-timing.py                      every session in test-media/live-dumps/phone
  tools/capture-timing.py <session dir>...     just those
"""
import csv, statistics, subprocess, sys
from pathlib import Path

COLUMNS = ["since_last", "copy", "gray", "ocr", "scan", "done", "session"]


def pct(values, p):
    v = sorted(values)
    return v[min(len(v) - 1, round(p / 100 * (len(v) - 1)))] if v else None


def rows(path):
    with open(path) as f:
        return [{k: int(r[k]) for k in COLUMNS if r.get(k)} for r in csv.DictReader(f, delimiter="\t")]


def line(name, data):
    cells = []
    for c in COLUMNS:
        v = [r[c] for r in data if c in r]
        cells.append(f"{statistics.median(v):5.0f}/{pct(v, 95):<5}" if v else f"{'-':>11}")
    return f"{name[-24:]:<24}{len(data):>6}  " + " ".join(cells)


def main():
    if len(sys.argv) > 1:
        dirs = [Path(a) for a in sys.argv[1:]]
    else:
        common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], capture_output=True,
                                text=True, check=True, cwd=Path(__file__).parent).stdout.strip()
        dirs = sorted((Path(common).parent / "test-media/live-dumps/phone").glob("*/"))
    sessions = [(d.name, rows(d / "timing.tsv")) for d in dirs if (d / "timing.tsv").exists()]
    if not sessions:
        print("no session has a timing.tsv yet (record some with a debug build that has step 0)")
        return
    print(f"{'session':<24}{'frames':>6}  " + " ".join(f"{c:>11}" for c in COLUMNS))
    print(f"{'':<30}  (median/p95 ms; copy..done from the frame's start; since_last = the period)")
    for name, data in sessions:
        print(line(name, data))
    print(line("all", [r for _, data in sessions for r in data]))


if __name__ == "__main__":
    main()
