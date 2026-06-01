#!/usr/bin/env python3
"""Run repeatable Gemini subtitle-alignment prompt experiments."""

from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

DEFAULT_MODEL = "gemini-3.1-flash-lite"
INPUT_MARKER = "{{INPUTS}}"
PAIR_FORMAT_MARKER = "{{PAIR_FORMAT}}"
TAGGED_TEXT = re.compile(r"^<(\d+)>(.*)$", re.DOTALL)
WORD = re.compile(r"[\w]+(?:['\u2019-][\w]+)*", re.UNICODE)
SRT_TIMING = re.compile(
    r"^(?P<start>\d{2}:\d{2}:\d{2},\d{3}) --> (?P<end>\d{2}:\d{2}:\d{2},\d{3})$"
)


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="Validate fixtures and prompt locally")
    add_common_args(validate, root)

    dry_run = subparsers.add_parser("dry-run", help="Print the complete prompt without an API call")
    add_common_args(dry_run, root)

    run = subparsers.add_parser("run", help="Call Gemini and write JSON plus Markdown reports")
    add_common_args(run, root)
    run.add_argument("--model", default=DEFAULT_MODEL)
    run.add_argument("--name", default="candidate")
    run.add_argument("--results-dir", type=Path, default=root / "results")
    run.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")
    run.add_argument("--batch-size", type=int, default=48)

    prepare_srt = subparsers.add_parser(
        "prepare-srt",
        help="Translate a source-only SRT once and cache a bilingual alignment fixture",
    )
    prepare_srt.add_argument("--srt", type=Path, required=True)
    prepare_srt.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "translated-srt.json",
    )
    prepare_srt.add_argument(
        "--translation-prompt",
        type=Path,
        default=root / "prompts" / "translation-candidate.txt",
    )
    prepare_srt.add_argument("--source-language", default="Korean")
    prepare_srt.add_argument("--target-language", default="English")
    prepare_srt.add_argument("--model", default=DEFAULT_MODEL)
    prepare_srt.add_argument("--batch-size", type=int, default=48)
    prepare_srt.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")

    generate = subparsers.add_parser(
        "generate-correspondence",
        help="Generate a validated learner correspondence corpus from aligned subtitle cues",
    )
    generate.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "subtitle-ko-en-word-correspondence.json",
    )
    generate.add_argument(
        "--prompt",
        type=Path,
        default=root / "prompts" / "correspondence-generator.txt",
    )
    generate.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "subtitle-ko-en-word-correspondence-generated.json",
    )
    generate.add_argument("--checkpoint", type=Path)
    generate.add_argument("--model", default=DEFAULT_MODEL)
    generate.add_argument("--batch-size", type=int, default=12)
    generate.add_argument("--limit", type=int)
    generate.add_argument(
        "--request-interval",
        type=float,
        default=0,
        help="Minimum seconds between Gemini requests; use 5 for the free tier",
    )
    generate.add_argument(
        "--html-output",
        type=Path,
        help="Continuously update a colored HTML review artifact while generating",
    )
    generate.add_argument(
        "--pair-format",
        choices=("named", "tuple", "named-indexed", "tuple-indexed"),
        default="named",
    )
    generate.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")

    audit = subparsers.add_parser(
        "audit-correspondence",
        help="Audit a generated learner correspondence corpus without an API call",
    )
    audit.add_argument("--input", type=Path, required=True)
    audit.add_argument("--output", type=Path)

    render_html = subparsers.add_parser(
        "render-correspondence-html",
        help="Render generated or checkpointed correspondence pairs as a colored HTML review",
    )
    render_html.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "subtitle-ko-en-word-correspondence.json",
    )
    render_html.add_argument("--input", type=Path, required=True)
    render_html.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "subtitle-ko-en-word-correspondence-review.html",
    )

    preflight = subparsers.add_parser(
        "app-preflight",
        help="Run a fast app-oriented Gemini tuple-schema regression before building an APK",
    )
    preflight.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "app-live-regression-ko-en.json",
    )
    preflight.add_argument(
        "--prompt",
        type=Path,
        default=root / "prompts" / "correspondence-generator.txt",
    )
    preflight.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "app-preflight-latest.json",
    )
    preflight.add_argument("--model", default=DEFAULT_MODEL)
    preflight.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")

    benchmark = subparsers.add_parser(
        "benchmark-app-batches",
        help="Compare app alignment quality across several batch sizes without translating again",
    )
    benchmark.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "subtitle-ko-en-word-correspondence.json",
    )
    benchmark.add_argument(
        "--prompt",
        type=Path,
        default=root.parent.parent / "app" / "src" / "main" / "res" / "raw"
        / "gemini_alignment_prompt.txt",
    )
    benchmark.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "app-alignment-batch-benchmark.json",
    )
    benchmark.add_argument("--markdown-output", type=Path)
    benchmark.add_argument("--model", default=DEFAULT_MODEL)
    benchmark.add_argument("--batch-sizes", type=int, nargs="+", default=[60, 30, 10])
    benchmark.add_argument("--limit", type=int)
    benchmark.add_argument("--request-interval", type=float, default=5)
    benchmark.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")

    diagnose = subparsers.add_parser(
        "diagnose-omissions",
        help="Ask Gemini why reviewed pairs were omitted and verify its explanations locally",
    )
    diagnose.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "subtitle-ko-en-word-correspondence.json",
    )
    diagnose.add_argument("--input", type=Path, required=True)
    diagnose.add_argument(
        "--prompt",
        type=Path,
        default=root / "prompts" / "correspondence-generator.txt",
    )
    diagnose.add_argument(
        "--output",
        type=Path,
        default=root / "work" / "omission-diagnostics.json",
    )
    diagnose.add_argument("--markdown-output", type=Path)
    diagnose.add_argument("--model", default=DEFAULT_MODEL)
    diagnose.add_argument("--api-key-file", type=Path, default=root / ".gemini-api-key")
    diagnose.add_argument("--limit", type=int, default=6)
    diagnose.add_argument("--passes", type=int, default=2)
    diagnose.add_argument("--request-interval", type=float, default=5)

    compare = subparsers.add_parser("compare", help="Compare metrics from two or more JSON reports")
    compare.add_argument("reports", nargs="+", type=Path)
    return parser.parse_args()


def add_common_args(parser: argparse.ArgumentParser, root: Path) -> None:
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=root / "fixtures" / "representative-ko-en.json",
    )
    parser.add_argument(
        "--prompt",
        type=Path,
        default=root / "prompts" / "alignment-candidate.txt",
    )


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_inputs(fixtures_path: Path, prompt_path: Path) -> tuple[dict[str, Any], str, list[dict[str, Any]]]:
    fixtures = read_json(fixtures_path)
    cases = fixtures.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{fixtures_path}: cases must be a non-empty array")
    for index, case in enumerate(cases):
        for key in ("id", "source", "translation"):
            if not isinstance(case.get(key), str) or not case[key]:
                raise ValueError(f"{fixtures_path}: cases[{index}].{key} must be a non-empty string")

    prompt_template = prompt_path.read_text(encoding="utf-8")
    if prompt_template.count(INPUT_MARKER) != 1:
        raise ValueError(f"{prompt_path}: expected exactly one {INPUT_MARKER} marker")
    inputs = [
        {"id": index, "source": case["source"], "translation": case["translation"]}
        for index, case in enumerate(cases)
    ]
    return fixtures, prompt_template, cases


def render_alignment_prompt(
    prompt_template: str, cases: list[dict[str, Any]], cue_ids: list[int]
) -> str:
    inputs = [
        {
            "id": cue_id,
            "source": cases[cue_id]["source"],
            "translation": cases[cue_id]["translation"],
        }
        for cue_id in cue_ids
    ]
    return prompt_template.replace(INPUT_MARKER, json.dumps(inputs, ensure_ascii=False))


def render_translation_prompt(
    prompt_template: str,
    cues: list[dict[str, Any]],
    cue_ids: list[int],
    source_language: str,
    target_language: str,
) -> str:
    inputs = [{"id": cue_id, "text": cues[cue_id]["source"]} for cue_id in cue_ids]
    return (
        prompt_template
        .replace("{{SOURCE_LANGUAGE}}", source_language)
        .replace("{{TARGET_LANGUAGE}}", target_language)
        .replace(INPUT_MARKER, json.dumps(inputs, ensure_ascii=False))
    )


