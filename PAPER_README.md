# Artha Paper — Build and Submission Guide

## Files

- `artha.tex` — full LaTeX source, IEEE conference format (`IEEEtran`)
- `artha.bib` — references (BibTeX)
- `eval_results/ablation_summary.txt` — reproducible source for Tables 5 and 6
- `eval_results/summary_{A,B,C,D}.json` — machine-readable raw numbers
- `eval_results/run_{A,B,C,D}_*.txt` — human-readable agent + judge transcripts

## Building the PDF

Requires a TeX distribution (TeX Live / MiKTeX / MacTeX) with the
`IEEEtran` class.

```bash
pdflatex artha
bibtex  artha
pdflatex artha
pdflatex artha
```

If `IEEEtran.cls` is not found:

```bash
# TeX Live
tlmgr install ieeetran

# MiKTeX will usually install it on demand.
```

## Targets

- **arXiv (immediate)**: compile locally, upload `artha.tex`,
  `artha.bib`, and the generated `.bbl` file as a tarball.
- **IEEE Big Data 2026**: same source compiles as-is against
  their LaTeX template. Before final submission, address the
  limitations listed in Section VIII (human Cohen's kappa,
  cross-family judge, multi-user per archetype).

## Before you upload to arXiv

Sanity pass checklist:

- [ ] Rebuild the PDF and read through end-to-end at least once
- [ ] Check every number in Tables 1-4 against
      `eval_results/summary_*.json`
- [ ] Confirm the qualitative example quote (Section VI.C) matches
      the actual run output in `eval_results/run_A_*.txt`
- [ ] Replace the affiliation / email header if needed
- [ ] Double-check references resolve (no `[?]` in the PDF)
- [ ] Verify LaTeX compilation produces no unresolved citations

## Scope of what's in the paper vs. what was deferred

**In the paper:**

- Multi-source enrichment (RULES + METADATA), transparent per-source
  percentages
- 4-condition ablation on all 60 benchmark queries (n=60 per
  condition; D intermittent rate-limit issues resolved on re-run)
- Reference-date override, disclosed in Methods and Limitations
- Post-hoc detector threshold tuning, disclosed in Limitations
- Full qualitative example (Condition A vs Condition B response
  for `subscription_audit`)

**Deferred to future work (stated in Limitations section):**

- Cohen's kappa vs human annotators (rubric released, infra
  `compute_kappa.py` ready)
- Cross-family LLM judge (GPT-4.1 or Gemini)
- Multiple seed users per archetype (n >= 5)
- Real-bank validation

These are all legitimate reviewer asks for a peer-reviewed venue;
the paper explicitly calls them out so there are no surprises.
