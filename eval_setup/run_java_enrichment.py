#!/usr/bin/env python3
"""
Force re-enrich + subscription-detect the 10 eval users through the
actual Java pipeline (not via SQL). This ensures the numbers reported
in the paper are produced by the system under test.
"""
import json
import sys
import time

import requests

API = "http://localhost:8081/api/enrichment"

EVAL_USERS = [
    ("high_earner",          "aa000000-0000-0000-0000-000000000000"),
    ("paycheck_to_paycheck", "bb000000-0000-0000-0000-000000000000"),
    ("high_debt",            "cc000000-0000-0000-0000-000000000000"),
    ("overspender",          "dd000000-0000-0000-0000-000000000000"),
    ("freelancer",           "ee000000-0000-0000-0000-000000000000"),
    ("retired_fixed",        "ff000000-0000-0000-0000-000000000000"),
    ("dual_income",          "11000000-0000-0000-0000-000000000000"),
    ("recent_grad",          "22000000-0000-0000-0000-000000000000"),
    ("gig_worker",           "33000000-0000-0000-0000-000000000000"),
    ("small_biz_owner",      "44000000-0000-0000-0000-000000000000"),
]


def call(method, path, **kwargs):
    url = API + path
    r = requests.request(method, url, timeout=600, **kwargs)
    r.raise_for_status()
    return r.json()


def main():
    print(f"{'archetype':<22} {'user_id':<38} {'enriched':>8} {'subs':>5} {'time':>7}")
    print("-" * 90)
    total_enriched = 0
    total_subs = 0
    for archetype, uid in EVAL_USERS:
        t0 = time.time()
        try:
            enr = call("POST", f"/user/{uid}/all?force=true")
            sub = call("POST", f"/user/{uid}/subscriptions?force=true")
        except requests.HTTPError as e:
            print(f"{archetype:<22} {uid}  FAILED: {e}")
            continue
        elapsed = time.time() - t0
        e_count = enr.get("enriched", 0)
        s_count = sub.get("detected_count", 0)
        total_enriched += e_count
        total_subs += s_count
        print(f"{archetype:<22} {uid} {e_count:>8} {s_count:>5} {elapsed:>6.1f}s")

    print("-" * 90)
    print(f"{'TOTAL':<61} {total_enriched:>8} {total_subs:>5}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