def batches(size: int, batch_size: int) -> list[list[int]]:
    if batch_size <= 0:
        raise ValueError("batch size must be positive")
    return [list(range(start, min(start + batch_size, size))) for start in range(0, size, batch_size)]


def request_gemini_json(
    api_key: str,
    model: str,
    prompt: str,
    response_json_schema: dict[str, Any],
) -> tuple[dict[str, Any], Any]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseJsonSchema": response_json_schema,
            "temperature": 0,
        },
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8", "x-goog-api-key": api_key},
        method="POST",
    )
    for attempt in range(1, 6):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw_response = json.loads(response.read().decode("utf-8"))
            break
        except urllib.error.HTTPError as error:
            response_text = error.read().decode("utf-8", errors="replace")
            if error.code != 429 or attempt == 5 or not is_retryable_rate_limit(response_text):
                raise RuntimeError(f"Gemini HTTP {error.code}: {response_text}") from error
            retry_match = re.search(r"retry in ([0-9.]+)s", response_text, re.IGNORECASE)
            delay = float(retry_match.group(1)) + 1 if retry_match else 20
            print(f"Gemini rate limit reached; waiting {delay:.1f}s")
            time.sleep(delay)
    generated_text = raw_response["candidates"][0]["content"]["parts"][0]["text"]
    return raw_response, json.loads(generated_text)


def is_retryable_rate_limit(response_text: str) -> bool:
    return "GenerateRequestsPerDay" not in response_text


def request_gemini(api_key: str, model: str, prompt: str, line_count: int) -> tuple[dict[str, Any], list[str]]:
    raw_response, output_lines = request_gemini_json(
        api_key,
        model,
        prompt,
        {
            "type": "array",
            "minItems": line_count,
            "maxItems": line_count,
            "items": {"type": "string"},
        },
    )
    if not isinstance(output_lines, list):
        raise ValueError("Gemini generated text was not a JSON array")
    return raw_response, output_lines


def request_gemini_correspondences(
    api_key: str,
    model: str,
    prompt: str,
    cue_count: int,
    pair_format: str,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if pair_format == "named":
        pair_schema = {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "target": {"type": "string"},
            },
            "required": ["source", "target"],
        }
    elif pair_format == "tuple":
        pair_schema = {
            "type": "array",
            "minItems": 2,
            "maxItems": 2,
            "items": {"type": "string"},
        }
    elif pair_format == "named-indexed":
        pair_schema = {
            "type": "object",
            "properties": {
                "source": {"type": "string"},
                "target": {"type": "string"},
                "sourceOccurrence": {"type": "integer", "minimum": 0},
                "targetOccurrence": {"type": "integer", "minimum": 0},
            },
            "required": ["source", "target", "sourceOccurrence", "targetOccurrence"],
        }
    elif pair_format == "tuple-indexed":
        pair_schema = {
            "type": "array",
            "minItems": 4,
            "maxItems": 4,
            "prefixItems": [
                {"type": "string"},
                {"type": "string"},
                {"type": "integer", "minimum": 0},
                {"type": "integer", "minimum": 0},
            ],
        }
    else:
        raise ValueError(f"Unknown correspondence pair format: {pair_format}")
    raw_response, output_items = request_gemini_json(
        api_key,
        model,
        prompt,
        {
            "type": "array",
            "minItems": cue_count,
            "maxItems": cue_count,
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "pairs": {
                        "type": "array",
                        "items": pair_schema,
                    },
                },
                "required": ["id", "pairs"],
            },
        },
    )
    if not isinstance(output_items, list):
        raise ValueError("Gemini generated correspondence output was not a JSON array")
    return raw_response, output_items


def read_api_key(api_key_file: Path) -> str:
    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key and api_key_file.is_file():
        api_key = api_key_file.read_text(encoding="utf-8").strip()
    if not api_key:
        raise RuntimeError(
            f"Set GEMINI_API_KEY or write the key to {api_key_file} before running the prompt lab"
        )
    return api_key


def parse_tagged_text(encoded_text: str) -> tuple[int, str]:
    match = TAGGED_TEXT.match(encoded_text)
    if not match:
        raise ValueError(f"Malformed tagged Gemini output: {encoded_text!r}")
    return int(match.group(1)), match.group(2)


def parse_srt(path: Path) -> list[dict[str, Any]]:
    lines = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    cues: list[dict[str, Any]] = []
    line_index = 0
    while line_index < len(lines):
        if not lines[line_index].strip():
            line_index += 1
            continue
        sequence = lines[line_index].strip()
        line_index += 1
        if line_index >= len(lines):
            raise ValueError(f"{path}: cue {sequence} is missing timing")
        timing = SRT_TIMING.match(lines[line_index].strip())
        if not timing:
            raise ValueError(f"{path}: cue {sequence} has invalid timing: {lines[line_index]!r}")
        line_index += 1
        text_lines: list[str] = []
        while line_index < len(lines) and lines[line_index].strip():
            text_lines.append(lines[line_index].strip())
            line_index += 1
        source = "\n".join(text_lines)
        if source:
            cues.append(
                {
                    "id": f"srt-{sequence}",
                    "srtSequence": sequence,
                    "start": timing.group("start"),
                    "end": timing.group("end"),
                    "source": source,
                }
            )
    if not cues:
        raise ValueError(f"{path}: no subtitle cues found")
    return cues


def parse_correspondence_output(
    output_items: list[dict[str, Any]],
    expected_ids: set[int],
    cases: list[dict[str, Any]],
    pair_format: str,
) -> tuple[dict[int, list[list[str]]], dict[int, list[dict[str, Any]]], list[dict[str, Any]]]:
    generated: dict[int, list[list[str]]] = {}
    occurrence_alignments: dict[int, list[dict[str, Any]]] = {}
    rejected: list[dict[str, Any]] = []
    for output_item in output_items:
        if not isinstance(output_item, dict):
            raise ValueError(f"Gemini returned a non-object correspondence: {output_item!r}")
        cue_id = output_item.get("id")
        if cue_id not in expected_ids or cue_id in generated:
            raise ValueError(f"Gemini returned an invalid or duplicate correspondence id: {cue_id}")
        pairs = output_item.get("pairs")
        if not isinstance(pairs, list):
            raise ValueError(f"Gemini returned non-array pairs for cue {cue_id}")
        accepted_pairs: list[list[str]] = []
        accepted_occurrence_alignments: list[dict[str, Any]] = []
        for pair in pairs:
            if pair_format == "named" and isinstance(pair, dict):
                source = pair.get("source")
                target = pair.get("target")
                source_occurrence = None
                target_occurrence = None
            elif pair_format == "tuple" and isinstance(pair, list) and len(pair) == 2:
                source, target = pair
                source_occurrence = None
                target_occurrence = None
            elif pair_format == "named-indexed" and isinstance(pair, dict):
                source = pair.get("source")
                target = pair.get("target")
                source_occurrence = pair.get("sourceOccurrence")
                target_occurrence = pair.get("targetOccurrence")
            elif pair_format == "tuple-indexed" and isinstance(pair, list) and len(pair) == 4:
                source, target, source_occurrence, target_occurrence = pair
            else:
                source = None
                target = None
                source_occurrence = None
                target_occurrence = None
            if not isinstance(source, str) or not source or not isinstance(target, str) or not target:
                rejected.append({"cueIndex": cue_id, "pair": pair, "reason": "malformed pair"})
                continue
            if source not in cases[cue_id]["source"]:
                rejected.append(
                    {"cueIndex": cue_id, "pair": pair, "reason": "source substring missing"}
                )
                continue
            if target not in cases[cue_id]["translation"]:
                rejected.append(
                    {"cueIndex": cue_id, "pair": pair, "reason": "target substring missing"}
                )
                continue
            if pair_format.endswith("-indexed"):
                if (
                    not isinstance(source_occurrence, int)
                    or isinstance(source_occurrence, bool)
                    or source_occurrence < 0
                    or not isinstance(target_occurrence, int)
                    or isinstance(target_occurrence, bool)
                    or target_occurrence < 0
                ):
                    rejected.append(
                        {"cueIndex": cue_id, "pair": pair, "reason": "invalid occurrence index"}
                    )
                    continue
                if len(find_occurrences(cases[cue_id]["source"], source)) <= source_occurrence:
                    rejected.append(
                        {"cueIndex": cue_id, "pair": pair, "reason": "source occurrence missing"}
                    )
                    continue
                if (
                    len(find_occurrences(cases[cue_id]["translation"], target, ignore_case=True))
                    <= target_occurrence
                ):
                    rejected.append(
                        {"cueIndex": cue_id, "pair": pair, "reason": "target occurrence missing"}
                    )
                    continue
            accepted_pairs.append([source, target])
            if pair_format.endswith("-indexed"):
                accepted_occurrence_alignments.append(
                    {
                        "source": source,
                        "target": target,
                        "sourceOccurrence": source_occurrence,
                        "targetOccurrence": target_occurrence,
                    }
                )
        generated[cue_id] = accepted_pairs
        occurrence_alignments[cue_id] = accepted_occurrence_alignments
    if set(generated) != expected_ids:
        missing = sorted(expected_ids - set(generated))
        raise ValueError(f"Gemini omitted correspondence ids: {missing}")
    return generated, occurrence_alignments, rejected


