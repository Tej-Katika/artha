# -*- coding: utf-8 -*-
"""
FinWise Agent Eval Harness - Phase 6B Benchmark
Usage: python eval_harness.py
"""

import json
import urllib.request
import urllib.error
import datetime
import time
import re

BASE_URL = "http://localhost:8080/api/agent/chat"
DELAY_BETWEEN_CALLS = 20  # seconds - give Spring Boot time to breathe

PROFILES = [
    {"id": "aaaaaaaa-0001-0001-0001-000000000001", "name": "Priya Sharma",          "archetype": "High Earner/Saver"},
    {"id": "bbbbbbbb-0002-0002-0002-000000000002", "name": "Marcus Johnson",         "archetype": "Paycheck to Paycheck"},
    {"id": "cccccccc-0003-0003-0003-000000000003", "name": "Alex Chen",              "archetype": "High Debt"},
    {"id": "dddddddd-0004-0004-0004-000000000004", "name": "Brandon Willis",         "archetype": "Overspender + Anomalies"},
    {"id": "eeeeeeee-0005-0005-0005-000000000005", "name": "Sofia Reyes",            "archetype": "Freelancer"},
    {"id": "ffffffff-0006-0006-0006-000000000006", "name": "Robert & Helen Davis",   "archetype": "Retired/Fixed Income"},
    {"id": "a7a7a7a7-0007-0007-0007-000000000007", "name": "James & Sarah Mitchell", "archetype": "Dual Income"},
    {"id": "a8a8a8a8-0008-0008-0008-000000000008", "name": "Taylor Kim",             "archetype": "Recent Grad/Rebuilding"},
]

BENCHMARK_QUERIES = [
    {
        "id": "Q1",
        "question": "How much did I spend this month?",
        "min_length": 200,
        "must_contain_dollar": True,
        "signals": ["merchant", "spent", "total", "income", "savings"],
    },
    {
        "id": "Q2",
        "question": "How are my finances overall?",
        "min_length": 200,
        "must_contain_dollar": True,
        "signals": ["score", "health", "spending", "income", "recommend"],
    },
    {
        "id": "Q3",
        "question": "Anything suspicious in my transactions?",
        "min_length": 100,
        "must_contain_dollar": True,
        "signals": ["unusual", "transaction", "charge", "normal", "flagg"],
    },
    {
        "id": "Q4",
        "question": "What are my biggest spending categories?",
        "min_length": 150,
        "must_contain_dollar": True,
        "signals": ["categor", "groceri", "dining", "food", "gas", "shopping"],
    },
    {
        "id": "Q5",
        "question": "Am I on track with my savings goals?",
        "min_length": 100,
        "must_contain_dollar": False,
        "signals": ["goal", "saving", "progress", "target", "fund"],
    },
]

