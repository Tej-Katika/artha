#!/usr/bin/env python3
"""
Artha Eval Harness v3 — Production LLM-as-Judge Evaluation
============================================================
Runs 60 benchmark queries (6 categories × 10 archetypes) against
the Artha Spring Boot API, then scores each response using a
held-out Claude Sonnet instance as the judge.

Produces:
  - eval_results/run_YYYYMMDD_HHMMSS.json  (raw machine-readable)
  - eval_results/run_YYYYMMDD_HHMMSS.txt   (human-readable report)
  - eval_results/summary.json              (paper-ready numbers)

Requirements:
  pip install anthropic requests

Usage:
  # Full run (60 queries, ~$15 in API costs)
  python artha_eval.py --api-url http://localhost:8080/api/agent/chat

  # Quick test (6 queries, 1 per category)
  python artha_eval.py --quick

  # Run specific ablation condition
  python artha_eval.py --condition A   # full system
  python artha_eval.py --condition D   # raw baseline

  # Run all 4 ablation conditions
  python artha_eval.py --ablation
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

try:
    import anthropic
except ImportError:
    print("ERROR: pip install anthropic")
    sys.exit(1)

try:
    import requests
except ImportError:
    print("ERROR: pip install requests")
    sys.exit(1)

# ── Configuration ──────────────────────────────────────────────

JUDGE_MODEL = "claude-opus-4-7"
RESULTS_DIR = Path("eval_results")

# ── Archetypes and their user IDs ──────────────────────────────
# These must match what generate_artha_data.py inserted into your DB.
# Update the UUIDs to match your actual finwise_users_v2.json or
# the users table in your PostgreSQL database.

ARCHETYPES = {
    "high_earner": {
        "name": "High Earner / Saver",
        "user_ids": []  # Fill from your DB: SELECT id FROM users WHERE archetype = 'high_earner' LIMIT 1
    },
    "overspender": {
        "name": "Lifestyle Overspender",
        "user_ids": []
    },
    "high_debt": {
        "name": "High Debt Load",
        "user_ids": []
    },
    "paycheck_to_paycheck": {
        "name": "Paycheck to Paycheck",
        "user_ids": []
    },
    "freelancer": {
        "name": "Freelancer",
        "user_ids": []
    },
    "retired_fixed": {
        "name": "Retired / Fixed Income",
        "user_ids": []
    },
    "dual_income": {
        "name": "Dual Income / Family",
        "user_ids": []
    },
    "recent_grad": {
        "name": "Recent Graduate",
        "user_ids": []
    },
    "small_biz": {
        "name": "Small Business Owner",
        "user_ids": []
    },
    "gig_worker": {
        "name": "Gig Economy Worker",
        "user_ids": []
    },
}

# ── Benchmark queries (6 categories × 1 per archetype = 60 total) ──

QUERY_CATEGORIES = {
    "spending_summary": {
        "query": "How much did I spend this month? Break it down by category.",
        "tools_expected": ["get_spending_summary", "get_budget_status"],
    },
    "financial_health": {
        "query": "How are my finances looking overall? Am I saving enough?",
        "tools_expected": ["get_financial_health", "get_income_analysis"],
    },
    "goal_tracking": {
        "query": "Am I on track to meet my financial goals?",
        "tools_expected": ["get_goal_progress", "get_budget_status"],
    },
    "anomaly_detection": {
        "query": "Have there been any unusual or suspicious charges recently?",
        "tools_expected": ["get_anomalies", "get_recent_transactions"],
    },
    "subscription_audit": {
        "query": "What recurring charges and subscriptions do I have?",
        "tools_expected": ["get_recurring_bills"],
    },
    "behavioral_analysis": {
        "query": "What are my worst spending habits? Where am I wasting money?",
        "tools_expected": ["get_spending_summary", "get_behavioral_insights"],
    },
}

# ── LLM-as-Judge Prompt ──────────────────────────────────────────

JUDGE_PROMPT = """You are an expert evaluator assessing the quality of a
personal finance AI agent's response. Score the response on four dimensions,
each from 1 (worst) to 5 (best).

**User profile archetype:** {archetype}
**User query:** {query}
**Agent response:** {response}

Score each dimension:

1. DATA_ACCURACY (1-5): Does the response cite specific, correct dollar
   amounts, merchant names, dates, or percentages from the user's actual
   financial data? A response with wrong numbers or no numbers scores 1-2.

