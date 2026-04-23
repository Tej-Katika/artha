# -*- coding: utf-8 -*-
"""
FinWise Eval Harness v2 - LLM-as-Judge Scoring
===============================================
Tests the FinWise agent across all 10 archetypes using Claude as judge.
Each response is scored 1-5 on a structured rubric.

Usage:
  python eval_harness_v2.py                          # run all benchmarks
  python eval_harness_v2.py --quick                  # 2 queries per archetype
  python eval_harness_v2.py --archetype high_earner  # one archetype only
  python eval_harness_v2.py --save report.json       # save full results
"""

import argparse
import json
import os
import sys
import time
try:
    from urllib.request import urlopen, Request
    from urllib.error import HTTPError
except ImportError:
    from urllib2 import urlopen, Request, HTTPError
from datetime import date

# -- Config ---------------------------------------------------------------------

AGENT_URL        = "http://localhost:8081/api/agent/chat"
ANTHROPIC_KEY    = os.environ.get("ANTHROPIC_API_KEY", "")
JUDGE_MODEL      = "claude-haiku-4-5-20251001"
JUDGE_MAX_TOKENS = 600
DELAY_SECONDS    = 5   # between agent calls to avoid hammering the API

# One representative user per archetype (index 0 of each archetype)
ARCHETYPE_USER_IDS = {
    "overspender":          "dd000000-0000-0000-0000-000000000000",
    "high_debt":            "cc000000-0000-0000-0000-000000000000",
    "high_earner":          "aa000000-0000-0000-0000-000000000000",
    "recent_grad":          "22000000-0000-0000-0000-000000000000",
    "freelancer":           "ee000000-0000-0000-0000-000000000000",
    "retired_fixed":        "ff000000-0000-0000-0000-000000000000",
    "dual_income":          "11000000-0000-0000-0000-000000000000",
    "paycheck_to_paycheck": "bb000000-0000-0000-0000-000000000000",
    "small_biz_owner":      "44000000-0000-0000-0000-000000000000",
    "gig_worker":           "33000000-0000-0000-0000-000000000000",
}

# -- Benchmark Queries ----------------------------------------------------------
# 5 queries per archetype. Each has a focus used by the judge.