def call_agent(user_id, message):
    payload = json.dumps({"message": message, "userId": user_id}).encode("utf-8")
    req = urllib.request.Request(
        BASE_URL,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            return {"ok": True, "response": body.get("response", "")}
    except Exception as e:
        return {"ok": False, "error": str(e), "response": ""}

def score_response(text, query):
    if not text or len(text) < 30:
        return 0.0, "EMPTY"

    score = 0.0
    reasons = []

    # Length check (30 pts)
    if len(text) >= query["min_length"]:
        score += 0.30
        reasons.append("length OK")

    # Dollar amounts present (30 pts)
    has_dollars = bool(re.search(r'\$[\d,]+', text))
    if has_dollars:
        score += 0.30
        reasons.append("has $amounts")
    elif not query["must_contain_dollar"]:
        score += 0.15  # partial credit

    # Signal words (40 pts split across signals)
    text_lower = text.lower()
    pts_each = 0.40 / len(query["signals"])
    for sig in query["signals"]:
        if sig.lower() in text_lower:
            score += pts_each
            reasons.append(f"has '{sig}'")

    # Quality label
    if score >= 0.75:
        quality = "DETAILED"
    elif score >= 0.45:
        quality = "ADEQUATE"
    elif score >= 0.20:
        quality = "BRIEF"
    else:
        quality = "POOR"

    return round(min(score, 1.0), 2), quality

def run_eval():
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    all_results = []
    total = len(PROFILES) * len(BENCHMARK_QUERIES)

    print("=" * 70)
    print("  FinWise Agent Eval Harness - Phase 6B Benchmark")
    print(f"  {len(PROFILES)} profiles x {len(BENCHMARK_QUERIES)} queries = {total} total")
    print(f"  Delay between calls: {DELAY_BETWEEN_CALLS}s")
    print(f"  Est. time: ~{total * DELAY_BETWEEN_CALLS // 60} min")
    print(f"  Started: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)

    for profile in PROFILES:
        print(f"\n[{profile['name']}] ({profile['archetype']})")
        profile_results = []

        for query in BENCHMARK_QUERIES:
            label = query['question'][:50]
            print(f"  {query['id']}: {label}...", end=" ", flush=True)

            start = time.time()
            result = call_agent(profile["id"], query["question"])
            elapsed = round(time.time() - start, 1)

            response_text = result["response"]
            score, quality = score_response(response_text, query)
            status = "PASS" if score >= 0.45 else "FAIL"

            if not result["ok"]:
                status = "ERROR"
                quality = "ERROR"
                score = 0.0
                print(f"[ERROR] {result.get('error','')[:50]}")
            else:
                print(f"[{status}] score={score} quality={quality} len={len(response_text)} {elapsed}s")

            profile_results.append({
                "query_id":    query["id"],
                "question":    query["question"],
                "status":      status,
                "score":       score,
                "quality":     quality,
                "length":      len(response_text),
                "elapsed_sec": elapsed,
                "response":    response_text,
            })

            time.sleep(DELAY_BETWEEN_CALLS)

        avg = round(sum(q["score"] for q in profile_results) / len(profile_results), 2)
        passes = sum(1 for q in profile_results if q["status"] == "PASS")
        all_results.append({
            "profile":    profile,
            "queries":    profile_results,
            "avg_score":  avg,
            "pass_count": passes,
            "pass_rate":  round(passes / len(profile_results), 2),
        })

    # ── Summary ───────────────────────────────────────────────
    overall_avg  = round(sum(p["avg_score"]  for p in all_results) / len(all_results), 2)
    overall_pass = round(sum(p["pass_rate"]  for p in all_results) / len(all_results), 2)

    print("\n" + "=" * 70)
    print("  SUMMARY")
    print("=" * 70)
    print(f"  Overall avg score : {overall_avg}")
    print(f"  Overall pass rate : {round(overall_pass * 100)}%")
    print()
    for p in all_results:
        bar = "#" * int(p["avg_score"] * 20)
        print(f"  {p['profile']['name']:<28} score={p['avg_score']}  "
              f"pass={p['pass_count']}/{len(BENCHMARK_QUERIES)}  |{bar}")

    # ── Save reports ──────────────────────────────────────────
    json_path = f"eval_report_{timestamp}.json"
    txt_path  = f"eval_report_{timestamp}.txt"

    report = {
        "timestamp":     timestamp,
        "overall_score": overall_avg,
        "overall_pass":  overall_pass,
        "profiles":      all_results,
    }
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(f"FinWise Eval Report - {timestamp}\n")
        f.write(f"Overall Score: {overall_avg} | Pass Rate: {round(overall_pass*100)}%\n\n")
        for p in all_results:
            f.write(f"\n{'='*60}\n")
            f.write(f"{p['profile']['name']} ({p['profile']['archetype']})\n")
            f.write(f"Avg Score: {p['avg_score']} | Pass: {p['pass_count']}/{len(BENCHMARK_QUERIES)}\n")
            f.write("="*60 + "\n")
            for q in p["queries"]:
                f.write(f"\n[{q['status']}] {q['query_id']}: {q['question']}\n")
                f.write(f"Score: {q['score']} | Quality: {q['quality']} | "
                        f"Length: {q['length']} | {q['elapsed_sec']}s\n")
                f.write(f"\nResponse:\n{q['response']}\n")
                f.write("-" * 40 + "\n")

    print(f"\n  Saved: {json_path}")
    print(f"  Saved: {txt_path}")
    print("=" * 70)

if __name__ == "__main__":
    run_eval()