2. INSIGHT_QUALITY (1-5): Is the financial reasoning sound and specific
   to this user's situation? Generic advice like "reduce spending" without
   naming which categories scores 1-2. Archetype-specific insights
   (e.g., noting irregular income for a freelancer) score 4-5.

3. ACTIONABILITY (1-5): Does the response provide concrete, prioritized
   next steps the user can take? Vague suggestions score 1-2. Specific
   actions like "cancel Netflix ($15.99/mo)" or "reduce dining out from
   $340 to $200" score 4-5.

4. COMPLETENESS (1-5): Does the response surface all important financial
   signals present in the query context? Missing major patterns (e.g.,
   not mentioning overdraft fees when they exist, or missing half the
   subscriptions) scores 1-2.

Respond with ONLY a JSON object, no other text:
{{
  "data_accuracy": <int 1-5>,
  "insight_quality": <int 1-5>,
  "actionability": <int 1-5>,
  "completeness": <int 1-5>,
  "reasoning": "<one sentence explaining the scores>"
}}"""


def load_user_ids(user_map_path="finwise_users_v2.json"):
    """Load user IDs from the generator's output map."""
    if not os.path.exists(user_map_path):
        print(f"WARNING: {user_map_path} not found.")
        print("You need to either:")
        print("  1. Run generate_artha_data.py --save-map first, or")
        print("  2. Manually fill ARCHETYPES user_ids in this script")
        print("  3. Query your DB: SELECT id, archetype FROM users;")
        return False

    with open(user_map_path) as f:
        user_map = json.load(f)

    # Map archetype names in the generator file to the keys used in ARCHETYPES.
    archetype_key_map = {
        "overspender": "overspender",
        "high_debt": "high_debt",
        "high_earner": "high_earner",
        "recent_grad": "recent_grad",
        "freelancer": "freelancer",
        "retired_fixed": "retired_fixed",
        "dual_income": "dual_income",
        "paycheck_to_paycheck": "paycheck_to_paycheck",
        "small_biz_owner": "small_biz",
        "gig_worker": "gig_worker",
    }

    # finwise_users_v2.json shape:
    #   {"generated_at": ..., "total_users": N,
    #    "archetypes": {"overspender": [{"user_id": "...", ...}, ...], ...}}
    # Fall back to a flat list of {"archetype","id"} entries if someone
    # regenerates the file in that older shape.
    if isinstance(user_map, dict) and "archetypes" in user_map:
        for archetype_name, users in user_map["archetypes"].items():
            arch_key = archetype_key_map.get(archetype_name)
            if not arch_key or arch_key not in ARCHETYPES:
                continue
            for user in users:
                uid = user.get("user_id") or user.get("id")
                if uid:
                    ARCHETYPES[arch_key]["user_ids"].append(uid)
    elif isinstance(user_map, list):
        for entry in user_map:
            arch_key = archetype_key_map.get(entry.get("archetype", ""))
            if arch_key and arch_key in ARCHETYPES:
                uid = entry.get("user_id") or entry.get("id")
                if uid:
                    ARCHETYPES[arch_key]["user_ids"].append(uid)
    else:
        print(f"ERROR: unrecognized user map format in {user_map_path}")
        return False

    loaded = sum(1 for a in ARCHETYPES.values() if a["user_ids"])
    print(f"Loaded user IDs for {loaded}/10 archetypes")
    return loaded == 10


def call_agent(api_url, user_id, message, timeout=120):
    """Call the Artha Spring Boot agent API."""
    try:
        resp = requests.post(
            api_url,
            json={"message": message, "userId": user_id},
            headers={"Content-Type": "application/json"},
            timeout=timeout,
        )
        resp.raise_for_status()
        body = resp.json()

        # Handle different response shapes from Spring Boot
        if isinstance(body, str):
            return body
        if isinstance(body, dict):
            return body.get("response", body.get("message", json.dumps(body)))
        return str(body)

    except requests.exceptions.ConnectionError:
        return "[ERROR] Cannot connect to API at " + api_url
    except requests.exceptions.Timeout:
        return "[ERROR] API timeout after " + str(timeout) + "s"
    except Exception as e:
        return f"[ERROR] {type(e).__name__}: {e}"


