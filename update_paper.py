#!/usr/bin/env python3
"""
Artha Paper Updater — Replaces projected numbers with real eval results
=======================================================================
Run this AFTER artha_eval.py has produced results for all 4 conditions.

Usage:
  python update_paper.py

It reads:
  - eval_results/summary_A.json
  - eval_results/summary_B.json (optional, for ablation)
  - eval_results/summary_C.json (optional)
  - eval_results/summary_D.json (optional)
  - eval_results/kappa_results.json (optional)

And updates artha.tex with the real numbers.
"""

import json
import os
import re
import sys

TEX_FILE = "artha.tex"
RESULTS_DIR = "eval_results"


def load_summary(condition):
    path = os.path.join(RESULTS_DIR, f"summary_{condition}.json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return None


def main():
    if not os.path.exists(TEX_FILE):
        print(f"ERROR: {TEX_FILE} not found. Run from the artha_paper directory.")
        sys.exit(1)

    with open(TEX_FILE) as f:
        tex = f.read()

    # Load condition A (required)
    a = load_summary("A")
    if not a:
        print("ERROR: eval_results/summary_A.json not found.")
        print("Run: python artha_eval.py --condition A")
        sys.exit(1)

    b = load_summary("B")
    c = load_summary("C")
    d = load_summary("D")
    kappa_path = os.path.join(RESULTS_DIR, "kappa_results.json")
    kappa = None
    if os.path.exists(kappa_path):
        with open(kappa_path) as f:
            kappa = json.load(f)

    print("=" * 60)
    print("  ARTHA PAPER UPDATER")
    print("=" * 60)

    overall_pass = a["overall_pass_rate"]
    overall_avg = a["overall_avg_score"]
    print(f"\n  Condition A results: {overall_pass}% pass, {overall_avg}/5 avg")

    # ── Update Abstract numbers ──────────────────────────────
    # Current: Artha achieves \textbf{88.7\%}
    old_abstract_pass = r"Artha achieves \textbf{88.7\%}"
    new_abstract_pass = f"Artha achieves \\textbf{{{overall_pass}\\%}}"
    if old_abstract_pass in tex:
        tex = tex.replace(old_abstract_pass, new_abstract_pass)
        print(f"  Updated abstract pass rate: 88.7% -> {overall_pass}%")

    if d:
        delta_ad = round(a["overall_pass_rate"] - d["overall_pass_rate"], 1)
        old_delta = r"\textbf{36.7 percentage-point}"
        new_delta = f"\\textbf{{{delta_ad} percentage-point}}"
        tex = tex.replace(old_delta, new_delta)
        print(f"  Updated ablation delta: 36.7 -> {delta_ad}")

    # ── Update Results Table (Table 4) ────────────────────────
    cat_map = {
        "spending_summary": "Spending summary",
        "financial_health": "Financial health",
        "goal_tracking": "Goal tracking",
        "anomaly_detection": "Anomaly detection",
        "subscription_audit": "Subscription audit",
        "behavioral_analysis": "Behavioral analysis",
    }

    cat_summary = a.get("category_summary", {})
    print(f"\n  Category results from eval:")
    for cat_key, cat_name in cat_map.items():
        cs = cat_summary.get(cat_key, {})
        pr = cs.get("pass_rate", 0)
        avg = cs.get("avg_score", 0)
        lowest = cs.get("lowest_archetype", "N/A")
        print(f"    {cat_name:<22s}: {pr:>5.1f}% pass, {avg:.1f}/5, lowest: {lowest}")

    # ── Update Ablation Table (Table 5) ───────────────────────
    if all([b, c, d]):
        print(f"\n  Ablation results:")
        for cond_name, s in [("A", a), ("B", b), ("C", c), ("D", d)]:
            print(f"    Condition {cond_name}: {s['overall_pass_rate']}%")

    # ── Update Kappa ──────────────────────────────────────────
    if kappa:
        k = kappa.get("human_human_kappa", 0)
        print(f"\n  Cohen's kappa: {k}")

    # ── Write updated file ────────────────────────────────────
    backup = TEX_FILE + ".bak"
    os.rename(TEX_FILE, backup)
    with open(TEX_FILE, "w") as f:
        f.write(tex)

    print(f"\n  Backup saved: {backup}")
    print(f"  Updated: {TEX_FILE}")
    print(f"\n  NOTE: This script only updates the abstract pass rate and delta.")
    print(f"  You must manually update Tables 4, 5, 6 and the narrative")
    print(f"  paragraphs with your actual numbers from the eval output.")
    print(f"  The raw numbers are in eval_results/summary_*.json")
    print("=" * 60)


if __name__ == "__main__":
    main()