BENCHMARKS = {
    "overspender": [
        {"q": "How much did I spend last month?",
         "focus": "Must return specific dollar amount from real data. Not vague."},
        {"q": "What are my biggest spending categories?",
         "focus": "Should list categories with amounts. Identify overspending pattern."},
        {"q": "Am I spending more than I earn?",
         "focus": "Must compare income vs spend with actual numbers. Give deficit/surplus."},
        {"q": "Where should I cut back first?",
         "focus": "Must name specific category or merchant with amount. Actionable."},
        {"q": "How does my spending compare to a typical budget?",
         "focus": "Should use 50/30/20 or similar benchmark. Reference actual numbers."},
    ],
    "high_debt": [
        {"q": "How much am I paying toward debt each month?",
         "focus": "Should identify loan payment category total with specific amount."},
        {"q": "What percentage of my income goes to debt?",
         "focus": "Must calculate: debt payments / income * 100. Give actual percentage."},
        {"q": "Am I making progress on my debt?",
         "focus": "Should show trend over multiple months if possible. Specific amounts."},
        {"q": "What should I prioritize - saving or paying off debt?",
         "focus": "Should factor in actual savings rate and debt load. Not generic advice."},
        {"q": "How long until I'm debt free at my current rate?",
         "focus": "Should estimate based on monthly debt payments. May need assumptions."},
    ],
    "high_earner": [
        {"q": "What's my savings rate?",
         "focus": "Must calculate (income - spend) / income * 100 with real numbers."},
        {"q": "How much am I investing each month?",
         "focus": "Should identify investment category transactions with amounts."},
        {"q": "Am I over budget in any area?",
         "focus": "Should use 50/30/20 rule against actual income. Identify over-budget buckets."},
        {"q": "What are my biggest expenses?",
         "focus": "Top categories/merchants by amount. Specific dollar figures."},
        {"q": "How has my income trended over the last 3 months?",
         "focus": "Should call income_vs_spend tool. Month by month comparison."},
    ],
    "recent_grad": [
        {"q": "How much am I paying in student loans?",
         "focus": "Must find LOAN_PAYMENT category. Specific monthly amount."},
        {"q": "Can I afford to save while paying off my loans?",
         "focus": "Compare income to loan payments + expenses. Give concrete savings capacity."},
        {"q": "What's eating up most of my paycheck?",
         "focus": "Top spending categories with amounts as % of income."},
        {"q": "Am I living within my means?",
         "focus": "Income vs total spend comparison with surplus/deficit amount."},
        {"q": "What small changes would make the biggest difference for me?",
         "focus": "Should identify highest-impact category to cut. Specific amounts."},
    ],
    "freelancer": [
        {"q": "How irregular is my income month to month?",
         "focus": "Should call income_vs_spend for multiple months. Show variance."},
        {"q": "What are my business expenses?",
         "focus": "Should identify BUSINESS_EXPENSE category total. List merchants."},
        {"q": "How much should I set aside for taxes?",
         "focus": "Estimate ~25-30% of net profit. Reference actual income numbers."},
        {"q": "What's my worst income month this year?",
         "focus": "Should find lowest income month from data. Specific month and amount."},
        {"q": "Am I spending consistently or does it spike with income?",
         "focus": "Correlate income and spending months. Pattern analysis."},
    ],
    "retired_fixed": [
        {"q": "Is my spending sustainable on my fixed income?",
         "focus": "Compare total spend to total income. Surplus or deficit with amounts."},
        {"q": "How much am I spending on healthcare?",
         "focus": "Must identify HEALTHCARE category total. Specific amount."},
        {"q": "What are my essential vs discretionary expenses?",
         "focus": "Should bucket into needs vs wants. Amounts for each."},
        {"q": "Am I spending more than last month?",
         "focus": "Month-over-month comparison. Specific dollar difference."},
        {"q": "How long could my savings last at my current spend rate?",
         "focus": "Should reference balance if available, or monthly spend rate."},
    ],
    "dual_income": [
        {"q": "What's our household savings rate?",
         "focus": "Calculate (income - spend) / income. Specific percentage."},
        {"q": "How much are we spending on childcare?",
         "focus": "Must find CHILDCARE category. Specific monthly amount."},
        {"q": "Are we spending too much on housing?",
         "focus": "Housing as % of income vs 28-30% guideline. Use actual numbers."},
        {"q": "What's our biggest discretionary expense?",
         "focus": "Top wants-bucket category with amount."},
        {"q": "How does this month compare to last month?",
         "focus": "Month over month income and spending comparison with dollar delta."},
    ],
    "paycheck_to_paycheck": [
        {"q": "Why am I always broke by the end of the month?",
         "focus": "Should show income vs spend deficit or near-zero surplus. Identify top drain."},
        {"q": "How much do I have left after essential bills?",
         "focus": "Income minus needs-bucket total. Specific remaining amount."},
        {"q": "What's one thing I could do to have more money?",
         "focus": "Identify single highest-impact cut. Specific category and amount saved."},
        {"q": "Am I spending more than I make?",
         "focus": "Direct income vs spend comparison. Specific deficit if any."},
        {"q": "What are my fixed monthly costs?",
         "focus": "Identify recurring DEBIT transactions. Total with breakdown."},
    ],
    "small_biz_owner": [
        {"q": "What are my total business expenses this month?",
         "focus": "BUSINESS_EXPENSE + MARKETING categories. Specific total."},
        {"q": "How does my personal spending compare to business spending?",
         "focus": "Split transactions into business vs personal buckets with amounts."},
        {"q": "Am I reinvesting enough in the business?",
         "focus": "Business expenses as % of income. Context on healthy reinvestment rate."},
        {"q": "What's my take-home after business costs?",
         "focus": "Income minus business expenses. Specific net amount."},
        {"q": "Which business expense is growing fastest?",
         "focus": "Month over month business category trend. Identify growing item."},
    ],
    "gig_worker": [
        {"q": "How much am I spending on my car for work?",
         "focus": "TRANSPORTATION category total. Specific amount. Key for gig workers."},
        {"q": "Is driving worth it financially?",
         "focus": "Compare vehicle costs to income. Give net after transport costs."},
        {"q": "How variable is my monthly income?",
         "focus": "Income across multiple months. Show range and average."},
        {"q": "What months did I earn the most?",
         "focus": "Find peak income months from data. Specific months and amounts."},
        {"q": "Am I setting aside money for slow periods?",
         "focus": "Savings rate assessment. Identify if surplus exists to save."},
    ],
}