def judge_response(client, archetype, query, response):
    """Use Claude as judge to score the agent response."""
    prompt = JUDGE_PROMPT.format(
        archetype=archetype, query=query, response=response
    )

    try:
        msg = client.messages.create(
            model=JUDGE_MODEL,
            max_tokens=300,
            messages=[{"role": "user", "content": prompt}],
        )

        text = msg.content[0].text.strip()
        # Strip markdown code fences if present
        if text.startswith("```"):
            text = text.split("\n", 1)[1].rsplit("```", 1)[0]
        scores = json.loads(text)

        return {
            "data_accuracy": int(scores.get("data_accuracy", 1)),
            "insight_quality": int(scores.get("insight_quality", 1)),
            "actionability": int(scores.get("actionability", 1)),
            "completeness": int(scores.get("completeness", 1)),
            "reasoning": scores.get("reasoning", ""),
            "judge_tokens_in": msg.usage.input_tokens,
            "judge_tokens_out": msg.usage.output_tokens,
        }

    except Exception as e:
        print(f"  Judge error: {e}")
        return {
            "data_accuracy": 0,
            "insight_quality": 0,
            "actionability": 0,
            "completeness": 0,
            "reasoning": f"Judge failed: {e}",
            "judge_tokens_in": 0,
            "judge_tokens_out": 0,
        }


def is_pass(scores):
    """A response passes if all 4 dimensions score >= 3."""
    return all(
        scores.get(d, 0) >= 3
        for d in ["data_accuracy", "insight_quality", "actionability", "completeness"]
    )


def avg_score(scores):
    """Average of the 4 dimension scores."""
    dims = [scores.get(d, 0) for d in
            ["data_accuracy", "insight_quality", "actionability", "completeness"]]
    return round(sum(dims) / len(dims), 2) if dims else 0


