# Gemini Subtitle Prompt Experiments

Use the same first 12 cues for prompt pilots before spending calls on the full
track. Exact-substring validation is necessary but not sufficient: review the
returned mappings manually for learner usefulness, coverage, and chunk size.

## Current Best

`atomic-nonoverlap-v1` is the current best prompt. It has the strongest
hard-case behavior so far and passed the opening-slice regression. Use it for
the full track, while retaining a human review pass for semantic mistakes.

## Results

### `string-encoded-baseline`

- Artifact: `work/subtitle-ko-en-word-correspondence-pilot.json`
- Result: `0` accepted pairs, `50` rejected entries.
- Verdict: unusable output format. Gemini returned flat alternating arrays
  instead of nested pair arrays.
- Action: replaced JSON-inside-string output with a structured response schema.

### `structured-baseline`

- Artifact: `work/subtitle-ko-en-word-correspondence-pilot-v2.json`
- Result: `25` accepted pairs, `1` rejected pair.
- Verdict: current best, but too conservative. Pair shape and chunk sizes are
  mostly good. It misses obvious learner-useful mappings including
  `포함하고 있습니다 -> contains`, `벌써 -> already`,
  `열게 되었는데요 -> holding`, and `다뤄봤는데 -> covered`.
- Action: added an explicit per-cue coverage pass and smaller-substring advice.

### `coverage-pass-v1`

- Artifact: `work/subtitle-ko-en-word-correspondence-pilot-v3.json`
- Result: `35` accepted pairs, `0` rejected pairs.
- Verdict: current best. It recovered useful mappings such as
  `포함하고 있습니다 -> contains`, `벌써 -> already`,
  `열게 되었는데요 -> holding`, and `다뤄봤는데 -> covered`. It still omitted
  clear pairs including `스포일러 -> spoilers` and `많이 -> so many`, and
  returned the overly broad
  `재밌게 봤거든요 -> I really enjoyed reading them`.
- Action: require a final unmatched-content audit and retry splitting target
  chunks longer than three words.

### `coverage-audit-v1`

- Artifact: `work/subtitle-ko-en-word-correspondence-pilot-v4.json`
- Result: `36` accepted pairs, `0` rejected pairs.
- Verdict: current best. It restored `스포일러 -> spoilers`, added
  `많이 -> so many`, and split the earlier broad phrase down to
  `재밌게 -> really enjoyed`. It omitted the useful but less important
  `일부 -> some`, so prompt results still need human review.
- Action: use `temperature: 0` for repeatable tuning and test this candidate
  against the starter failure corpus.

### `coverage-audit-v1` hard cases

- Artifact: `work/representative-ko-en-pilot-v1.json`
- Result: `35` accepted pairs, `0` rejected pairs.
- Verdict: good coverage on the earlier collapse failures, but not ready.
  Gemini returned the false semantic mapping `찾다가 -> after` and missed
  reordered adverbs including `결국 -> finally` and `다시 -> again`.
- Action: require an independent semantic check for every pair and call out
  reordered adverbs during the final coverage audit.

### `semantic-guard-v1` hard cases

- Artifact: `work/representative-ko-en-pilot-v2.json`
- Result: `34` accepted pairs, `1` rejected pair.
- Verdict: not better. It recovered `결국 -> finally`, but wrapped avoidable
  particles and produced the false overlapping mapping
  `곤이를 찾다가 -> after losing him`.
- Action: prioritize atomic content substrings, strip particles when possible,
  and prohibit overlapping source or target spans.

### `atomic-nonoverlap-v1` hard cases

- Artifact: `work/representative-ko-en-pilot-v3.json`
- Result: `33` accepted pairs, `0` rejected pairs.
- Verdict: new best hard-case result. It removed the false overlap, trimmed
  particles, restored `다시 -> again`, `결국 -> finally`, and
  `공감 -> empathy`, and kept the reordered mappings compact. It still omitted
  `이렇게 -> here` and `만나 -> meets`.
- Action: clarify that adjacent mappings are allowed, keep semantic modifiers
  atomic, and explicitly restore omitted translated details during the audit.

### `adjacent-audit-v1` hard cases

- Artifact: `work/representative-ko-en-pilot-v4.json`
- Result: `31` accepted pairs, `0` rejected pairs.
- Verdict: regression. It recovered `이렇게 -> here` and made
  `엄청 -> so much` more atomic, but invented `찾다가 -> reunites` and dropped
  useful mappings including `죽어가는 -> dying`.
- Action: reverted the adjacent-audit paragraph. Keep `atomic-nonoverlap-v1`.

### `atomic-nonoverlap-v1` opening regression

