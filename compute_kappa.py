#!/usr/bin/env python3
"""
Artha Human Evaluation — Inter-Rater Reliability
=================================================
Computes Cohen's kappa between human annotators and the LLM judge.

Usage:
  1. Run artha_eval.py first to get agent responses
  2. Give 6 responses to 2 human annotators with the scoring rubric
  3. Fill in their scores in HUMAN_SCORES below
  4. Run this script: python compute_kappa.py

The 6 responses should be stratified: 1 per query category,
from the most challenging archetypes (paycheck, gig_worker, recent_grad).
"""

import json
import os
import sys

# ── FILL THESE IN ──────────────────────────────────────────────
# After getting agent responses from artha_eval.py, have 2 human
# annotators score these 6 responses on each dimension (1-5).
# Each annotator scores independently without seeing the other's scores.

HUMAN_SCORES = {
    "annotator_1": {
        # Query 1: spending_summary (pick from paycheck_to_paycheck)
        "paycheck_spending_summary": {
            "data_accuracy": 0,    # FILL: 1-5
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        # Query 2: financial_health (pick from gig_worker)
        "gig_worker_financial_health": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        # Query 3: goal_tracking (pick from recent_grad)
        "recent_grad_goal_tracking": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        # Query 4: anomaly_detection (pick from overspender)
        "overspender_anomaly_detection": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        # Query 5: subscription_audit (pick from high_earner)
        "high_earner_subscription_audit": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        # Query 6: behavioral_analysis (pick from high_debt)
        "high_debt_behavioral_analysis": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
    },
    "annotator_2": {
        "paycheck_spending_summary": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        "gig_worker_financial_health": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        "recent_grad_goal_tracking": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        "overspender_anomaly_detection": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        "high_earner_subscription_audit": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
        "high_debt_behavioral_analysis": {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
        },
    },
}


def compute_cohens_kappa(ratings1, ratings2):
    """
    Compute Cohen's kappa for ordinal ratings.
    Converts 1-5 scores to binary pass/fail (>= 3 = pass)
    to match the paper's pass/fail evaluation framework.
    """
    assert len(ratings1) == len(ratings2), "Rating lists must be same length"
    n = len(ratings1)

    if n == 0:
        return 0.0

    # Count agreements and category frequencies
    agree = sum(1 for a, b in zip(ratings1, ratings2) if a == b)
    p_o = agree / n  # observed agreement

    # Expected agreement by chance
    cats = sorted(set(ratings1 + ratings2))
    p_e = 0
    for c in cats:
        p1 = sum(1 for r in ratings1 if r == c) / n
        p2 = sum(1 for r in ratings2 if r == c) / n
        p_e += p1 * p2

    if p_e == 1.0:
        return 1.0

    kappa = (p_o - p_e) / (1 - p_e)
    return round(kappa, 3)


def interpret_kappa(k):
    """Landis & Koch 1977 interpretation scale."""
    if k < 0:
        return "Poor"
    elif k < 0.20:
        return "Slight"
    elif k < 0.40:
        return "Fair"
    elif k < 0.60:
        return "Moderate"
    elif k < 0.80:
        return "Substantial"
    else:
        return "Almost Perfect"


def main():
    # Check if scores have been filled in
    sample = HUMAN_SCORES["annotator_1"]
    first_query = list(sample.values())[0]
    if all(v == 0 for v in first_query.values()):
        print("ERROR: Human scores have not been filled in yet.")
        print()
        print("Instructions:")
        print("1. Run artha_eval.py to get 60 agent responses")
        print("2. Pick 6 responses (1 per category, from hard archetypes)")
        print("3. Give the SCORING_RUBRIC.md to 2 human annotators")
        print("4. Have them score each response on 4 dimensions (1-5)")
        print("5. Enter their scores in HUMAN_SCORES at the top of this file")
        print("6. Run this script again")
        sys.exit(1)

    # Load LLM judge scores if available
    judge_scores = {}
    results_dir = "eval_results"
    if os.path.exists(results_dir):
        # Find the most recent condition A run
        a_files = sorted([
            f for f in os.listdir(results_dir)
            if f.startswith("run_A_") and f.endswith(".json")
        ])
        if a_files:
            with open(os.path.join(results_dir, a_files[-1])) as f:
                data = json.load(f)
            for r in data.get("results", []):
                judge_scores[r["query_id"]] = r["scores"]

    # Extract ratings for kappa computation
    dims = ["data_accuracy", "insight_quality", "actionability", "completeness"]

    a1 = HUMAN_SCORES["annotator_1"]
    a2 = HUMAN_SCORES["annotator_2"]

    queries = list(a1.keys())

    print("=" * 60)
    print("  INTER-RATER RELIABILITY ANALYSIS")
    print("=" * 60)

    # Human-Human agreement
    print("\n  Human-Human Agreement (Annotator 1 vs Annotator 2)")
    print("  " + "-" * 50)

    for dim in dims:
        r1 = [a1[q][dim] for q in queries]
        r2 = [a2[q][dim] for q in queries]
        k = compute_cohens_kappa(r1, r2)
        interp = interpret_kappa(k)
        print(f"    {dim:<20s}: κ = {k:.3f} ({interp})")

    # Overall (flatten all dimensions)
    all_r1 = []
    all_r2 = []
    for q in queries:
        for dim in dims:
            all_r1.append(a1[q][dim])
            all_r2.append(a2[q][dim])

    k_overall = compute_cohens_kappa(all_r1, all_r2)
    print(f"\n    Overall: κ = {k_overall:.3f} ({interpret_kappa(k_overall)})")

    # Human-Judge agreement (if judge scores available)
    if judge_scores:
        print("\n  Human-Judge Agreement (avg of annotators vs LLM judge)")
        print("  " + "-" * 50)

        # Map query names to judge query IDs
        query_to_judge_id = {
            "paycheck_spending_summary": "paycheck_to_paycheck_spending_summary",
            "gig_worker_financial_health": "gig_worker_financial_health",
            "recent_grad_goal_tracking": "recent_grad_goal_tracking",
            "overspender_anomaly_detection": "overspender_anomaly_detection",
            "high_earner_subscription_audit": "high_earner_subscription_audit",
            "high_debt_behavioral_analysis": "high_debt_behavioral_analysis",
        }

        for dim in dims:
            human_avg = []
            judge_vals = []
            for q in queries:
                jid = query_to_judge_id.get(q, "")
                if jid in judge_scores:
                    h = round((a1[q][dim] + a2[q][dim]) / 2)
                    j = judge_scores[jid].get(dim, 0)
                    human_avg.append(h)
                    judge_vals.append(j)

            if human_avg:
                k = compute_cohens_kappa(human_avg, judge_vals)
                print(f"    {dim:<20s}: κ = {k:.3f} ({interpret_kappa(k)})")

        all_h = []
        all_j = []
        for q in queries:
            jid = query_to_judge_id.get(q, "")
            if jid in judge_scores:
                for dim in dims:
                    all_h.append(round((a1[q][dim] + a2[q][dim]) / 2))
                    all_j.append(judge_scores[jid].get(dim, 0))

        if all_h:
            k_hj = compute_cohens_kappa(all_h, all_j)
            print(f"\n    Overall Human-Judge: κ = {k_hj:.3f} ({interpret_kappa(k_hj)})")
            print(f"\n    → Use this value in the paper: κ = {k_hj:.2f}")
    else:
        print("\n  LLM judge scores not found — run artha_eval.py first")
        print(f"  Human-Human κ = {k_overall:.2f} → report this in the paper")

    # Save
    output = {
        "human_human_kappa": k_overall,
        "interpretation": interpret_kappa(k_overall),
        "per_dimension": {
            dim: compute_cohens_kappa(
                [a1[q][dim] for q in queries],
                [a2[q][dim] for q in queries]
            )
            for dim in dims
        },
    }

    out_path = os.path.join(results_dir if os.path.exists(results_dir) else ".", "kappa_results.json")
    with open(out_path, "w") as f:
        json.dump(output, f, indent=2)
    print(f"\n  Results saved to {out_path}")
    print("=" * 60)


if __name__ == "__main__":
    main()
