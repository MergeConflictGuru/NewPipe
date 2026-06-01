# Gemini Subtitle Prompt Lab

This is a local tuning loop for Gemini subtitle alignment prompts. It runs
outside Android so prompt variants can be tested repeatedly against the same
representative cues before changing the app.

The API key is read from `GEMINI_API_KEY` or the ignored local
`.gemini-api-key` file. Do not commit it or paste it into fixtures, prompts,
command arguments, reports, or chat.

## Quick Start

In PowerShell:

```powershell
$env:GEMINI_API_KEY = 'your-key'
python tools/gemini-subtitle-prompt-lab/prompt_lab.py validate
python tools/gemini-subtitle-prompt-lab/prompt_lab.py run --name atomic-v1
```

Alternatively, place the key in
`tools/gemini-subtitle-prompt-lab/.gemini-api-key`. The file is ignored by Git.

## APK Preflight

Before building and installing a debug APK after prompt or parser changes, run:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py app-preflight
```

This makes a few live Gemini calls using the app-oriented compact tuple schema,
one cue per request. It covers long captions, a short repeated-word cue, and a
previous zero-coverage cue. The command exits nonzero if Gemini returns
non-verbatim pairs, sparse results, or fails to return both `steak` mappings.

## App Batch-Size Benchmark

To compare alignment quality without rebuilding the app or translating the
track again, run:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py benchmark-app-batches
```

This uses the app's packaged `gemini_alignment_prompt.txt`, the saved bilingual
`378`-cue fixture, and the compact tuple response schema. It tests large batches
first to conserve the free-tier daily request quota and checkpoints a JSON
report plus a Markdown summary under `work/` after every size.

## Full SRT Track

The source-only Korean track is stored at
`fixtures/almond-reading-club-ko.srt`. Translate it once into an ignored
bilingual working fixture:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py prepare-srt `
  --srt tools/gemini-subtitle-prompt-lab/fixtures/almond-reading-club-ko.srt `
  --output tools/gemini-subtitle-prompt-lab/work/almond-reading-club-ko-en.json
```

Then test alignment prompts repeatedly without translating the track again:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py run `
  --fixtures tools/gemini-subtitle-prompt-lab/work/almond-reading-club-ko-en.json `
  --name almond-atomic-v1
```

## Generate Learner Corpus

To regenerate learner-friendly `expectedPairs` from an existing bilingual JSON
fixture, use the bounded correspondence generator:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py generate-correspondence `
  --fixtures tools/gemini-subtitle-prompt-lab/fixtures/subtitle-ko-en-word-correspondence.json `
  --output tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-word-correspondence-generated.json
```

The generator sends small batches, checkpoints progress after each successful
batch, and rejects pairs unless both strings occur verbatim in their cue. Use
`--limit 12` for a quick prompt experiment before running the entire corpus.
Use `--pair-format named` for descriptive `source`/`target` pair objects or
`--pair-format tuple` for compact `[source, target]` pairs. The experimental
`named-indexed` and `tuple-indexed` formats add zero-based source and target
occurrence indexes for repeated-word tests.
Keep human review notes in `EXPERIMENTS.md`; exact-substring validation catches
rendering hazards but cannot prove that a mapping is semantically correct.

Audit a generated corpus without another API call:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py audit-correspondence `
  --input tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-word-correspondence-generated.json `
  --output tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-word-correspondence-generated.audit.json
```

The audit reports rejected pairs, empty cues, broad chunks, and mapping sets
that cannot be assigned to non-overlapping source and translation occurrences.

## Colored Full-Track Review

Run the saved bilingual track through the app-oriented compact tuple contract,
one cue per request, and continuously update an HTML review file:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py generate-correspondence `
  --fixtures tools/gemini-subtitle-prompt-lab/fixtures/subtitle-ko-en-word-correspondence.json `
  --output tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-app-review.json `
  --html-output tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-app-review.html `
  --batch-size 1 `
  --pair-format tuple `
  --request-interval 5
```

The HTML view colors each accepted Korean and English substring with the same
color, leaves unmatched text neutral, and lists tuples rejected by the app-like
deterministic occurrence allocator. It can also be regenerated from a completed
corpus or an in-progress checkpoint without another API call:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py render-correspondence-html `
  --input tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-app-review.checkpoint.json `
  --output tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-app-review.html
```

To investigate why reviewed mappings were omitted, run repeated Gemini
self-diagnostics against a generated corpus or checkpoint:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py diagnose-omissions `
  --input tools/gemini-subtitle-prompt-lab/work/subtitle-ko-en-app-review-v2.checkpoint.json `
  --output tools/gemini-subtitle-prompt-lab/work/omission-diagnostics.json `
  --passes 2 `
  --limit 6
```

This writes JSON plus a Markdown report. Treat Gemini's explanations only as
hypotheses: each one is annotated with local checks for verbatim availability,
repeated occurrences, and source or target span conflicts. Rerun the same
command to resume incomplete diagnostic passes without repeating completed
ones.

Reports are written under `tools/gemini-subtitle-prompt-lab/results/`, which is
ignored by Git. Each run saves:

- The raw Gemini HTTP response.
- The generated alignment strings.
- Exact-substring validation flags.
- Broad-pair warnings.
- A Markdown table for human review.

Edit `prompts/alignment-candidate.txt`, run again with a new `--name`, then
compare metrics:

```powershell
python tools/gemini-subtitle-prompt-lab/prompt_lab.py compare `
  tools/gemini-subtitle-prompt-lab/results/20260601-120000-atomic-v1.json `
  tools/gemini-subtitle-prompt-lab/results/20260601-121500-atomic-v2.json
```

## Fixtures

The starter fixture file is
`fixtures/representative-ko-en.json`. It contains failure cases observed during
development and optional expected pairs. Add representative examples as new
failure modes appear.

The expected pairs are a review aid, not a perfect semantic ground truth. The
Markdown report is the important artifact: subtitle mappings still need a human
quality pass.

## Commands

```text
validate  Validate the fixture and prompt files without network access.
dry-run   Print the exact rendered prompt without calling Gemini.
run       Call Gemini and write JSON plus Markdown reports.
prepare-srt
          Translate a source-only SRT once into an ignored bilingual fixture.
generate-correspondence
          Generate validated learner word pairs from an aligned bilingual JSON fixture.
audit-correspondence
          Audit a generated learner corpus locally without calling Gemini.
app-preflight
          Run the fast live tuple-schema regression before building an APK.
render-correspondence-html
          Render a checkpoint or corpus as a colored browser-readable review.
diagnose-omissions
          Ask Gemini why reviewed pairs were omitted and locally verify its clues.
compare   Show summary metrics from previous JSON reports.
```