- Artifact: `work/subtitle-ko-en-word-correspondence-pilot-v6-atomic.json`
- Result: `38` accepted pairs, `0` rejected pairs.
- Verdict: selected prompt. This is the strongest opening-slice result and no
  false mapping was found during manual review. It restored `일부 -> some` and
  split cue 9 into compact mappings. Two broad phrase mappings remain where the
  translation is naturally phrase-level.
- Action: run the full `378`-cue corpus in checkpointed batches, then review
  generated output rather than assuming substring validation proves quality.

### `atomic-nonoverlap-v1` full corpus

- Artifact:
  `work/subtitle-ko-en-word-correspondence-generated-atomic-v1.json`
- Result: `378` cues, `1179` accepted pairs, `44` rejected non-verbatim pairs.
- Local audit: `5` empty cues, `46` broad pairs, and `3` genuine target-span
  conflicts after accounting for repeated words in separate occurrences.
- Verdict: useful review corpus, not a blind ground-truth replacement. Manual
  sampling looked broadly sensible. The empty cues expose upstream cue-boundary
  alignment problems in the bilingual fixture, such as cue `0021`, where the
  Korean and English halves belong to adjacent subtitle cues.
- Known conflicts: cue `0126` maps both `굉장히` and `많이` to `a great deal`;
  cue `0130` maps both `너무` and `직설적이게` to `outright`; cue `0339` maps
  two Korean occurrences of `공감` to the single English `empathy`.
- Action: keep the generated file as the prompt-lab artifact. Before app
  ingestion, repair bilingual cue alignment and add deterministic conflict
  sanitization. Do not treat substring validation as semantic proof.

### Existing fixture comparison

- Existing fixture: `fixtures/subtitle-ko-en-word-correspondence.json`
- Existing result: `1218` pairs across `369` non-empty cues, `9` broad pairs,
  and `0` non-overlap conflicts.
- `atomic-nonoverlap-v1` result: `1179` pairs across `373` non-empty cues,
  `46` broad pairs, and `3` non-overlap conflicts.
- Verdict: keep the existing fixture as the better base corpus.
  `atomic-nonoverlap-v1` is the best prompt variant tested so far, but its
  generated corpus is not a better wholesale replacement. It finds useful
  missing mappings, such as `벌써 -> already` and `열게 되었는데요 -> holding`,
  but it also introduces semantic regressions, such as `뇌 -> amygdalae`,
  swaps `공포 -> fear` and `두려움 -> terror`, and sometimes assigns one target
  span to multiple source chunks.
- Action: use generated results as review suggestions for augmenting the
  existing fixture, not as an automatic replacement.

### Pair JSON shape comparison

Compared the same selected prompt with two structured response schemas:

- Named pairs: `{"source":"감정","target":"emotions"}`
- Tuple pairs: `["감정","emotions"]`

Hard-case corpus:

- Named: `35` accepted pairs, `0` rejected, `2975` raw UTF-8 response bytes,
  and `24/30` exact reviewed-pair matches.
- Tuple: `32` accepted pairs, `0` rejected, `1331` raw UTF-8 response bytes,
  and `25/30` exact reviewed-pair matches.

Opening `12` cues:

- Named: `35` accepted pairs, `0` rejected, `3351` raw UTF-8 response bytes.
- Tuple: `33` accepted pairs, `1` rejected, `1739` raw UTF-8 response bytes.

Verdict: tuples reduce generated response size by roughly half and do not
meaningfully confuse Gemini when the prompt explicitly documents tuple order.
Named fields retain slightly more coverage on the opening slice. Use structured
outer cue objects with tuple pairs as the app-oriented compact format:
`{"id":7,"pairs":[["감정","emotions"]]}`. Keep local validation and sanitization;
neither shape prevents semantic mistakes by itself.

### Explicit occurrence-index comparison

Fixture: `fixtures/repeated-ko-en.json`

Compared two schemas that ask Gemini to identify repeated substring
occurrences explicitly:

- Named indexed pairs:
  `{"source":"공감","target":"empathy","sourceOccurrence":1,"targetOccurrence":0}`
- Tuple indexed pairs: `["공감","empathy",1,0]`

Results:

- Named indexed: `19` accepted pairs, `1` rejected invalid index,
  `2880` raw UTF-8 response bytes, and `15/23` reviewed alignment matches.
- Tuple indexed: `19` accepted pairs, `0` rejected invalid indexes,
  `855` raw UTF-8 response bytes, and `11/23` reviewed alignment matches.