# -- HTTP helpers ---------------------------------------------------------------

def http_post(url, payload, headers=None):
    data = json.dumps(payload).encode("utf-8")
    req  = Request(url, data=data)
    req.add_header("Content-Type", "application/json")
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    try:
        resp = urlopen(req, timeout=60)
        try:
            return json.loads(resp.read().decode("utf-8"))
        finally:
            resp.close()
    except HTTPError as e:
        return {"error": "HTTP %d: %s" % (e.code, e.read().decode("utf-8")[:200])}
    except Exception as e:
        return {"error": str(e)}

# -- Agent call -----------------------------------------------------------------

def call_agent(user_id, message, retries=3):
    for attempt in range(retries):
        result = http_post(AGENT_URL, {"userId": user_id, "message": message})
        response = result.get("response", result.get("error", "NO_RESPONSE"))
        # Retry on transient connection errors
        if "trouble connecting" in response.lower() or "error" in str(result.get("error","")).lower():
            wait = 8 * (attempt + 1)
            print("    [retry %d/%d] agent error, waiting %ds..." % (attempt+1, retries, wait))
            time.sleep(wait)
        else:
            return response
    return response  # return last attempt regardless

# -- LLM Judge -----------------------------------------------------------------

JUDGE_SYSTEM = (
    "You are an expert evaluator for a personal finance AI assistant. "
    "Score responses strictly and fairly on a 1-5 scale."
)

JUDGE_PROMPT_TEMPLATE = """Evaluate this financial AI response.

USER QUESTION: {question}

EVALUATION FOCUS: {focus}

AI RESPONSE:
{response}

Score on these criteria (1-5 each):
1. DATA_ACCURACY - Did it use specific numbers from real data? (5=very specific amounts, 1=vague/guessed)
2. RELEVANCE - Did it directly answer the question? (5=directly answered, 1=off-topic)
3. ACTIONABILITY - Did it give useful, specific advice? (5=clear next steps with amounts, 1=generic platitudes)
4. FOCUS - Did it address the evaluation focus criteria? (5=fully addressed, 1=ignored)

Respond ONLY with valid JSON, no other text:
{{"scores":{{"data_accuracy":N,"relevance":N,"actionability":N,"focus":N}},"overall":N,"pass":true/false,"reason":"one sentence"}}

Pass = overall >= 3.5. Overall = average of the 4 scores."""

def judge_response(question, focus, response):
    if not ANTHROPIC_KEY:
        return keyword_fallback(response)

    prompt = JUDGE_PROMPT_TEMPLATE.format(
        question=question,
        focus=focus,
        response=response[:1500]
    )

    result = http_post(
        "https://api.anthropic.com/v1/messages",
        {
            "model":      JUDGE_MODEL,
            "max_tokens": JUDGE_MAX_TOKENS,
            "system":     JUDGE_SYSTEM,
            "messages":   [{"role": "user", "content": prompt}]
        },
        headers={
            "x-api-key":         ANTHROPIC_KEY,
            "anthropic-version": "2023-06-01",
        }
    )

    if "error" in result:
        return keyword_fallback(response)

    try:
        text = result["content"][0]["text"].strip()
        # Strip markdown fences if present
        if text.startswith("```"):
            text = text.split("```")[1]
            if text.startswith("json"):
                text = text[4:]
        return json.loads(text.strip())
    except Exception:
        return keyword_fallback(response)

def keyword_fallback(response):
    """Simple keyword scorer when no API key is available."""
    resp_lower = response.lower()
    has_dollar  = "$" in response
    has_pct     = "%" in response
    has_numbers = any(c.isdigit() for c in response)
    is_error    = "trouble" in resp_lower or "error" in resp_lower or "cannot" in resp_lower

    if is_error:
        score = 1.0
    elif has_dollar and has_numbers:
        score = 3.5
    elif has_numbers:
        score = 2.5
    else:
        score = 1.5

    return {
        "scores": {"data_accuracy": score, "relevance": score,
                   "actionability": score, "focus": score},
        "overall": score,
        "pass":    score >= 3.5,
        "reason":  "keyword fallback (no API key)"
    }

# -- Run eval -------------------------------------------------------------------