def run_eval(api_url, quick=False, condition="A"):
    """Run the full evaluation."""

    api_key = os.environ.get("ANTHROPIC_API_KEY", "")
    if not api_key:
        print("ERROR: Set ANTHROPIC_API_KEY environment variable")
        sys.exit(1)

    client = anthropic.Anthropic(api_key=api_key)

    RESULTS_DIR.mkdir(exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    print("=" * 70)
    print(f"  ARTHA EVAL HARNESS v3")
    print(f"  Condition: {condition}")
    print(f"  API: {api_url}")
    print(f"  Judge: {JUDGE_MODEL}")
    print(f"  Mode: {'quick (6 queries)' if quick else 'full (60 queries)'}")
    print(f"  Time: {datetime.now().isoformat()}")
    print("=" * 70)

    all_results = []
    category_results = {cat: [] for cat in QUERY_CATEGORIES}
    archetype_results = {arch: [] for arch in ARCHETYPES}

    total_agent_tokens = 0
    total_judge_tokens = 0
    total_latency = 0
    query_count = 0

    for arch_key, arch_info in ARCHETYPES.items():
        if not arch_info["user_ids"]:
            print(f"\n  SKIP {arch_info['name']} — no user IDs loaded")
            continue

        user_id = arch_info["user_ids"][0]  # Use first user per archetype
        print(f"\n  === {arch_info['name']} (user: {user_id[:8]}...) ===")

        for cat_key, cat_info in QUERY_CATEGORIES.items():
            query = cat_info["query"]
            query_count += 1

            if quick and query_count > 6:
                break  # Only 6 queries in quick mode

            print(f"    [{query_count:2d}] {cat_key}...", end=" ", flush=True)

            # Rate-limit friendly pacing — keeps sustained agent+judge call
            # volume below Anthropic's burst thresholds over 60-query runs.
            if query_count > 1:
                time.sleep(2.0)

            # Call the agent
            t0 = time.time()
            response = call_agent(api_url, user_id, query)
            latency = round(time.time() - t0, 2)
            total_latency += latency

            if response.startswith("[ERROR]"):
                print(f"AGENT ERROR ({latency}s)")
                scores = {
                    "data_accuracy": 0, "insight_quality": 0,
                    "actionability": 0, "completeness": 0,
                    "reasoning": response,
                }
            else:
                # Judge the response
                scores = judge_response(
                    client, arch_info["name"], query, response
                )
                total_judge_tokens += scores.get("judge_tokens_in", 0) + scores.get("judge_tokens_out", 0)

            passed = is_pass(scores)
            avg = avg_score(scores)

            result = {
                "query_id": f"{arch_key}_{cat_key}",
                "archetype": arch_key,
                "archetype_name": arch_info["name"],
                "category": cat_key,
                "user_id": user_id,
                "query": query,
                "response": response[:2000],  # Truncate for storage
                "response_length": len(response),
                "scores": scores,
                "avg_score": avg,
                "passed": passed,
                "latency_sec": latency,
                "condition": condition,
                "timestamp": datetime.now().isoformat(),
            }

            all_results.append(result)
            category_results[cat_key].append(result)
            archetype_results[arch_key].append(result)

            status = "PASS" if passed else "FAIL"
            print(f"{status} ({avg}/5, {latency}s)")

        if quick and query_count >= 6:
            break

    # ── Compute summary statistics ─────────────────────────────

    print("\n" + "=" * 70)
    print("  RESULTS SUMMARY")
    print("=" * 70)

    # Overall
    total = len(all_results)
    passes = sum(1 for r in all_results if r["passed"])
    overall_pass_rate = round(passes / total * 100, 1) if total else 0
    overall_avg = round(
        sum(r["avg_score"] for r in all_results) / total, 2
    ) if total else 0

    print(f"\n  Overall: {overall_pass_rate}% pass rate, {overall_avg}/5 avg score")
    print(f"  Queries: {total}, Passed: {passes}, Failed: {total - passes}")
    print(f"  Avg latency: {round(total_latency / total, 1)}s per query" if total else "")

    # Per category
    print(f"\n  {'Category':<22s} {'Pass%':>6s} {'Avg':>5s} {'n':>3s}")
    print("  " + "-" * 40)
    cat_summary = {}
    for cat_key, results in category_results.items():
        if not results:
            continue
        n = len(results)
        p = sum(1 for r in results if r["passed"])
        pr = round(p / n * 100, 1)
        avg = round(sum(r["avg_score"] for r in results) / n, 2)

        # Find lowest archetype
        arch_scores = {}
        for r in results:
            arch_scores.setdefault(r["archetype_name"], []).append(r["avg_score"])
        lowest = min(arch_scores, key=lambda k: sum(arch_scores[k]) / len(arch_scores[k]))

        cat_summary[cat_key] = {
            "pass_rate": pr,
            "avg_score": avg,
            "n": n,
            "lowest_archetype": lowest,
        }
        print(f"  {cat_key:<22s} {pr:>5.1f}% {avg:>5.2f} {n:>3d}")

    # Per archetype
    print(f"\n  {'Archetype':<25s} {'Pass%':>6s} {'Avg':>5s}")
    print("  " + "-" * 40)
    arch_summary = {}
    for arch_key, results in archetype_results.items():
        if not results:
            continue
        n = len(results)
        p = sum(1 for r in results if r["passed"])
        pr = round(p / n * 100, 1)
        avg = round(sum(r["avg_score"] for r in results) / n, 2)
        arch_summary[arch_key] = {"pass_rate": pr, "avg_score": avg, "n": n}
        print(f"  {ARCHETYPES[arch_key]['name']:<25s} {pr:>5.1f}% {avg:>5.2f}")

    # ── Save results ───────────────────────────────────────────

    summary = {
        "condition": condition,
        "timestamp": timestamp,
        "total_queries": total,
        "overall_pass_rate": overall_pass_rate,
        "overall_avg_score": overall_avg,
        "avg_latency_sec": round(total_latency / total, 1) if total else 0,
        "total_judge_tokens": total_judge_tokens,
        "category_summary": cat_summary,
        "archetype_summary": arch_summary,
    }

    # Raw results
    raw_path = RESULTS_DIR / f"run_{condition}_{timestamp}.json"
    with open(raw_path, "w") as f:
        json.dump({"summary": summary, "results": all_results}, f, indent=2)

    # Human-readable report
    txt_path = RESULTS_DIR / f"run_{condition}_{timestamp}.txt"
    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(f"Artha Eval Report — Condition {condition}\n")
        f.write(f"Date: {timestamp}\n")
        f.write(f"Overall: {overall_pass_rate}% pass, {overall_avg}/5 avg\n\n")

        for r in all_results:
            status = "PASS" if r["passed"] else "FAIL"
            f.write(f"[{status}] {r['query_id']}\n")
            f.write(f"  Archetype: {r['archetype_name']}\n")
            f.write(f"  Query: {r['query']}\n")
            f.write(f"  Scores: DA={r['scores']['data_accuracy']} "
                    f"IQ={r['scores']['insight_quality']} "
                    f"AC={r['scores']['actionability']} "
                    f"CO={r['scores']['completeness']}\n")
            f.write(f"  Judge: {r['scores']['reasoning']}\n")
            f.write(f"  Latency: {r['latency_sec']}s\n")
            f.write(f"  Response (first 500 chars):\n    {r['response'][:500]}\n")
            f.write("-" * 60 + "\n\n")

    # Summary for paper
    summary_path = RESULTS_DIR / f"summary_{condition}.json"
    with open(summary_path, "w") as f:
        json.dump(summary, f, indent=2)

    print(f"\n  Files saved:")
    print(f"    {raw_path}")
    print(f"    {txt_path}")
    print(f"    {summary_path}")
    print("=" * 70)

    return summary


def run_ablation(api_url):
    """Run all 4 ablation conditions.

    NOTE: You must modify your Spring Boot application between runs
    to enable/disable the enrichment engine and ontology tools.
    See the README for instructions on each condition.
    """
    conditions = ["A", "B", "C", "D"]
    all_summaries = {}

    for cond in conditions:
        print(f"\n{'#' * 70}")
        print(f"  ABLATION CONDITION {cond}")
        print(f"{'#' * 70}")
        print()
        print(f"  Before running, configure your Spring Boot app for condition {cond}:")
        if cond == "A":
            print("    - Enrichment engine: ON")
            print("    - Ontology tools: ON (default)")
        elif cond == "B":
            print("    - Enrichment engine: ON")
            print("    - Ontology tools: OFF (tools query raw transactions)")
        elif cond == "C":
            print("    - Enrichment engine: OFF (skip enrichment at ingestion)")
            print("    - Ontology tools: ON")
        elif cond == "D":
            print("    - Enrichment engine: OFF")
            print("    - Ontology tools: OFF")

        input(f"\n  Press Enter when condition {cond} is configured and the app is running...")

        summary = run_eval(api_url, condition=cond)
        all_summaries[cond] = summary

    # Save combined ablation results
    ablation_path = RESULTS_DIR / "ablation_combined.json"
    with open(ablation_path, "w") as f:
        json.dump(all_summaries, f, indent=2)

    print(f"\n{'=' * 70}")
    print("  ABLATION COMPARISON")
    print(f"{'=' * 70}")
    print(f"\n  {'Condition':<20s} {'Pass Rate':>10s} {'Avg Score':>10s} {'Delta vs D':>10s}")
    print("  " + "-" * 55)
    d_rate = all_summaries.get("D", {}).get("overall_pass_rate", 0)
    for cond in conditions:
        s = all_summaries.get(cond, {})
        rate = s.get("overall_pass_rate", 0)
        avg = s.get("overall_avg_score", 0)
        delta = f"+{rate - d_rate:.1f}" if cond != "D" else "--"
        print(f"  {cond:<20s} {rate:>9.1f}% {avg:>9.2f} {delta:>10s}")

    print(f"\n  Combined results: {ablation_path}")


def main():
    parser = argparse.ArgumentParser(description="Artha Eval Harness v3")
    parser.add_argument(
        "--api-url",
        default="http://localhost:8080/api/agent/chat",
        help="Artha Spring Boot agent API URL",
    )
    parser.add_argument(
        "--quick", action="store_true",
        help="Run only 6 queries (1 per category) for quick testing",
    )
    parser.add_argument(
        "--condition", default="A",
        choices=["A", "B", "C", "D"],
        help="Ablation condition to run",
    )
    parser.add_argument(
        "--ablation", action="store_true",
        help="Run all 4 ablation conditions interactively",
    )
    parser.add_argument(
        "--user-map", default="finwise_users_v2.json",
        help="Path to user ID map from data generator",
    )
    args = parser.parse_args()

    # Load user IDs
    if not load_user_ids(args.user_map):
        print("\nFalling back to manual user ID entry.")
        print("Please update the ARCHETYPES dict in this script with your actual user IDs.")
        print("Query your DB: SELECT id, archetype FROM users GROUP BY archetype, id;")
        sys.exit(1)

    if args.ablation:
        run_ablation(args.api_url)
    else:
        run_eval(args.api_url, quick=args.quick, condition=args.condition)


if __name__ == "__main__":
    main()