Verdict: do not delegate occurrence resolution to Gemini as the primary
strategy. Named indexed objects perform better than four-value tuples, but
neither is reliable enough. Both formats failed the deliberately reordered-name
cue by assigning repeated names in reading order instead of semantic order.
Named indexed output also tried to use nonexistent target occurrence `1` for a
many-to-one `공감 -> empathy` mapping. Compact indexed tuples additionally
reversed the two `나` mappings in `나는 나야 -> I am me`.

Action: keep pair text compact and resolve ordinary occurrence indexes
deterministically in the app. Treat repeated-word cases with ambiguous
reordering as a residual limitation or add a narrowly-scoped fallback later.

### App live alignment batch-size regression

Fixture: `fixtures/app-live-regression-ko-en.json`

The live app originally sent roughly `70` translated cues to the alignment
prompt at once. A focused regression using long caption cues and a repeated
`steak` cue showed that pair format was not the main source of sparse output:

- Tuple pairs, `3` cues per request: `13` pairs.
- Named pairs, `3` cues per request: `11` pairs.
- Tuple pairs, `2` cues per request: `17` pairs.
- Tuple pairs, `1` cue per request: `31` pairs.

The single-cue tuple run restored compact mappings including
`그러면 -> Then`, `스테이크 -> steak`, and `두부 -> tofu`. The repeated-word
tuple fixture also produced `21` pairs with `0` rejected entries.

Action: retain compact tuple pairs and align one translated cue per Gemini
request in the app. Translation remains chunked and progressive; alignment
colors fill in afterward.

### Offline full-track app-oriented review

Artifacts:

- Baseline HTML:
  `work/subtitle-ko-en-app-review-baseline.html`
- Fresh checkpoint:
  `work/subtitle-ko-en-app-review-v2.checkpoint.json`
- Fresh progressive HTML:
  `work/subtitle-ko-en-app-review-v2.html`

The fresh run uses compact tuple pairs, one cue per request, app-like
deterministic repeated-occurrence allocation, and a colored browser-readable
review. It checkpointed `76` cues with `359` tuples and `8` verbatim-validation
rejects before the free-tier daily request quota was exhausted.

### Full-track app batch-size benchmark

Added `benchmark-app-batches` to compare the app's packaged alignment prompt
against the saved `378`-cue bilingual fixture without rebuilding or
retranslating. The first run was interrupted by the project's `500` request
free-tier daily quota while the phone was also aligning subtitles:

- Batch size `10`: `4` successful requests covering `40` cues, `108` accepted
  pairs, and `16` rejected pairs before quota exhaustion.
- Batch size `60`: `1` successful request covering `60` cues, `122` accepted
  pairs, and `12` rejected pairs before quota exhaustion.
- Replacement-key overlap, cues `0-119`: batch size `30` returned `255`
  accepted pairs and matched `179/425` curated pairs; batch size `60` returned
  `214` accepted pairs and matched `157/425`.
- Batch size `120`: rejected immediately with HTTP `400 INVALID_ARGUMENT`.
- Batch size `378`: rejected immediately with HTTP `400 INVALID_ARGUMENT`.

The replacement key later completed a full-track comparison:

- Batch size `60`: `7` requests, `639` accepted pairs, `410/1218` curated hits.
- Batch size `30`: `13` requests, `759` accepted pairs, `440/1218` curated hits.
- Batch size `10`: `38` requests, `1083` accepted pairs, `539/1218` curated hits.

Action: keep alignment batches at `10`. Larger batches conserve requests, but
the quality loss is too large for a learner-facing feature. Interleave each
translated region with its alignment requests so useful colors appear near
playback before the worker advances through the rest of the video.

### Omission self-diagnostics

Artifact: `work/omission-diagnostics-v2.md`

Added a diagnostic pass that asks Gemini which numbered prompt rule most likely
caused each reviewed omission. Treat these explanations as hypotheses only.
The report locally verifies whether each reviewed pair exists verbatim, has an
unused source span, has an unused target span, and conflicts with accepted
tuples.

The first compact pass found two actionable patterns:

- Clear locally addable content was still omitted, including `누구 -> who`,
  `나중에 -> later`, and `사람 -> people`. Gemini attributed these to an
  insufficient final coverage audit.
- Gemini treated the non-overlap rule too globally for repeated text. For
  example, it claimed `교수 -> professor` should be skipped because
  `윤 교수 -> Professor Yoon` was already mapped, while the local audit found
  unused occurrences on both sides.

Some reviewed omissions were correctly superseded by broader accepted chunks,
so blindly maximizing pair count would be harmful.

Action: clarify that overlap concerns concrete character ranges rather than
substring values, and explicitly revisit unused core nouns, interrogatives, and
temporal adverbs during the final audit. Run the revised prompt as a new
offline candidate after quota reset before copying it into Android.