def run_eval(arch_list, quick=False, save_path=None):
    results = {
        "run_date":   date.today().isoformat(),
        "mode":       "quick" if quick else "full",
        "archetypes": {},
        "overall":    {}
    }

    all_scores  = []
    all_pass    = []
    total_tests = 0

    print("\nFinWise Eval Harness v2")
    print("=" * 60)
    if not ANTHROPIC_KEY:
        print("WARNING: ANTHROPIC_API_KEY not set - using keyword fallback scorer")
        print("  Set key for accurate LLM-as-judge scoring")
    print()

    for arch_name in arch_list:
        user_id   = ARCHETYPE_USER_IDS.get(arch_name)
        benchmarks = BENCHMARKS.get(arch_name, [])

        if quick:
            benchmarks = benchmarks[:2]

        if not user_id:
            print("[SKIP] %s - no user ID configured" % arch_name)
            continue

        print("[%s]" % arch_name.upper().replace("_", " "))
        arch_scores = []
        arch_pass   = []
        arch_details = []

        for i, bench in enumerate(benchmarks):
            question = bench["q"]
            focus    = bench["focus"]

            # Call agent
            response = call_agent(user_id, question)
            time.sleep(DELAY_SECONDS)

            # Judge
            verdict = judge_response(question, focus, response)
            overall = verdict.get("overall", 1.0)
            passed  = verdict.get("pass", False)
            reason  = verdict.get("reason", "")

            arch_scores.append(overall)
            arch_pass.append(passed)
            all_scores.append(overall)
            all_pass.append(passed)
            total_tests += 1

            status = "PASS" if passed else "FAIL"
            print("  [%s] %.1f/5 Q%d: %s" % (status, overall, i + 1, question[:55]))
            if not passed:
                print("       Reason: %s" % reason)
                print("       Response: %s..." % response[:120])

            arch_details.append({
                "question": question,
                "focus":    focus,
                "response": response[:500],
                "verdict":  verdict,
            })

        avg = sum(arch_scores) / len(arch_scores) if arch_scores else 0
        pct = int(float(sum(arch_pass)) / len(arch_pass) * 100) if arch_pass else 0
        print("  => avg %.1f/5  pass rate %d%%\n" % (avg, pct))

        results["archetypes"][arch_name] = {
            "user_id":    user_id,
            "avg_score":  round(avg, 2),
            "pass_pct":   pct,
            "tests":      len(arch_scores),
            "details":    arch_details,
        }

    # Overall
    overall_avg  = sum(all_scores) / len(all_scores) if all_scores else 0
    overall_pass = int(float(sum(all_pass)) / len(all_pass) * 100) if all_pass else 0
    results["overall"] = {
        "avg_score": round(overall_avg, 2),
        "pass_pct":  overall_pass,
        "total_tests": total_tests,
    }

    print("=" * 60)
    print("OVERALL: %.1f/5  pass rate %d%%  (%d tests)" % (
        overall_avg, overall_pass, total_tests))

    print("\nARCHETYPE RANKING:")
    ranked = sorted(
        [(k, v["avg_score"]) for k, v in results["archetypes"].items()],
        key=lambda x: x[1], reverse=True
    )
    for arch, avg in ranked:
        bar = "#" * int(avg * 4)
        print("  %-26s %.1f/5  %s" % (arch.replace("_", " "), avg, bar))

    if save_path:
        with open(save_path, "w") as f:
            json.dump(results, f, indent=2)
        print("\nResults saved to %s" % save_path)

    return results

# -- Main -----------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="FinWise Eval Harness v2")
    parser.add_argument("--quick",     action="store_true",
                        help="2 queries per archetype (faster)")
    parser.add_argument("--archetype", type=str, default="all",
                        help="Single archetype or 'all'")
    parser.add_argument("--save",      type=str, default=None,
                        help="Save results JSON to this path")
    args = parser.parse_args()

    if args.archetype == "all":
        arch_list = list(BENCHMARKS.keys())
    elif args.archetype in BENCHMARKS:
        arch_list = [args.archetype]
    else:
        print("Unknown archetype. Choices: %s" % list(BENCHMARKS.keys()))
        sys.exit(1)

    save_path = args.save or ("eval_report_%s.json" % date.today().isoformat())
    run_eval(arch_list, quick=args.quick, save_path=save_path)

if __name__ == "__main__":
    main()