def generate_correspondence(args: argparse.Namespace) -> int:
    api_key = read_api_key(args.api_key_file)
    fixture = read_json(args.fixtures)
    cases = fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{args.fixtures}: cases must be a non-empty array")
    for index, case in enumerate(cases):
        for key in ("id", "source", "translation"):
            if not isinstance(case.get(key), str) or not case[key]:
                raise ValueError(f"{args.fixtures}: cases[{index}].{key} must be a non-empty string")
    if args.limit is not None:
        if args.limit <= 0:
            raise ValueError("limit must be positive")
        cases = cases[:args.limit]

    prompt_template = args.prompt.read_text(encoding="utf-8")
    if prompt_template.count(INPUT_MARKER) != 1:
        raise ValueError(f"{args.prompt}: expected exactly one {INPUT_MARKER} marker")
    if prompt_template.count(PAIR_FORMAT_MARKER) != 1:
        raise ValueError(f"{args.prompt}: expected exactly one {PAIR_FORMAT_MARKER} marker")
    checkpoint = args.checkpoint or args.output.with_suffix(".checkpoint.json")
    generated: dict[int, list[list[str]]] = {}
    occurrence_alignments: dict[int, list[dict[str, Any]]] = {}
    rejected: list[dict[str, Any]] = []
    raw_responses: list[dict[str, Any]] = []
    if checkpoint.is_file():
        checkpoint_data = read_json(checkpoint)
        checkpoint_pair_format = checkpoint_data.get("pairFormat", "named")
        if checkpoint_pair_format != args.pair_format:
            raise ValueError(
                f"{checkpoint}: pair format is {checkpoint_pair_format}, not {args.pair_format}"
            )
        generated = {
            int(cue_id): pairs for cue_id, pairs in checkpoint_data.get("generated", {}).items()
        }
        occurrence_alignments = {
            int(cue_id): alignments
            for cue_id, alignments in checkpoint_data.get("occurrenceAlignments", {}).items()
        }
        rejected = checkpoint_data.get("rejected", [])
        raw_responses = checkpoint_data.get("rawResponses", [])
        print(f"Loaded {len(generated)} checkpointed cues")

    next_request_at = 0.0
    for batch_number, cue_ids in enumerate(batches(len(cases), args.batch_size), start=1):
        cue_ids = [cue_id for cue_id in cue_ids if cue_id not in generated]
        if not cue_ids:
            continue
        if args.request_interval < 0:
            raise ValueError("request interval must not be negative")
        wait_seconds = next_request_at - time.monotonic()
        if wait_seconds > 0:
            print(f"Waiting {wait_seconds:.1f}s for Gemini quota pacing")
            time.sleep(wait_seconds)
        print(f"Generating batch {batch_number}: cues {cue_ids[0]}-{cue_ids[-1]}")
        inputs = [
            {
                "id": cue_id,
                "source": cases[cue_id]["source"],
                "translation": cases[cue_id]["translation"],
            }
            for cue_id in cue_ids
        ]
        pair_format_instruction = {
            "named": (
                'Return each pair as an object with exactly the fields "source" and "target". '
                'Example: {"id":7,"pairs":[{"source":"감정","target":"emotions"}]}'
            ),
            "tuple": (
                "Return each pair as a two-string JSON array "
                "[sourceSubstring,targetSubstring]. The first item is source and the second item "
                'is target. Example: {"id":7,"pairs":[["감정","emotions"]]}'
            ),
            "named-indexed": (
                'Return each pair as an object with exactly the fields "source", "target", '
                '"sourceOccurrence", and "targetOccurrence". Occurrence indexes are zero-based '
                "among substring matches in the complete cue text. Source matching is "
                "case-sensitive; target matching is case-insensitive to match the app. Use "
                "indexes to identify the intended matches when text repeats. Example: "
                '{"id":7,"pairs":[{"source":"공감","target":"empathy",'
                '"sourceOccurrence":1,"targetOccurrence":0}]}'
            ),
            "tuple-indexed": (
                "Return each pair as a four-value JSON array "
                "[sourceSubstring,targetSubstring,sourceOccurrence,targetOccurrence]. The first "
                "two items are strings. Occurrence indexes are zero-based integers among "
                "substring matches in the complete cue text. Source matching is case-sensitive; "
                "target matching is case-insensitive to match the app. Use indexes to identify "
                'the intended matches when text repeats. Example: {"id":7,'
                '"pairs":[["공감","empathy",1,0]]}'
            ),
        }[args.pair_format]
        prompt = (
            prompt_template
            .replace(PAIR_FORMAT_MARKER, pair_format_instruction)
            .replace(INPUT_MARKER, json.dumps(inputs, ensure_ascii=False))
        )
        batch_generated = None
        batch_occurrence_alignments: dict[int, list[dict[str, Any]]] = {}
        batch_rejected: list[dict[str, Any]] = []
        for attempt in range(1, 4):
            next_request_at = time.monotonic() + args.request_interval
            raw_response, output_items = request_gemini_correspondences(
                api_key, args.model, prompt, len(cue_ids), args.pair_format
            )
            try:
                batch_generated, batch_occurrence_alignments, batch_rejected = parse_correspondence_output(
                    output_items, set(cue_ids), cases, args.pair_format
                )
                raw_responses.append(raw_response)
                break
            except ValueError as error:
                if attempt == 3:
                    raise
                print(f"Retrying malformed correspondence batch: {error}")
        generated.update(batch_generated or {})
        occurrence_alignments.update(batch_occurrence_alignments or {})
        rejected.extend(batch_rejected)
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        checkpoint.write_text(
            json.dumps(
                {
                    "generated": generated,
                    "occurrenceAlignments": occurrence_alignments,
                    "rejected": rejected,
                    "rawResponses": raw_responses,
                    "pairFormat": args.pair_format,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        if args.html_output:
            render_correspondence_html(
                fixture, cases, generated, rejected, args.html_output,
                title="Gemini subtitle correspondence review (in progress)",
            )

    output_cases = []
    for cue_id, case in enumerate(cases):
        if cue_id not in generated:
            raise ValueError(f"Missing generated correspondence cue {cue_id}")
        output_case = {key: value for key, value in case.items() if key != "expectedPairs"}
        output_case["expectedPairs"] = generated[cue_id]
        if args.pair_format.endswith("-indexed"):
            output_case["expectedAlignments"] = occurrence_alignments.get(cue_id, [])
        output_cases.append(output_case)
    output = {
        "name": "subtitle-ko-en-word-correspondence-generated",
        "description": "Validated Korean-to-English learner corpus generated by Gemini in bounded batches.",
        "sourceLanguage": fixture.get("sourceLanguage", "Korean"),
        "targetLanguage": fixture.get("targetLanguage", "English"),
        "sourceFixture": str(args.fixtures),
        "model": args.model,
        "pairFormat": args.pair_format,
        "cases": output_cases,
        "rejectedPairs": rejected,
        "rawResponses": raw_responses,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.html_output:
        render_correspondence_html(
            fixture, cases, generated, rejected, args.html_output,
            title="Gemini subtitle correspondence review",
        )
    print(f"Wrote {args.output}")
    print(f"Generated {len(output_cases)} cues with {sum(len(c['expectedPairs']) for c in output_cases)} pairs")
    print(f"Rejected {len(rejected)} non-verbatim or malformed pairs")
    return 0


HTML_PALETTE = (
    "#ff6b6b",
    "#f6ad55",
    "#f6e05e",
    "#68d391",
    "#4fd1c5",
    "#63b3ed",
    "#b794f4",
    "#f687b3",
)


def allocate_first_unused_occurrence(
    text: str,
    substring: str,
    used_ranges: list[tuple[int, int]],
    ignore_case: bool = False,
) -> tuple[int, int, int] | None:
    for occurrence_index, (start, end) in enumerate(find_occurrences(text, substring, ignore_case)):
        if all(max(start, used_start) >= min(end, used_end) for used_start, used_end in used_ranges):
            return start, end, occurrence_index
    return None


def sanitize_pairs_for_rendering(case: dict[str, Any], pairs: list[list[str]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    accepted: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []
    used_source_ranges: list[tuple[int, int]] = []
    used_target_ranges: list[tuple[int, int]] = []
    for pair in pairs:
        if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(item, str) and item for item in pair):
            rejected.append({"pair": pair, "reason": "malformed pair"})
            continue
        source, target = pair
        source_occurrence = allocate_first_unused_occurrence(
            case["source"], source, used_source_ranges
        )
        target_occurrence = allocate_first_unused_occurrence(
            case["translation"], target, used_target_ranges, ignore_case=True
        )
        if source_occurrence is None:
            rejected.append({"pair": pair, "reason": "missing or overlapping source substring"})
            continue
        if target_occurrence is None:
            rejected.append({"pair": pair, "reason": "missing or overlapping target substring"})
            continue
        color = HTML_PALETTE[len(accepted) % len(HTML_PALETTE)]
        accepted_pair = {
            "source": source,
            "target": target,
            "sourceRange": source_occurrence[:2],
            "targetRange": target_occurrence[:2],
            "sourceOccurrence": source_occurrence[2],
            "targetOccurrence": target_occurrence[2],
            "color": color,
        }
        accepted.append(accepted_pair)
        used_source_ranges.append(source_occurrence[:2])
        used_target_ranges.append(target_occurrence[:2])
    return accepted, rejected


def render_colored_text(text: str, accepted_pairs: list[dict[str, Any]], range_key: str) -> str:
    ranges = sorted(
        (pair[range_key][0], pair[range_key][1], pair["color"])
        for pair in accepted_pairs
    )
    output: list[str] = []
    position = 0
    for start, end, color in ranges:
        output.append(html.escape(text[position:start]))
        output.append(
            f'<span class="matched" style="color:{color}">{html.escape(text[start:end])}</span>'
        )
        position = end
    output.append(html.escape(text[position:]))
    return "".join(output)


def render_correspondence_html(
    fixture: dict[str, Any],
    cases: list[dict[str, Any]],
    generated: dict[int, list[list[str]]],
    rejected_pairs: list[dict[str, Any]],
    output: Path,
    title: str,
) -> None:
    rendered_cases: list[str] = []
    accepted_count = 0
    sanitized_rejected_count = 0
    for cue_id, case in enumerate(cases):
        pairs = generated.get(cue_id)
        if pairs is None:
            rendered_cases.append(
                f'<section class="cue pending"><h2>{html.escape(case["id"])}</h2>'
                '<p class="status">Pending Gemini response</p></section>'
            )
            continue
        accepted, sanitized_rejected = sanitize_pairs_for_rendering(case, pairs)
        accepted_count += len(accepted)
        sanitized_rejected_count += len(sanitized_rejected)
        pair_chips = "".join(
            f'<span class="pair" style="border-color:{pair["color"]}">'
            f'{html.escape(pair["source"])} <b>&rarr;</b> {html.escape(pair["target"])}</span>'
            for pair in accepted
        )
        rejected_html = ""
        if sanitized_rejected:
            rejected_html = '<details><summary>Rejected tuples</summary><ul>' + "".join(
                f'<li>{html.escape(json.dumps(item["pair"], ensure_ascii=False))}: '
                f'{html.escape(item["reason"])}</li>'
                for item in sanitized_rejected
            ) + "</ul></details>"
        rendered_cases.append(
            f'<section class="cue"><h2>{cue_id + 1}. {html.escape(case["id"])}</h2>'
            f'<p class="counts">{len(accepted)} accepted / {len(pairs)} returned</p>'
            f'<div class="subtitle source">{render_colored_text(case["source"], accepted, "sourceRange")}</div>'
            f'<div class="subtitle translation">{render_colored_text(case["translation"], accepted, "targetRange")}</div>'
            f'<div class="pairs">{pair_chips}</div>{rejected_html}</section>'
        )
    completed = len(generated)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "<!doctype html><html><head><meta charset=\"utf-8\">"
        f"<title>{html.escape(title)}</title>"
        "<style>"
        "body{background:#111827;color:#d1d5db;font:15px system-ui,sans-serif;margin:0;padding:24px}"
        "main{max-width:1200px;margin:auto}.summary{position:sticky;top:0;background:#111827ee;padding:8px 0 14px;z-index:2}"
        ".cue{background:#1f2937;border:1px solid #374151;border-radius:10px;margin:14px 0;padding:14px}"
        ".pending{opacity:.55}h1{margin:0 0 6px}h2{color:#f3f4f6;font-size:15px;margin:0 0 4px}"
        ".counts,.status{color:#9ca3af;margin:0 0 10px}.subtitle{background:#030712;border-radius:5px;font-size:20px;line-height:1.35;margin:5px 0;padding:8px;white-space:pre-wrap}"
        ".source{font-size:17px}.matched{font-weight:700}.pairs{display:flex;flex-wrap:wrap;gap:6px;margin-top:9px}"
        ".pair{background:#111827;border:1px solid;border-radius:999px;padding:3px 8px}details{margin-top:8px;color:#fca5a5}"
        "</style></head><body><main>"
        f'<div class="summary"><h1>{html.escape(title)}</h1>'
        f"<div>{completed}/{len(cases)} cues generated, {accepted_count} accepted mappings, "
        f"{sanitized_rejected_count + len(rejected_pairs)} rejected mappings</div></div>"
        + "".join(rendered_cases)
        + "</main></body></html>",
        encoding="utf-8",
    )


def render_correspondence_html_command(args: argparse.Namespace) -> int:
    fixture = read_json(args.fixtures)
    cases = fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{args.fixtures}: cases must be a non-empty array")
    input_data = read_json(args.input)
    if isinstance(input_data.get("generated"), dict):
        generated = {
            int(cue_id): pairs for cue_id, pairs in input_data["generated"].items()
        }
        rejected = input_data.get("rejected", [])
        title = "Gemini subtitle correspondence review (checkpoint)"
    elif isinstance(input_data.get("cases"), list):
        generated = {
            cue_id: case.get("expectedPairs", [])
            for cue_id, case in enumerate(input_data["cases"])
        }
        rejected = input_data.get("rejectedPairs", [])
        title = "Gemini subtitle correspondence review"
    else:
        raise ValueError(f"{args.input}: expected generated checkpoint or correspondence corpus")
    render_correspondence_html(fixture, cases, generated, rejected, args.output, title)
    print(f"Wrote {args.output}")
    return 0


def load_generated_pairs(path: Path) -> dict[int, list[list[str]]]:
    input_data = read_json(path)
    if isinstance(input_data.get("generated"), dict):
        return {
            int(cue_id): pairs for cue_id, pairs in input_data["generated"].items()
        }
    if isinstance(input_data.get("cases"), list):
        return {
            cue_id: case.get("expectedPairs", [])
            for cue_id, case in enumerate(input_data["cases"])
        }
    raise ValueError(f"{path}: expected generated checkpoint or correspondence corpus")


def ranges_overlap(first: tuple[int, int], second: tuple[int, int]) -> bool:
    return max(first[0], second[0]) < min(first[1], second[1])


def local_omission_check(
    case: dict[str, Any],
    returned_pairs: list[list[str]],
    missing_pair: list[str],
) -> dict[str, Any]:
    source, target = missing_pair
    accepted, rejected = sanitize_pairs_for_rendering(case, returned_pairs)
    used_source_ranges = [tuple(pair["sourceRange"]) for pair in accepted]
    used_target_ranges = [tuple(pair["targetRange"]) for pair in accepted]
    source_occurrences = find_occurrences(case["source"], source)
    target_occurrences = find_occurrences(case["translation"], target, ignore_case=True)
    source_available = allocate_first_unused_occurrence(
        case["source"], source, used_source_ranges
    )
    target_available = allocate_first_unused_occurrence(
        case["translation"], target, used_target_ranges, ignore_case=True
    )
    source_conflicts = [
        [pair["source"], pair["target"]]
        for pair in accepted
        if any(ranges_overlap(tuple(pair["sourceRange"]), occurrence)
               for occurrence in source_occurrences)
    ]
    target_conflicts = [
        [pair["source"], pair["target"]]
        for pair in accepted
        if any(ranges_overlap(tuple(pair["targetRange"]), occurrence)
               for occurrence in target_occurrences)
    ]
    return {
        "sourceVerbatim": bool(source_occurrences),
        "targetVerbatimIgnoreCase": bool(target_occurrences),
        "sourceOccurrenceCount": len(source_occurrences),
        "targetOccurrenceCountIgnoreCase": len(target_occurrences),
        "sourceSpanAvailable": source_available is not None,
        "targetSpanAvailable": target_available is not None,
        "mechanicallyAddable": source_available is not None and target_available is not None,
        "sourceConflicts": source_conflicts,
        "targetConflicts": target_conflicts,
        "returnedTupleRejects": rejected,
    }


def verify_diagnostic_hypothesis(local_check: dict[str, Any], hypothesis: dict[str, Any]) -> list[str]:
    explanation = " ".join(
        str(hypothesis.get(field, ""))
        for field in ("likelyCause", "responsibleInstruction", "explanation")
    ).casefold()
    notes: list[str] = []
    if any(word in explanation for word in ("verbatim", "substring", "not appear", "missing")):
        if local_check["sourceVerbatim"] and local_check["targetVerbatimIgnoreCase"]:
            notes.append("AI cites substring availability, but both reviewed substrings exist verbatim.")
    if "overlap" in explanation and local_check["mechanicallyAddable"]:
        notes.append("AI cites overlap, but the local allocator has unused spans on both sides.")
    if "repeat" in explanation or "duplicate" in explanation:
        if (
            local_check["sourceOccurrenceCount"] <= 1
            and local_check["targetOccurrenceCountIgnoreCase"] <= 1
        ):
            notes.append("AI cites repetition, but neither reviewed substring repeats.")
    if hypothesis.get("shouldHaveReturned") and not local_check["mechanicallyAddable"]:
        notes.append("AI recommends adding the pair, but the current local allocator has a span conflict.")
    if not notes:
        notes.append("No direct contradiction found by local mechanical checks.")
    return notes


def omission_diagnostic_schema(pair_count: int) -> dict[str, Any]:
    return {
        "type": "array",
        "minItems": pair_count,
        "maxItems": pair_count,
        "items": {
            "type": "object",
            "properties": {
                "caseIndex": {"type": "integer", "minimum": 0},
                "pairIndex": {"type": "integer", "minimum": 0},
                "shouldHaveReturned": {"type": "boolean"},
                "likelyCause": {"type": "string"},
                "responsibleInstruction": {"type": "string"},
                "explanation": {"type": "string"},
                "minimalPromptChange": {"type": "string"},
            },
            "required": [
                "caseIndex",
                "pairIndex",
                "shouldHaveReturned",
                "likelyCause",
                "responsibleInstruction",
                "explanation",
                "minimalPromptChange",
            ],
        },
    }


def build_omission_diagnostic_prompt(
    diagnostic_cases: list[dict[str, Any]],
) -> str:
    diagnostic_input = []
    for case_index, case in enumerate(diagnostic_cases):
        diagnostic_input.append(
            {
                "caseIndex": case_index,
                "source": case["source"],
                "translation": case["translation"],
                "returnedPairs": case["returnedPairs"],
                "reviewedPairsMissingFromReturnedOutput": [
                    {"pairIndex": pair_index, "pair": omission["pair"]}
                    for pair_index, omission in enumerate(case["omissions"])
                ],
            }
        )
    return (
        "Diagnose omissions from a subtitle word-correspondence generation result. "
        "Do not regenerate mappings. For each reviewed missing pair, decide whether the "
        "generation prompt should have caused it to be returned. If it should not be "
        "returned, explain whether an existing returned pair supersedes it or whether it "
        "would violate a prompt constraint. If it should have been returned, identify the "
        "instruction most likely to have discouraged or distracted the generator. Quote "
        "or precisely name the responsible instruction. Suggest the smallest prompt change "
        "that could recover useful coverage without encouraging broad or false mappings. "
        "Treat your explanation as a hypothesis: do not claim certainty about hidden model "
        "reasoning. Preserve each caseIndex and pairIndex exactly.\n\n"
        "The generation prompt's relevant numbered rules were:\n"
        "R1: MAXIMIZE learner-useful coverage and perform a final unmatched-content audit.\n"
        "R2: Prefer atomic exact substrings; use two-word phrases only when needed and "
        "three-word chunks only when necessary.\n"
        "R3: Copy source and target text verbatim as contiguous subtitle substrings.\n"
        "R4: Skip unclear or potentially misleading mappings.\n"
        "R5: Do not return overlapping mappings; each source and target span belongs to "
        "at most one pair.\n"
        "R6: Repeated text in distinct places is allowed and should be returned separately "
        "in reading order.\n\nDiagnostic input JSON:\n"
        + json.dumps(diagnostic_input, ensure_ascii=False)
    )


def omission_diagnostics_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Gemini omission diagnostics",
        "",
        f"- Fixture: `{report['fixtures']}`",
        f"- Generated input: `{report['input']}`",
        f"- Cases diagnosed: `{len(report['cases'])}`",
        f"- Passes requested: `{report['passes']}`",
        "",
        "Gemini explanations are hypotheses. Local checks are the verification layer.",
        "",
    ]
    for case in report["cases"]:
        lines.extend(
            [
                f"## {case['cueIndex'] + 1}. {case['id']}",
                "",
                f"**Source:** {case['source']}",
                "",
                f"**Translation:** {case['translation']}",
                "",
                "**Returned pairs:** "
                + ", ".join(f"`{source} -> {target}`" for source, target in case["returnedPairs"]),
                "",
            ]
        )
        for omission in case["omissions"]:
            lines.extend(
                [
                    f"### Missing: `{omission['pair'][0]} -> {omission['pair'][1]}`",
                    "",
                    "**Local audit:** `" + json.dumps(
                        omission["localCheck"], ensure_ascii=False
                    ) + "`",
                    "",
                ]
            )
            for hypothesis in omission["hypotheses"]:
                lines.extend(
                    [
                        f"**Pass {hypothesis['pass']}:** "
                        + ("should return" if hypothesis["shouldHaveReturned"] else "may skip"),
                        "",
                        f"- Likely cause: {hypothesis['likelyCause']}",
                        f"- Responsible instruction: {hypothesis['responsibleInstruction']}",
                        f"- Explanation: {hypothesis['explanation']}",
                        f"- Minimal prompt change: {hypothesis['minimalPromptChange']}",
                        "- Verification: " + " ".join(hypothesis["verificationNotes"]),
                        "",
                    ]
                )
    return "\n".join(lines)


def write_omission_diagnostics(report: dict[str, Any], output: Path, markdown_output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.write_text(omission_diagnostics_markdown(report), encoding="utf-8")


def diagnose_omissions(args: argparse.Namespace) -> int:
    if args.limit <= 0 or args.passes <= 0:
        raise ValueError("limit and passes must be positive")
    if args.request_interval < 0:
        raise ValueError("request interval must not be negative")
    fixture = read_json(args.fixtures)
    cases = fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{args.fixtures}: cases must be a non-empty array")
    generated = load_generated_pairs(args.input)
    api_key = read_api_key(args.api_key_file)
    diagnostic_cases: list[dict[str, Any]] = []
    for cue_id, returned_pairs in generated.items():
        case = cases[cue_id]
        returned_keys = {(source, target.casefold()) for source, target in returned_pairs}
        missing_pairs = [
            pair for pair in case.get("expectedPairs", [])
            if (pair[0], pair[1].casefold()) not in returned_keys
        ]
        if not missing_pairs:
            continue
        omissions = [
            {
                "pair": pair,
                "localCheck": local_omission_check(case, returned_pairs, pair),
                "hypotheses": [],
            }
            for pair in missing_pairs
        ]
        diagnostic_cases.append(
            {
                "cueIndex": cue_id,
                "id": case["id"],
                "source": case["source"],
                "translation": case["translation"],
                "returnedPairs": returned_pairs,
                "omissions": omissions,
            }
        )
    diagnostic_cases.sort(
        key=lambda case: (
            -sum(omission["localCheck"]["mechanicallyAddable"] for omission in case["omissions"]),
            -len(case["omissions"]),
            case["cueIndex"],
        )
    )
    diagnostic_cases = diagnostic_cases[:args.limit]
    markdown_output = args.markdown_output or args.output.with_suffix(".md")
    report = {
        "name": "gemini-omission-diagnostics",
        "description": "Gemini self-explanations annotated with deterministic local checks.",
        "fixtures": str(args.fixtures),
        "input": str(args.input),
        "prompt": str(args.prompt),
        "model": args.model,
        "passes": args.passes,
        "cases": diagnostic_cases,
        "rawResponses": [],
    }
    if args.output.is_file():
        existing_report = read_json(args.output)
        for key in ("fixtures", "input", "prompt", "model"):
            if existing_report.get(key) != report[key]:
                raise ValueError(f"{args.output}: existing diagnostic {key} does not match")
        if len(existing_report.get("cases", [])) != len(diagnostic_cases):
            raise ValueError(f"{args.output}: existing diagnostic case count does not match")
        report = existing_report
        report["passes"] = args.passes
        diagnostic_cases = report["cases"]
        print(f"Loaded {len(report['rawResponses'])} completed diagnostic pass(es)")
    next_request_at = 0.0
    for pass_number in range(len(report["rawResponses"]) + 1, args.passes + 1):
        wait_seconds = next_request_at - time.monotonic()
        if wait_seconds > 0:
            print(f"Waiting {wait_seconds:.1f}s for Gemini quota pacing")
            time.sleep(wait_seconds)
        print(f"Diagnosing pass {pass_number}: {len(diagnostic_cases)} cues")
        prompt = build_omission_diagnostic_prompt(diagnostic_cases)
        next_request_at = time.monotonic() + args.request_interval
        expected_indexes = {
            (case_index, pair_index)
            for case_index, diagnostic_case in enumerate(diagnostic_cases)
            for pair_index in range(len(diagnostic_case["omissions"]))
        }
        raw_response, hypotheses = request_gemini_json(
            api_key, args.model, prompt, omission_diagnostic_schema(len(expected_indexes))
        )
        report["rawResponses"].append(raw_response)
        by_index = {
            (hypothesis.get("caseIndex"), hypothesis.get("pairIndex")): hypothesis
            for hypothesis in hypotheses
            if isinstance(hypothesis, dict)
        }
        if set(by_index) != expected_indexes:
            raise ValueError(f"Gemini returned invalid omission diagnostic indexes: {by_index}")
        for case_index, diagnostic_case in enumerate(diagnostic_cases):
            for pair_index, omission in enumerate(diagnostic_case["omissions"]):
                hypothesis = {"pass": pass_number, **by_index[(case_index, pair_index)]}
                hypothesis["verificationNotes"] = verify_diagnostic_hypothesis(
                    omission["localCheck"], hypothesis
                )
                omission["hypotheses"].append(hypothesis)
        write_omission_diagnostics(report, args.output, markdown_output)
    print(f"Wrote {args.output}")
    print(f"Wrote {markdown_output}")
    return 0


def find_occurrences(text: str, substring: str, ignore_case: bool = False) -> list[tuple[int, int]]:
    searchable_text = text.casefold() if ignore_case else text
    searchable_substring = substring.casefold() if ignore_case else substring
    occurrences: list[tuple[int, int]] = []
    start = 0
    while True:
        index = searchable_text.find(searchable_substring, start)
        if index < 0:
            return occurrences
        occurrences.append((index, index + len(substring)))
        start = index + 1


def can_allocate_non_overlapping(text: str, values: list[str], ignore_case: bool = False) -> bool:
    choices = sorted(
        (len(find_occurrences(text, value, ignore_case)), value) for value in values
    )

    def allocate(choice_index: int, used_ranges: list[tuple[int, int]]) -> bool:
        if choice_index == len(choices):
            return True
        _, value = choices[choice_index]
        for value_range in find_occurrences(text, value, ignore_case):
            if all(
                max(value_range[0], used_range[0]) >= min(value_range[1], used_range[1])
                for used_range in used_ranges
            ) and allocate(choice_index + 1, [*used_ranges, value_range]):
                return True
        return False

    return allocate(0, [])


def audit_correspondence(args: argparse.Namespace) -> int:
    corpus = read_json(args.input)
    cases = corpus.get("cases")
    if not isinstance(cases, list):
        raise ValueError(f"{args.input}: cases must be an array")

    empty_cue_ids: list[str] = []
    broad_pairs: list[dict[str, Any]] = []
    overlap_conflicts: list[dict[str, Any]] = []
    pair_count = 0
    for case in cases:
        pairs = case.get("expectedPairs")
        if not isinstance(pairs, list):
            raise ValueError(f"{args.input}: {case.get('id')}: expectedPairs must be an array")
        pair_count += len(pairs)
        if not pairs:
            empty_cue_ids.append(case["id"])
        for pair in pairs:
            if not isinstance(pair, list) or len(pair) != 2:
                raise ValueError(f"{args.input}: {case.get('id')}: malformed pair: {pair!r}")
            if word_count(pair[0]) > 3 or word_count(pair[1]) > 3:
                broad_pairs.append({"cueId": case["id"], "pair": pair})
        for side, field in enumerate(("source", "translation")):
            values = [pair[side] for pair in pairs]
            if not can_allocate_non_overlapping(
                case[field], values, ignore_case=field == "translation"
            ):
                overlap_conflicts.append({"cueId": case["id"], "side": field, "values": values})

    rejected_pairs = corpus.get("rejectedPairs", [])
    rejected_reasons = Counter(pair.get("reason", "unknown") for pair in rejected_pairs)
    audit = {
        "input": str(args.input),
        "cases": len(cases),
        "pairs": pair_count,
        "rejectedPairs": len(rejected_pairs),
        "rejectedReasons": dict(rejected_reasons),
        "emptyCueIds": empty_cue_ids,
        "broadPairs": broad_pairs,
        "overlapConflicts": overlap_conflicts,
    }
    print(f"Cases: {audit['cases']}")
    print(f"Pairs: {audit['pairs']}")
    print(f"Rejected pairs: {audit['rejectedPairs']}")
    print(f"Empty cues: {len(empty_cue_ids)}")
    print(f"Broad pairs: {len(broad_pairs)}")
    print(f"Non-overlap conflicts: {len(overlap_conflicts)}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {args.output}")
    return 0


def app_preflight(args: argparse.Namespace) -> int:
    api_key = read_api_key(args.api_key_file)
    fixture = read_json(args.fixtures)
    cases = fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{args.fixtures}: cases must be a non-empty array")
    prompt_template = args.prompt.read_text(encoding="utf-8")
    if prompt_template.count(INPUT_MARKER) != 1:
        raise ValueError(f"{args.prompt}: expected exactly one {INPUT_MARKER} marker")
    if prompt_template.count(PAIR_FORMAT_MARKER) != 1:
        raise ValueError(f"{args.prompt}: expected exactly one {PAIR_FORMAT_MARKER} marker")

    pair_format_instruction = (
        "Return each pair as a two-string JSON array "
        "[sourceSubstring,targetSubstring]. The first item is source and the second item "
        'is target. Example: {"id":7,"pairs":[["감정","emotions"]]}'
    )
    generated: dict[int, list[list[str]]] = {}
    rejected: list[dict[str, Any]] = []
    raw_responses: list[dict[str, Any]] = []
    for cue_id in range(len(cases)):
        print(f"Preflighting cue {cue_id}: {cases[cue_id]['id']}")
        inputs = [
            {
                "id": 0,
                "source": cases[cue_id]["source"],
                "translation": cases[cue_id]["translation"],
            }
        ]
        prompt = (
            prompt_template
            .replace(PAIR_FORMAT_MARKER, pair_format_instruction)
            .replace(INPUT_MARKER, json.dumps(inputs, ensure_ascii=False))
        )
        raw_response, output_items = request_gemini_correspondences(
            api_key, args.model, prompt, 1, "tuple"
        )
        raw_responses.append(raw_response)
        parsed, _, cue_rejected = parse_correspondence_output(
            output_items, {0}, [cases[cue_id]], "tuple"
        )
        generated[cue_id] = parsed[0]
        rejected.extend(
            {"fixtureCueIndex": cue_id, **rejected_pair}
            for rejected_pair in cue_rejected
        )

    failures: list[str] = []
    output_cases = []
    for cue_id, case in enumerate(cases):
        pairs = generated[cue_id]
        minimum_pairs = case.get("minimumPreflightPairs", 1)
        if len(pairs) < minimum_pairs:
            failures.append(
                f"{case['id']}: returned {len(pairs)} pairs, expected at least {minimum_pairs}"
            )
        targets = Counter(target.casefold() for _, target in pairs)
        for target, minimum_count in case.get("requiredPreflightTargets", {}).items():
            if targets[target.casefold()] < minimum_count:
                failures.append(
                    f"{case['id']}: target {target!r} returned {targets[target.casefold()]} "
                    f"time(s), expected at least {minimum_count}"
                )
        output_cases.append({**case, "preflightPairs": pairs})
    if rejected:
        failures.append(f"Gemini returned {len(rejected)} non-verbatim or malformed pair(s)")

    output = {
        "name": "app-preflight",
        "description": "Fast live-schema regression for the app-oriented tuple alignment path.",
        "model": args.model,
        "fixtures": str(args.fixtures),
        "prompt": str(args.prompt),
        "cases": output_cases,
        "rejectedPairs": rejected,
        "failures": failures,
        "rawResponses": raw_responses,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.output}")
    print(f"Preflight pairs: {sum(len(pairs) for pairs in generated.values())}")
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print("App preflight passed")
    return 0


def benchmark_metrics(
    cases: list[dict[str, Any]],
    generated: dict[int, list[list[str]]],
    rejected: list[dict[str, Any]],
) -> dict[str, int]:
    expected_count = 0
    matched_expected_count = 0
    for cue_id, case in enumerate(cases):
        expected = Counter(
            (source, target.casefold()) for source, target in case.get("expectedPairs", [])
        )
        returned = Counter(
            (source, target.casefold()) for source, target in generated.get(cue_id, [])
        )
        expected_count += sum(expected.values())
        matched_expected_count += sum((expected & returned).values())
    pair_count = sum(len(pairs) for pairs in generated.values())
    return {
        "cues": len(cases),
        "requests": 0,
        "acceptedPairs": pair_count,
        "rejectedPairs": len(rejected),
        "emptyCues": sum(not generated.get(cue_id) for cue_id in range(len(cases))),
        "expectedPairs": expected_count,
        "matchedExpectedPairs": matched_expected_count,
        "missingExpectedPairs": expected_count - matched_expected_count,
    }


def benchmark_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# App alignment batch-size benchmark",
        "",
        f"- Model: `{report['model']}`",
        f"- Fixtures: `{report['fixtures']}`",
        f"- Prompt: `{report['prompt']}`",
        "",
        "| Batch size | Requests | Accepted | Rejected | Empty cues | Expected hits | Missing expected | Status |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for result in report["results"]:
        summary = result.get("metrics", {})
        status = result.get("error", "ok")
        if status != "ok":
            status = status.splitlines()[0][:160]
        lines.append(
            "| "
            + " | ".join(
                str(value)
                for value in (
                    result["batchSize"],
                    summary.get("requests", "-"),
                    summary.get("acceptedPairs", "-"),
                    summary.get("rejectedPairs", "-"),
                    summary.get("emptyCues", "-"),
                    (
                        f"{summary.get('matchedExpectedPairs', '-')}/"
                        f"{summary.get('expectedPairs', '-')}"
                    ),
                    summary.get("missingExpectedPairs", "-"),
                    status,
                )
            )
            + " |"
        )
    lines.extend(
        [
            "",
            "Expected-pair hits compare Gemini output with the curated fixture. They are a useful",
            "relative quality signal, not a complete semantic correctness score.",
            "",
        ]
    )
    return "\n".join(lines)


def write_batch_benchmark(report: dict[str, Any], output: Path, markdown_output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_output.parent.mkdir(parents=True, exist_ok=True)
    markdown_output.write_text(benchmark_markdown(report), encoding="utf-8")


def benchmark_app_batches(args: argparse.Namespace) -> int:
    api_key = read_api_key(args.api_key_file)
    fixture = read_json(args.fixtures)
    cases = fixture.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"{args.fixtures}: cases must be a non-empty array")
    for index, case in enumerate(cases):
        for key in ("id", "source", "translation"):
            if not isinstance(case.get(key), str) or not case[key]:
                raise ValueError(f"{args.fixtures}: cases[{index}].{key} must be a non-empty string")
    if args.limit is not None:
        if args.limit <= 0:
            raise ValueError("limit must be positive")
        cases = cases[:args.limit]
    prompt_template = args.prompt.read_text(encoding="utf-8")
    if prompt_template.count(INPUT_MARKER) != 1:
        raise ValueError(f"{args.prompt}: expected exactly one {INPUT_MARKER} marker")
    if args.request_interval < 0:
        raise ValueError("request interval must not be negative")
    if not args.batch_sizes or any(size <= 0 for size in args.batch_sizes):
        raise ValueError("batch sizes must be positive")

    markdown_output = args.markdown_output or args.output.with_suffix(".md")
    report = {
        "name": "app-alignment-batch-benchmark",
        "timestamp": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "model": args.model,
        "fixtures": str(args.fixtures),
        "prompt": str(args.prompt),
        "results": [],
    }
    next_request_at = 0.0
    for batch_size in args.batch_sizes:
        print(f"Benchmarking app alignment batch size {batch_size}")
        generated: dict[int, list[list[str]]] = {}
        occurrence_alignments: dict[int, list[dict[str, Any]]] = {}
        rejected: list[dict[str, Any]] = []
        request_count = 0
        result: dict[str, Any] = {"batchSize": batch_size}
        try:
            for cue_ids in batches(len(cases), batch_size):
                wait_seconds = next_request_at - time.monotonic()
                if wait_seconds > 0:
                    print(f"Waiting {wait_seconds:.1f}s for Gemini quota pacing")
                    time.sleep(wait_seconds)
                print(f"  Aligning cues {cue_ids[0]}-{cue_ids[-1]}")
                inputs = [
                    {
                        "id": cue_id,
                        "source": cases[cue_id]["source"],
                        "translation": cases[cue_id]["translation"],
                    }
                    for cue_id in cue_ids
                ]
                prompt = prompt_template.replace(
                    INPUT_MARKER, json.dumps(inputs, ensure_ascii=False)
                )
                next_request_at = time.monotonic() + args.request_interval
                _, output_items = request_gemini_correspondences(
                    api_key, args.model, prompt, len(cue_ids), "tuple"
                )
                request_count += 1
                batch_generated, batch_occurrences, batch_rejected = parse_correspondence_output(
                    output_items, set(cue_ids), cases, "tuple"
                )
                generated.update(batch_generated)
                occurrence_alignments.update(batch_occurrences)
                rejected.extend(batch_rejected)
            summary = benchmark_metrics(cases, generated, rejected)
            summary["requests"] = request_count
            result.update(
                {
                    "metrics": summary,
                    "generated": generated,
                    "occurrenceAlignments": occurrence_alignments,
                    "rejected": rejected,
                }
            )
        except (ValueError, RuntimeError, urllib.error.URLError) as error:
            result["error"] = str(error)
            summary = benchmark_metrics(cases, generated, rejected)
            summary["requests"] = request_count
            result.update(
                {
                    "metrics": summary,
                    "generated": generated,
                    "occurrenceAlignments": occurrence_alignments,
                    "rejected": rejected,
                }
            )
        report["results"].append(result)
        write_batch_benchmark(report, args.output, markdown_output)
        print(json.dumps(result["metrics"], indent=2))
        if result.get("error"):
            print(f"  FAILED: {result['error']}", file=sys.stderr)
            if "GenerateRequestsPerDay" in result["error"]:
                print("Stopping benchmark because the daily Gemini request quota is exhausted.")
                return 2
            if "PERMISSION_DENIED" in result["error"] or "SERVICE_DISABLED" in result["error"]:
                print("Stopping benchmark because the Gemini API is not available for this key.")
                return 2
    print(f"Wrote {args.output}")
    print(f"Wrote {markdown_output}")
    return 0


def prepare_srt_fixture(args: argparse.Namespace) -> int:
    api_key = read_api_key(args.api_key_file)
    cues = parse_srt(args.srt)
    prompt_template = args.translation_prompt.read_text(encoding="utf-8")
    for marker in (INPUT_MARKER, "{{SOURCE_LANGUAGE}}", "{{TARGET_LANGUAGE}}"):
        if prompt_template.count(marker) != 1:
            raise ValueError(f"{args.translation_prompt}: expected exactly one {marker} marker")

    translations: list[str | None] = [None] * len(cues)
    raw_batches: list[dict[str, Any]] = []
    for batch_number, cue_ids in enumerate(batches(len(cues), args.batch_size), start=1):
        print(f"Translating batch {batch_number}: cues {cue_ids[0]}-{cue_ids[-1]}")
        prompt = render_translation_prompt(
            prompt_template, cues, cue_ids, args.source_language, args.target_language
        )
        raw_response, output_lines = request_gemini(api_key, args.model, prompt, len(cue_ids))
        raw_batches.append(raw_response)
        for output_line in output_lines:
            cue_id, translation = parse_tagged_text(output_line)
            if cue_id not in cue_ids or translations[cue_id] is not None or not translation.strip():
                raise ValueError(f"Gemini returned an invalid or duplicate translation id: {cue_id}")
            translations[cue_id] = translation

    if any(translation is None for translation in translations):
        raise ValueError("Gemini omitted one or more translated SRT cues")
    fixture_cases = []
    for cue, translation in zip(cues, translations):
        fixture_cases.append({**cue, "translation": translation, "expectedPairs": []})
    fixture = {
        "name": args.output.stem,
        "description": f"Bilingual working fixture generated from source-only SRT {args.srt}",
        "sourceLanguage": args.source_language,
        "targetLanguage": args.target_language,
        "sourceSrt": str(args.srt),
        "model": args.model,
        "cases": fixture_cases,
        "rawTranslationResponses": raw_batches,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.output} with {len(fixture_cases)} translated cues")
    return 0


def word_count(text: str) -> int:
    return len(WORD.findall(text))


def validate_response(cases: list[dict[str, Any]], output_lines: list[str]) -> list[dict[str, Any]]:
    rows = [
        {
            "id": case["id"],
            "source": case["source"],
            "translation": case["translation"],
            "notes": case.get("notes", ""),
            "expectedPairs": case.get("expectedPairs", []),
            "pairs": [],
            "flags": [],
        }
        for case in cases
    ]
    seen_ids: set[int] = set()
    for output_line in output_lines:
        if not isinstance(output_line, str):
            continue
        match = TAGGED_TEXT.match(output_line)
        if not match:
            continue
        cue_id = int(match.group(1))
        if cue_id < 0 or cue_id >= len(rows) or cue_id in seen_ids:
            continue
        seen_ids.add(cue_id)
        row = rows[cue_id]
        try:
            pairs = json.loads(match.group(2))
        except json.JSONDecodeError as error:
            row["flags"].append(f"malformed pairs JSON: {error}")
            continue
        if not isinstance(pairs, list):
            row["flags"].append("pairs payload is not an array")
            continue
        for pair in pairs:
            if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(item, str) for item in pair):
                row["flags"].append(f"malformed pair: {pair!r}")
                continue
            source, target = pair
            flags: list[str] = []
            if source not in row["source"]:
                flags.append("source substring missing")
            if target.casefold() not in row["translation"].casefold():
                flags.append("target substring missing")
            if word_count(source) > 3 or word_count(target) > 3:
                flags.append("broad pair")
            row["pairs"].append({"source": source, "target": target, "flags": flags})
    for cue_id, row in enumerate(rows):
        if cue_id not in seen_ids:
            row["flags"].append("missing cue response")
        returned = {(pair["source"], pair["target"].casefold()) for pair in row["pairs"]}
        row["missingExpectedPairs"] = [
            pair for pair in row["expectedPairs"]
            if (pair[0], pair[1].casefold()) not in returned
        ]
    return rows


def metrics(rows: list[dict[str, Any]]) -> dict[str, int]:
    pairs = [pair for row in rows for pair in row["pairs"]]
    return {
        "cases": len(rows),
        "pairs": len(pairs),
        "invalidPairs": sum(bool(pair["flags"]) for pair in pairs),
        "broadPairs": sum("broad pair" in pair["flags"] for pair in pairs),
        "missingCueResponses": sum("missing cue response" in row["flags"] for row in rows),
        "expectedPairs": sum(len(row["expectedPairs"]) for row in rows),
        "missingExpectedPairs": sum(len(row["missingExpectedPairs"]) for row in rows),
    }


def escape_table(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def markdown_report(result: dict[str, Any]) -> str:
    summary = result["metrics"]
    lines = [
        f"# Gemini subtitle prompt lab: {result['name']}",
        "",
        f"- Model: `{result['model']}`",
        f"- Fixtures: `{result['fixtures']}`",
        f"- Prompt: `{result['prompt']}`",
        f"- Cases: `{summary['cases']}`",
        f"- Returned pairs: `{summary['pairs']}`",
        f"- Invalid pairs: `{summary['invalidPairs']}`",
        f"- Broad pairs: `{summary['broadPairs']}`",
        f"- Missing cue responses: `{summary['missingCueResponses']}`",
        f"- Missing expected pairs: `{summary['missingExpectedPairs']}/{summary['expectedPairs']}`",
        "",
        "| Case | Source | Translation | Returned pairs | Missing expected | Flags |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for row in result["rows"]:
        pairs = "<br>".join(
            f"`{escape_table(pair['source'])}` -> `{escape_table(pair['target'])}`"
            + (f" **[{', '.join(pair['flags'])}]**" if pair["flags"] else "")
            for pair in row["pairs"]
        )
        missing = "<br>".join(
            f"`{escape_table(pair[0])}` -> `{escape_table(pair[1])}`"
            for pair in row["missingExpectedPairs"]
        )
        lines.append(
            "| "
            + " | ".join(
                escape_table(value)
                for value in (
                    row["id"],
                    row["source"],
                    row["translation"],
                    pairs,
                    missing,
                    ", ".join(row["flags"]),
                )
            )
            + " |"
        )
    return "\n".join(lines) + "\n"


def compare_reports(paths: list[Path]) -> int:
    print("report\tpairs\tinvalid\tbroad\tmissing-cues\tmissing-expected")
    for path in paths:
        report = read_json(path)
        summary = report["metrics"]
        print(
            f"{path}\t{summary['pairs']}\t{summary['invalidPairs']}\t"
            f"{summary['broadPairs']}\t{summary['missingCueResponses']}\t"
            f"{summary['missingExpectedPairs']}/{summary['expectedPairs']}"
        )
    return 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
    args = parse_args()
    if args.command == "compare":
        return compare_reports(args.reports)
    if args.command == "prepare-srt":
        return prepare_srt_fixture(args)
    if args.command == "generate-correspondence":
        return generate_correspondence(args)
    if args.command == "audit-correspondence":
        return audit_correspondence(args)
    if args.command == "app-preflight":
        return app_preflight(args)
    if args.command == "benchmark-app-batches":
        return benchmark_app_batches(args)
    if args.command == "render-correspondence-html":
        return render_correspondence_html_command(args)
    if args.command == "diagnose-omissions":
        return diagnose_omissions(args)

    fixtures, prompt_template, cases = load_inputs(args.fixtures, args.prompt)
    prompt = render_alignment_prompt(prompt_template, cases, list(range(len(cases))))
    if args.command == "validate":
        print(f"Validated {len(cases)} fixtures and prompt template: {args.prompt}")
        return 0
    if args.command == "dry-run":
        print(prompt)
        return 0

    api_key = read_api_key(args.api_key_file)
    raw_responses: list[dict[str, Any]] = []
    output_lines: list[str] = []
    for batch_number, cue_ids in enumerate(batches(len(cases), args.batch_size), start=1):
        print(f"Aligning batch {batch_number}: cues {cue_ids[0]}-{cue_ids[-1]}")
        batch_prompt = render_alignment_prompt(prompt_template, cases, cue_ids)
        raw_response, batch_output_lines = request_gemini(
            api_key, args.model, batch_prompt, len(cue_ids)
        )
        raw_responses.append(raw_response)
        output_lines.extend(batch_output_lines)
    rows = validate_response(cases, output_lines)
    now = dt.datetime.now().astimezone()
    result = {
        "name": args.name,
        "timestamp": now.isoformat(timespec="seconds"),
        "model": args.model,
        "fixtures": str(args.fixtures),
        "prompt": str(args.prompt),
        "fixtureMetadata": {key: value for key, value in fixtures.items() if key != "cases"},
        "metrics": metrics(rows),
        "rows": rows,
        "generatedText": output_lines,
        "rawHttpResponses": raw_responses,
    }
    args.results_dir.mkdir(parents=True, exist_ok=True)
    stem = f"{now.strftime('%Y%m%d-%H%M%S')}-{args.name}"
    json_path = args.results_dir / f"{stem}.json"
    markdown_path = args.results_dir / f"{stem}.md"
    json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown_report(result), encoding="utf-8")
    print(f"Wrote {json_path}")
    print(f"Wrote {markdown_path}")
    print(json.dumps(result["metrics"], indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError, KeyError, IndexError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
