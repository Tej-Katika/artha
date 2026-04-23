# arXiv Submission Guide — Artha

This folder contains everything needed to submit the Artha paper to
arXiv. The submission is a self-contained LaTeX source; arXiv will
compile it server-side and produce the PDF.

## Files in this folder

| File | Purpose |
|---|---|
| `artha.tex` | Main paper source. Self-contained (inline bibliography). |
| `abstract_plain.txt` | Plain-text abstract for arXiv's form field. |
| `arxiv_metadata.txt` | Title, authors, categories, comments — copy into arXiv form. |
| `SUBMIT.md` | This file. |

No figures, no separate bibliography file, no `.bbl`, no `.cls` — everything
the paper needs (other than packages bundled with arXiv's TeX tree such as
`IEEEtran`, `amsmath`, `booktabs`, `hyperref`) is in `artha.tex`.

## Before you submit

Verify once that the numbers in the paper match the evaluation outputs:

- `../eval_results/summary_A.json`, `summary_B.json`, `summary_C.json`,
  `summary_D.json` — the four ablation conditions
- `../eval_results/ablation_summary.txt` — the Table 5 and Table 6 numbers

The paper's key numbers to cross-check:

- **Condition A**: 75.0% pass rate, 3.67/5 mean score
- **Condition B**: 50.0% pass rate, 2.97/5 mean score  (Δ = −25.0%)
- **Condition C**: 70.0% pass rate, 3.56/5 mean score  (Δ = −5.0%)
- **Condition D**: 48.3% pass rate, 2.86/5 mean score  (Δ = −26.7%)
- Enrichment split: 40.4% RULES + 59.6% METADATA on eval users

## If you have a local LaTeX install (optional, recommended)

Build the PDF locally to check for compile errors *before* uploading.
Easiest path on Windows:

1. Install [MiKTeX](https://miktex.org/) (full install includes IEEEtran).
2. From a shell in this folder:
   ```
   pdflatex artha
   pdflatex artha    # second pass resolves table/ref cross-links
   ```
3. Inspect `artha.pdf`. If it compiles cleanly (no red errors), you're good.
4. MiKTeX will prompt to install any missing packages on first run; accept.

If `pdflatex` isn't available and you don't want to install a TeX
distribution, you can also test-compile online at Overleaf:

- Create a new empty project
- Upload `artha.tex`
- Set compiler to `pdfLaTeX`
- Click "Recompile"

## Uploading to arXiv

1. Sign in at https://arxiv.org/. If you don't have an account, register.
   Your account may need endorsement to submit to `cs.AI` the first
   time — this is normal and free; any existing arXiv author in cs.AI can
   endorse.

2. Click **Start new submission** from the top bar.

3. **Step 1 — License**: pick the default *arXiv.org perpetual,
   non-exclusive license*.

4. **Step 2 — Metadata**: paste from `arxiv_metadata.txt`:
   - Title
   - Authors (one per row)
   - Abstract (paste from `abstract_plain.txt`)
   - Comments
   - Report/Journal fields — leave blank
   - Primary subject: `cs.AI`
   - Cross-lists: `cs.CL`, `cs.IR`

5. **Step 3 — File upload**:
   - Make a `.tar.gz` (or `.zip`) containing *only* `artha.tex`.
     See "Creating the upload archive" below.
   - Upload it.
   - arXiv runs a compile check. You should see "Processed without errors."
     If you see warnings about undefined references on the first pass,
     that's because arXiv's first pass doesn't have cross-refs yet — click
     "AutoTeX" or the re-process button; the second pass resolves them.

6. **Step 4 — Preview**: arXiv shows the compiled PDF. Read through it
   end-to-end once. Check:
   - Title and abstract render correctly
   - All tables have no missing columns
   - Every `[?]` is resolved (references + cross-refs to tables/sections)
   - Number values in the tables match the summary JSONs

7. **Step 5 — Submit**: click "Submit" when satisfied.

8. arXiv will assign an ID (e.g., `arXiv:2604.XXXXX`) and schedule an
   announce date — typically within a few weekdays. You'll receive email
   confirmation.

## Creating the upload archive

On Windows (PowerShell):

```powershell
cd C:\Users\tejas\Projects\finwise-agent\arxiv_submission
Compress-Archive -Path artha.tex -DestinationPath artha-arxiv.zip -Force
```

On Git Bash / Unix:

```bash
cd arxiv_submission
tar -czf artha-arxiv.tar.gz artha.tex
```

Either `.zip` or `.tar.gz` is fine for arXiv.

## After posting

- The arXiv ID becomes the canonical citation for the preprint.
- You can submit **replacements** (v2, v3, ...) later when you:
  - Add Cohen's kappa human evaluation
  - Add cross-family LLM judge
  - Expand to n >= 5 users per archetype
  - Address IEEE Big Data reviewer feedback (if accepted)

Each replacement keeps the same arXiv ID with a new version number.

## Questions arXiv moderators sometimes ask

The paper is clean for cs.AI and cs.CL because:
- It introduces a novel framework (Artha) with a working implementation
- Includes a reproducible evaluation methodology
- Discloses limitations transparently
- Cites peer-reviewed work (MT-Bench, ReAct, Toolformer)

If moderators ask for clarification, common requests:
- Confirm the affiliation claim (University of North Texas) — ensure your
  arXiv account email is consistent
- Confirm authorship — you are the sole author; no other names required
- Category fit — cs.AI is appropriate; cs.HC could also be added if
  reviewers view personal finance as human-computer interaction
