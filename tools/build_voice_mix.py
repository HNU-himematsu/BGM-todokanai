#!/usr/bin/env python3
"""Build the event master with announcer clips and recompute the timeline."""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import wave
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET

try:
    import numpy as np
except ImportError:  # pragma: no cover - checked in main()
    np = None


SAMPLE_RATE = 44100
CHANNELS = 2
SAMPLE_WIDTH = 2
FRAMES_PER_MS = SAMPLE_RATE / 1000.0
LEAD_MS = 200
TAIL_MS = 400
CLOSING_TAIL_MS = 300
FADE_MS = 10
FADE_FRAMES = int(round(SAMPLE_RATE * FADE_MS / 1000.0))
TARGET_PEAK_DBFS = -1.0
LOUDNESS_TOLERANCE_LU = 6.0
MUSIC_PRESENCE_MS = 3000
OPENING_FILE = "开幕.mp3"
CLOSING_FILE = "再见.wav"
BOUNDARY_FILES = [
    "1→2改.wav",
    "2→3改改.wav",
    "3→4.wav",
    "4→5.wav",
    "5→6.wav",
    "6→7.wav",
    "7→8.wav",
    "8→9.wav",
    "9→10.wav",
    "结束改.wav",
]
ALL_VOICE_FILES = BOUNDARY_FILES + [OPENING_FILE, CLOSING_FILE]
EXPECTED_VOICE_MS = {
    "1→2改.wav": 6970,
    "2→3改改.wav": 5790,
    "3→4.wav": 12680,
    "4→5.wav": 14740,
    "5→6.wav": 10120,
    "6→7.wav": 12640,
    "7→8.wav": 13100,
    "8→9.wav": 13920,
    "9→10.wav": 11160,
    "结束改.wav": 14660,
    OPENING_FILE: 32935,
    CLOSING_FILE: 1910,
}
W_NAMESPACE = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


class ToolError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def run_command(args: list[str], *, code: int = 5) -> subprocess.CompletedProcess[bytes]:
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    except FileNotFoundError as exc:
        raise ToolError(2, f"缺少可执行文件：{args[0]}") from exc
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ToolError(code, f"命令失败（{args[0]}，退出码 {result.returncode}）：{detail}")
    return result


def probe_media(path: Path, *, input_file: bool = True) -> dict[str, Any]:
    result = run_command(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration,size,bit_rate:stream=codec_name,sample_rate,channels,bit_rate",
            "-of",
            "json",
            str(path),
        ],
        code=2 if input_file else 5,
    )
    try:
        return json.loads(result.stdout.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise ToolError(2 if input_file else 5, f"无法解析媒体信息：{path}") from exc


def media_duration_ms(path: Path) -> int:
    info = probe_media(path)
    duration = info.get("format", {}).get("duration")
    if duration is None:
        raise ToolError(2, f"无法读取媒体时长：{path}")
    return int(round(float(duration) * 1000))


def read_docx_lines(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as archive:
            payload = archive.read("word/document.xml")
    except (OSError, KeyError, zipfile.BadZipFile) as exc:
        raise ToolError(3, f"无法读取编排文件：{path}") from exc
    try:
        root = ET.fromstring(payload)
    except ET.ParseError as exc:
        raise ToolError(3, f"编排文件 XML 解析失败：{path}") from exc

    lines: list[str] = []
    for paragraph in root.iter(W_NAMESPACE + "p"):
        parts: list[str] = []
        for node in paragraph.iter():
            if node.tag == W_NAMESPACE + "t":
                parts.append(node.text or "")
            elif node.tag == W_NAMESPACE + "br":
                parts.append("\n")
            elif node.tag == W_NAMESPACE + "tab":
                parts.append("\t")
        text = "".join(parts)
        for line in text.split("\n"):
            stripped = line.strip()
            if stripped:
                lines.append(stripped)
    return lines


def parse_clock(value: str) -> int:
    text = value.strip()
    parts = text.split(":")
    if len(parts) == 3:
        hours, minutes, seconds = parts
    elif len(parts) == 2:
        hours = "0"
        minutes, seconds = parts
    else:
        raise ValueError(f"时间格式无效：{value}")
    if not hours.isdigit() or not minutes.isdigit() or not re.fullmatch(r"\d+(?:\.\d+)?", seconds):
        raise ValueError(f"时间格式无效：{value}")
    return int(hours) * 3_600_000 + int(minutes) * 60_000 + int(round(float(seconds) * 1000))


@dataclass
class ScriptQuestion:
    number: int
    audio_start_ms: int | None = None
    audio_end_ms: int | None = None
    load_ms: int | None = None
    next_ms: int | None = None
    lock_ms: int | None = None
    reveal_ms: int | None = None


@dataclass
class ScriptRound:
    name: str
    start_ms: int | None = None
    end_ms: int | None = None
    finish_ms: int | None = None
    questions: list[ScriptQuestion] = field(default_factory=list)

def parse_script(path: Path) -> list[ScriptRound]:
    if not path.is_file():
        raise ToolError(2, f"编排文件不存在：{path}")
    lines = read_docx_lines(path)
    rounds: list[ScriptRound] = []
    current_round: ScriptRound | None = None
    current_question: ScriptQuestion | None = None
    clock_fields = {
        "题目音频片段开始": "audio_start_ms",
        "题目音频片段结束": "audio_end_ms",
        "LOAD": "load_ms",
        "NEXT": "next_ms",
        "LOCK": "lock_ms",
        "REVEAL": "reveal_ms",
    }

    for line in lines:
        if re.fullmatch(r"第[一二三四五六七八九十]+轮", line):
            current_round = ScriptRound(name=line)
            rounds.append(current_round)
            current_question = None
            continue
        if current_round is None:
            continue
        if line.startswith("轮次开始："):
            try:
                current_round.start_ms = parse_clock(line.split("：", 1)[1])
            except ValueError as exc:
                raise ToolError(3, f"{current_round.name} 的轮次开始无法解析") from exc
            continue
        if line.startswith("轮次结束："):
            try:
                current_round.end_ms = parse_clock(line.split("：", 1)[1])
            except ValueError as exc:
                raise ToolError(3, f"{current_round.name} 的轮次结束无法解析") from exc
            continue
        if line.startswith("FINISH："):
            try:
                current_round.finish_ms = parse_clock(line.split("：", 1)[1])
            except ValueError as exc:
                raise ToolError(3, f"{current_round.name} 的 FINISH 无法解析") from exc
            current_question = None
            continue
        match = re.match(r"^Q-(\d{2})｜", line)
        if match:
            current_question = ScriptQuestion(number=int(match.group(1)))
            current_round.questions.append(current_question)
            continue
        if current_question is None or "：" not in line:
            continue
        key, value = line.split("：", 1)
        target = clock_fields.get(key.strip())
        if target is None:
            continue
        try:
            setattr(current_question, target, parse_clock(value))
        except ValueError as exc:
            raise ToolError(3, f"第 {current_question.number} 题的 {key} 无法解析：{value}") from exc

    if len(rounds) != 4:
        raise ToolError(3, f"编排文件应包含 4 轮，实际为 {len(rounds)} 轮")

    expected_number = 1
    for script_round in rounds:
        if script_round.start_ms is None or script_round.end_ms is None:
            raise ToolError(3, f"{script_round.name} 缺少轮次开始或轮次结束")
        if script_round.end_ms <= script_round.start_ms:
            raise ToolError(3, f"{script_round.name} 的轮次结束不晚于轮次开始")
        if script_round.finish_ms is None:
            script_round.finish_ms = script_round.end_ms
        if script_round.finish_ms != script_round.end_ms:
            raise ToolError(3, f"{script_round.name} 的 FINISH 与轮次结束不一致")
        if len(script_round.questions) != 10:
            raise ToolError(3, f"{script_round.name} 应包含 10 题，实际为 {len(script_round.questions)} 题")
        for question_index, question in enumerate(script_round.questions):
            if question.number != expected_number:
                raise ToolError(3, f"题号不连续：期望 Q-{expected_number:02d}，实际 Q-{question.number:02d}")
            expected_number += 1
            values = (
                question.audio_start_ms,
                question.audio_end_ms,
                question.load_ms,
                question.next_ms,
                question.lock_ms,
                question.reveal_ms,
            )
            if any(value is None for value in values):
                raise ToolError(3, f"第 {question.number} 题缺少音频或动作时间")
            if not (question.audio_start_ms < question.audio_end_ms):
                raise ToolError(3, f"第 {question.number} 题音频片段起止无效")
            if not (script_round.start_ms <= question.audio_start_ms and question.audio_end_ms <= script_round.end_ms):
                raise ToolError(3, f"第 {question.number} 题音频片段超出轮次区间")
            if not (question.load_ms < question.next_ms < question.lock_ms < question.reveal_ms):
                raise ToolError(3, f"第 {question.number} 题动作时刻未严格递增")
            window_end = (
                script_round.questions[question_index + 1].audio_start_ms
                if question_index + 1 < len(script_round.questions)
                else script_round.end_ms
            )
            if not (question.audio_start_ms <= question.next_ms < question.lock_ms <= window_end):
                raise ToolError(3, f"第 {question.number} 题动作时刻与音频窗口不一致")
            expected_load = 0 if question_index == 0 else -1000
            expected_next = 1 if question_index == 0 else 0
            if question.load_ms - question.audio_start_ms != expected_load:
                raise ToolError(3, f"第 {question.number} 题 LOAD 偏移应为 {expected_load} 毫秒")
            if question.next_ms - question.audio_start_ms != expected_next:
                raise ToolError(3, f"第 {question.number} 题 NEXT 偏移应为 {expected_next} 毫秒")
            if question.reveal_ms - question.lock_ms != 1000:
                raise ToolError(3, f"第 {question.number} 题 REVEAL 应在 LOCK 后 1000 毫秒")
            if question.lock_ms <= question.audio_end_ms:
                raise ToolError(3, f"第 {question.number} 题 LOCK 应晚于音乐片段结束")
        for question_index, question in enumerate(script_round.questions):
            next_start = (
                script_round.questions[question_index + 1].audio_start_ms
                if question_index + 1 < len(script_round.questions)
                else script_round.end_ms
            )
            if next_start < question.audio_end_ms:
                raise ToolError(3, f"第 {question.number} 题音乐片段与下一题重叠")

    if expected_number != 41:
        raise ToolError(3, f"编排文件应包含 40 题，实际为 {expected_number - 1} 题")
    return rounds

def load_timeline(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ToolError(2, f"编排文件不存在：{path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ToolError(3, f"timeline.json 解析失败：{path}") from exc
    if not isinstance(data, dict):
        raise ToolError(3, "timeline.json 顶层必须为对象")
    for key in ("eventId", "eventName", "audioFile", "rounds"):
        if data.get(key) in (None, ""):
            raise ToolError(3, f"timeline.json 缺少字段：{key}")
    if not isinstance(data["eventId"], str) or not isinstance(data["eventName"], str):
        raise ToolError(3, "timeline.json 的 eventId 与 eventName 必须为字符串")
    audio_file = data["audioFile"]
    if not isinstance(audio_file, str) or Path(audio_file).name != audio_file:
        raise ToolError(3, "timeline.json 的 audioFile 必须为不含目录的文件名")
    if not isinstance(data["rounds"], list) or len(data["rounds"]) != 4:
        raise ToolError(3, "timeline.json 应包含 4 轮")
    return data


def timeline_question_ids(timeline: dict[str, Any], script_rounds: list[ScriptRound]) -> list[list[str]]:
    result: list[list[str]] = []
    seen_round_ids: set[str] = set()
    for round_index, (timeline_round, script_round) in enumerate(zip(timeline["rounds"], script_rounds)):
        if not isinstance(timeline_round, dict):
            raise ToolError(3, f"timeline.json 第 {round_index + 1} 轮必须为对象")
        round_id = timeline_round.get("id")
        if not isinstance(round_id, str) or not round_id or round_id in seen_round_ids:
            raise ToolError(3, f"timeline.json 第 {round_index + 1} 轮的 id 无效或重复")
        seen_round_ids.add(round_id)
        if timeline_round.get("name") != script_round.name:
            raise ToolError(3, f"timeline.json 第 {round_index + 1} 轮名称与编排文件不一致")
        items = timeline_round.get("items")
        if not isinstance(items, list):
            raise ToolError(3, f"timeline.json 第 {round_index + 1} 轮缺少 items")
        loads = [item for item in items if isinstance(item, dict) and item.get("action") == "LOAD"]
        if len(loads) != len(script_round.questions):
            raise ToolError(3, f"timeline.json 第 {round_index + 1} 轮 LOAD 数量与编排文件不一致")
        qids: list[str] = []
        for item in loads:
            qid = item.get("questionId")
            if not isinstance(qid, str) or not re.fullmatch(r"q-\d{2}", qid):
                raise ToolError(3, f"timeline.json 存在无效 questionId：{qid!r}")
            qids.append(qid)
        expected = [f"q-{question.number:02d}" for question in script_round.questions]
        if qids != expected:
            raise ToolError(
                3,
                f"timeline.json 第 {round_index + 1} 轮题号顺序与编排文件不一致："
                f"期望 {expected}，实际 {qids}",
            )
        result.append(qids)
    return result


@dataclass
class SourceQuestion:
    number: int
    qid: str
    round_index: int
    question_index: int
    source_music_start: int
    source_music_end: int
    source_next_music_start: int
    original_gap: int
    lock_offset: int
    reveal_offset: int


@dataclass
class SourceRound:
    index: int
    name: str
    source_start: int
    source_end: int
    questions: list[SourceQuestion]


def build_source_rounds(
    script_rounds: list[ScriptRound], qids_by_round: list[list[str]]
) -> list[SourceRound]:
    result: list[SourceRound] = []
    source_base = 0
    for round_index, (script_round, qids) in enumerate(zip(script_rounds, qids_by_round)):
        round_start = source_base
        round_end = source_base + (script_round.end_ms - script_round.start_ms)
        questions: list[SourceQuestion] = []
        for question_index, (script_question, qid) in enumerate(zip(script_round.questions, qids)):
            music_start = source_base + (script_question.audio_start_ms - script_round.start_ms)
            music_end = source_base + (script_question.audio_end_ms - script_round.start_ms)
            if question_index + 1 < len(script_round.questions):
                next_start = source_base + (
                    script_round.questions[question_index + 1].audio_start_ms - script_round.start_ms
                )
            else:
                next_start = round_end
            questions.append(
                SourceQuestion(
                    number=script_question.number,
                    qid=qid,
                    round_index=round_index,
                    question_index=question_index,
                    source_music_start=music_start,
                    source_music_end=music_end,
                    source_next_music_start=next_start,
                    original_gap=next_start - music_end,
                    lock_offset=script_question.lock_ms - script_question.audio_end_ms,
                    reveal_offset=script_question.reveal_ms - script_question.lock_ms,
                )
            )
        result.append(
            SourceRound(
                index=round_index,
                name=script_round.name,
                source_start=round_start,
                source_end=round_end,
                questions=questions,
            )
        )
        source_base = round_end
    return result


@dataclass
class AudioSegment:
    kind: str
    duration_ms: int
    source_start_ms: int | None = None
    voice_name: str | None = None


@dataclass
class SlotInfo:
    kind: str
    start_ms: int
    end_ms: int
    clip_duration_ms: int
    clip_name: str
    trailing_ms: int

@dataclass
class QuestionMix:
    round_index: int
    question_index: int
    number: int
    qid: str
    source_music_start: int
    source_music_end: int
    new_music_start: int
    new_music_end: int
    original_gap: int
    clip_name: str
    clip_duration_ms: int
    slot_length: int
    new_gap: int
    insertion: int
    remainder: int
    lock_offset: int
    reveal_offset: int
    load_at: int
    next_at: int
    lock_at: int
    reveal_at: int
    audio_start: int
    audio_end: int
    slot_start: int
    slot_end: int


@dataclass
class RoundMix:
    id: str
    name: str
    start_ms: int
    end_ms: int
    questions: list[QuestionMix]


@dataclass
class MixPlan:
    timeline: dict[str, Any]
    rounds: list[RoundMix]
    segments: list[AudioSegment]
    slots: list[SlotInfo]
    total_ms: int
    insert_total: int


@dataclass
class GainInfo:
    gain_db: float
    music_lufs: float
    voice_lufs: dict[str, float]
    peak_db: float
    auto: bool


def read_voice_durations(voice_dir: Path) -> dict[str, int]:
    durations: dict[str, int] = {}
    for name in ALL_VOICE_FILES:
        path = voice_dir / name
        if not path.is_file():
            raise ToolError(2, f"播报素材不存在：{path}")
        duration = media_duration_ms(path)
        expected = EXPECTED_VOICE_MS[name]
        if abs(duration - expected) > 2:
            raise ToolError(
                2,
                f"播报素材时长与规范不符：{name} 预期 {expected} 毫秒，实际 {duration} 毫秒",
            )
        durations[name] = duration
    return durations


def build_mix_plan(
    script_rounds: list[ScriptRound],
    source_rounds: list[SourceRound],
    timeline: dict[str, Any],
    voice_durations: dict[str, int],
    *,
    zero_insert: bool,
) -> MixPlan:
    opening_duration = voice_durations[OPENING_FILE]
    closing_duration = voice_durations[CLOSING_FILE]
    segments: list[AudioSegment] = []
    slots: list[SlotInfo] = []
    round_mixes: list[RoundMix] = []
    global_insert = 0
    first_question = True

    for round_index, (script_round, source_round) in enumerate(zip(script_rounds, source_rounds)):
        timeline_round = timeline["rounds"][round_index]
        round_start_out = source_round.source_start + global_insert
        if round_index == 0 and round_start_out != 0:
            raise ToolError(3, "第一轮 startMs 必须为 0")
        if round_mixes and round_start_out != round_mixes[-1].end_ms:
            raise ToolError(3, f"{script_round.name} 与上一轮未首尾相接")
        cumulative = global_insert

        if round_index == 0 and not zero_insert:
            opening_length = opening_duration + LEAD_MS + TAIL_MS
            segments.append(AudioSegment("silence", LEAD_MS))
            segments.append(AudioSegment("voice", opening_duration, voice_name=OPENING_FILE))
            segments.append(AudioSegment("silence", TAIL_MS))
            slots.append(
                SlotInfo(
                    kind="opening",
                    start_ms=0,
                    end_ms=opening_length,
                    clip_duration_ms=opening_duration,
                    clip_name=OPENING_FILE,
                    trailing_ms=TAIL_MS,
                )
            )
            cumulative += opening_length

        question_mixes: list[QuestionMix] = []
        for question_index, source_question in enumerate(source_round.questions):
            clip_name = BOUNDARY_FILES[question_index]
            clip_duration = voice_durations[clip_name]
            new_music_start = source_question.source_music_start + cumulative
            new_music_end = source_question.source_music_end + cumulative
            slot_length = clip_duration + LEAD_MS + TAIL_MS
            insertion = 0 if zero_insert else max(0, slot_length - source_question.original_gap)
            new_gap = source_question.original_gap + insertion
            remainder = new_gap - slot_length

            if not zero_insert:
                if remainder < 0:
                    raise ToolError(6, f"第 {source_question.number} 题播报槽无法放入题间空档")
                segments.append(
                    AudioSegment(
                        "music",
                        source_question.source_music_end - source_question.source_music_start,
                        source_start_ms=source_question.source_music_start,
                    )
                )
                segments.append(AudioSegment("silence", LEAD_MS))
                segments.append(AudioSegment("voice", clip_duration, voice_name=clip_name))
                segments.append(AudioSegment("silence", TAIL_MS))
                if remainder > 0:
                    segments.append(AudioSegment("silence", remainder))
                slots.append(
                    SlotInfo(
                        kind="boundary",
                        start_ms=new_music_end,
                        end_ms=new_music_end + new_gap,
                        clip_duration_ms=clip_duration,
                        clip_name=clip_name,
                        trailing_ms=TAIL_MS,
                    )
                )

            load_at = new_music_start if question_index == 0 else new_music_start - 1000
            next_at = new_music_start + (1 if question_index == 0 else 0)
            lock_at = new_music_end + source_question.lock_offset
            reveal_at = lock_at + source_question.reveal_offset
            audio_start = 0 if first_question else new_music_start
            question_mixes.append(
                QuestionMix(
                    round_index=round_index,
                    question_index=question_index,
                    number=source_question.number,
                    qid=source_question.qid,
                    source_music_start=source_question.source_music_start,
                    source_music_end=source_question.source_music_end,
                    new_music_start=new_music_start,
                    new_music_end=new_music_end,
                    original_gap=source_question.original_gap,
                    clip_name=clip_name,
                    clip_duration_ms=clip_duration,
                    slot_length=slot_length,
                    new_gap=new_gap,
                    insertion=insertion,
                    remainder=remainder,
                    lock_offset=source_question.lock_offset,
                    reveal_offset=source_question.reveal_offset,
                    load_at=load_at,
                    next_at=next_at,
                    lock_at=lock_at,
                    reveal_at=reveal_at,
                    audio_start=audio_start,
                    audio_end=0,
                    slot_start=new_music_end,
                    slot_end=new_music_end + new_gap,
                )
            )
            cumulative += insertion
            first_question = False

        round_end_out = source_round.source_end + cumulative
        if round_index == len(script_rounds) - 1 and not zero_insert:
            closing_length = closing_duration + CLOSING_TAIL_MS
            segments.append(AudioSegment("voice", closing_duration, voice_name=CLOSING_FILE))
            segments.append(AudioSegment("silence", CLOSING_TAIL_MS))
            slots.append(
                SlotInfo(
                    kind="closing",
                    start_ms=round_end_out,
                    end_ms=round_end_out + closing_length,
                    clip_duration_ms=closing_duration,
                    clip_name=CLOSING_FILE,
                    trailing_ms=CLOSING_TAIL_MS,
                )
            )
            round_end_out += closing_length
            cumulative += closing_length

        for question_index, question in enumerate(question_mixes):
            if question_index + 1 < len(question_mixes):
                question.audio_end = question_mixes[question_index + 1].new_music_start
            else:
                question.audio_end = round_end_out

        for question_index, question in enumerate(question_mixes):
            if not (question.load_at < question.next_at < question.lock_at < question.reveal_at):
                raise ToolError(3, f"第 {question.number} 题重算后动作时刻未严格递增")
            if not (question.audio_start <= question.next_at < question.lock_at <= question.audio_end):
                raise ToolError(3, f"第 {question.number} 题重算后动作时刻与音频窗口不一致")
            if question.audio_end > round_end_out:
                raise ToolError(3, f"第 {question.number} 题音频窗口超出轮次")
            if question_index + 1 < len(question_mixes):
                if question.reveal_at >= question_mixes[question_index + 1].load_at:
                    raise ToolError(3, f"第 {question.number} 题 REVEAL 晚于下一题 LOAD")
            elif question.reveal_at >= round_end_out:
                raise ToolError(3, f"第 {question.number} 题 REVEAL 未早于 FINISH")
            if not zero_insert:
                clip_end = question.slot_start + LEAD_MS + question.clip_duration_ms
                if clip_end + TAIL_MS > question.slot_end:
                    raise ToolError(6, f"第 {question.number} 题播报尾部留白不足")
                if clip_end + TAIL_MS > question.audio_end:
                    raise ToolError(6, f"第 {question.number} 题播报进入下一题音乐")

        round_mixes.append(
            RoundMix(
                id=timeline_round["id"],
                name=timeline_round["name"],
                start_ms=round_start_out,
                end_ms=round_end_out,
                questions=question_mixes,
            )
        )
        global_insert = cumulative

    total_ms = round_mixes[-1].end_ms
    if not zero_insert:
        segment_total = sum(segment.duration_ms for segment in segments)
        if segment_total != total_ms:
            raise ToolError(5, f"拼接片段总时长 {segment_total} 毫秒与时间轴 {total_ms} 毫秒不一致")

    output_rounds: list[dict[str, Any]] = []
    for round_mix in round_mixes:
        items: list[dict[str, Any]] = []
        for question in round_mix.questions:
            items.append(
                {
                    "atMs": question.load_at,
                    "action": "LOAD",
                    "questionId": question.qid,
                    "audioStartMs": question.audio_start,
                    "audioEndMs": question.audio_end,
                }
            )
            items.append({"atMs": question.next_at, "action": "NEXT"})
            items.append({"atMs": question.lock_at, "action": "LOCK"})
            items.append({"atMs": question.reveal_at, "action": "REVEAL"})
        items.append({"atMs": round_mix.end_ms, "action": "FINISH"})
        for previous, current in zip(items, items[1:]):
            if current["atMs"] <= previous["atMs"]:
                raise ToolError(3, f"{round_mix.name} 的条目时刻未严格递增")
        output_rounds.append(
            {
                "id": round_mix.id,
                "name": round_mix.name,
                "startMs": round_mix.start_ms,
                "endMs": round_mix.end_ms,
                "items": items,
            }
        )

    output_timeline = {
        "eventId": timeline["eventId"],
        "eventName": timeline["eventName"],
        "audioFile": timeline["audioFile"],
        "rounds": output_rounds,
    }
    return MixPlan(
        timeline=output_timeline,
        rounds=round_mixes,
        segments=segments,
        slots=slots,
        total_ms=total_ms,
        insert_total=total_ms - source_rounds[-1].source_end,
    )


def measure_loudness(path: Path) -> float:
    result = run_command(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostats",
            "-i",
            str(path),
            "-af",
            "ebur128=framelog=quiet",
            "-f",
            "null",
            os.devnull,
        ],
        code=2,
    )
    text = result.stderr.decode("utf-8", "replace")
    matches = re.findall(r"I:\s*(-?\d+(?:\.\d+)?)\s*LUFS", text)
    if not matches:
        raise ToolError(6, f"无法测量响度：{path}")
    value = float(matches[-1])
    if not math.isfinite(value):
        raise ToolError(6, f"响度无效：{path}")
    return value


def measure_peak(path: Path) -> float:
    result = run_command(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostats",
            "-i",
            str(path),
            "-af",
            "volumedetect",
            "-f",
            "null",
            os.devnull,
        ],
        code=2,
    )
    text = result.stderr.decode("utf-8", "replace")
    matches = re.findall(r"max_volume:\s*(-?inf|-?\d+(?:\.\d+)?)\s*dB", text)
    if not matches:
        raise ToolError(6, f"无法测量峰值：{path}")
    value = matches[-1]
    if value == "-inf":
        return float("-inf")
    return float(value)


def resolve_gain(source: Path, voice_dir: Path, override_gain_db: float | None) -> GainInfo:
    music_lufs = measure_loudness(source)
    voice_lufs = {name: measure_loudness(voice_dir / name) for name in ALL_VOICE_FILES}
    peaks = {name: measure_peak(voice_dir / name) for name in ALL_VOICE_FILES}
    peak_db = max(peaks.values())
    mean_voice_lufs = sum(voice_lufs.values()) / len(voice_lufs)
    auto = override_gain_db is None
    if auto:
        match_gain = music_lufs - mean_voice_lufs
        peak_limited_gain = TARGET_PEAK_DBFS - peak_db
        gain_db = min(match_gain, peak_limited_gain)
        if gain_db <= peak_limited_gain + 1e-9:
            print(
                f"播报增益按峰值约束取 {gain_db:.2f} dB；按响度匹配为 {match_gain:.2f} dB。",
                file=sys.stderr,
            )
    else:
        gain_db = float(override_gain_db)

    if not math.isfinite(gain_db):
        raise ToolError(6, "播报增益无效")
    if peak_db + gain_db > TARGET_PEAK_DBFS + 1e-6:
        raise ToolError(
            6,
            f"播报增益 {gain_db:.2f} dB 会使峰值达到 {peak_db + gain_db:.2f} dBFS，"
            f"超过 {TARGET_PEAK_DBFS:.1f} dBFS",
        )
    for name, loudness in voice_lufs.items():
        compensated = loudness + gain_db
        if abs(compensated - music_lufs) > LOUDNESS_TOLERANCE_LU + 1e-6:
            raise ToolError(
                6,
                f"播报响度超出容差：{name} 增益后 {compensated:.2f} LUFS，"
                f"音乐 {music_lufs:.2f} LUFS",
            )
    return GainInfo(
        gain_db=gain_db,
        music_lufs=music_lufs,
        voice_lufs=voice_lufs,
        peak_db=peak_db,
        auto=auto,
    )

def decode_to_wav(source: Path, destination: Path) -> None:
    run_command(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(source),
            "-vn",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            str(CHANNELS),
            "-c:a",
            "pcm_s16le",
            str(destination),
        ],
        code=2,
    )


def fit_frames(data: bytes, frames: int) -> bytes:
    expected = frames * CHANNELS * SAMPLE_WIDTH
    if len(data) < expected:
        return data + b"\x00" * (expected - len(data))
    if len(data) > expected:
        return data[:expected]
    return data


def apply_gain_and_fade(data: bytes, frames: int, gain_db: float) -> bytes:
    if np is None:
        raise ToolError(2, "缺少 numpy，请先安装")
    data = fit_frames(data, frames)
    samples = np.frombuffer(data, dtype="<i2").reshape(-1, CHANNELS).astype(np.float32)
    if gain_db:
        samples *= 10.0 ** (gain_db / 20.0)
    fade = min(FADE_FRAMES, frames // 2)
    if fade > 0:
        ramp = np.linspace(0.0, 1.0, fade, dtype=np.float32)[:, None]
        samples[:fade] *= ramp
        samples[-fade:] *= ramp[::-1]
    samples = np.clip(np.rint(samples), -32768.0, 32767.0).astype("<i2")
    return samples.tobytes()


def build_master_wav(
    plan: MixPlan,
    source: Path,
    voice_dir: Path,
    gain_db: float,
    working_dir: Path,
) -> Path:
    if np is None:
        raise ToolError(2, "缺少 numpy，请先安装")
    source_wav = working_dir / "source-44100.wav"
    decode_to_wav(source, source_wav)
    voice_wavs: dict[str, Path] = {}
    for index, name in enumerate(ALL_VOICE_FILES):
        path = working_dir / f"voice-{index:02d}.wav"
        decode_to_wav(voice_dir / name, path)
        voice_wavs[name] = path

    master_wav = working_dir / "master.wav"
    cursor_ms = 0.0
    with wave.open(str(source_wav), "rb") as source_reader, wave.open(str(master_wav), "wb") as writer:
        if (
            source_reader.getnchannels() != CHANNELS
            or source_reader.getsampwidth() != SAMPLE_WIDTH
            or source_reader.getframerate() != SAMPLE_RATE
        ):
            raise ToolError(5, "源母带解码后的声道、位深或采样率不符合要求")
        writer.setnchannels(CHANNELS)
        writer.setsampwidth(SAMPLE_WIDTH)
        writer.setframerate(SAMPLE_RATE)
        for segment in plan.segments:
            start_frame = int(round(cursor_ms * FRAMES_PER_MS))
            cursor_ms += segment.duration_ms
            end_frame = int(round(cursor_ms * FRAMES_PER_MS))
            frames = end_frame - start_frame
            if frames <= 0:
                continue
            if segment.kind == "silence":
                writer.writeframes(b"\x00" * (frames * CHANNELS * SAMPLE_WIDTH))
            elif segment.kind == "music":
                source_reader.setpos(min(int(round(segment.source_start_ms * FRAMES_PER_MS)), source_reader.getnframes()))
                data = source_reader.readframes(frames)
                writer.writeframes(apply_gain_and_fade(data, frames, 0.0))
            elif segment.kind == "voice":
                if segment.voice_name is None:
                    raise ToolError(5, "播报片段缺少素材名称")
                voice_path = voice_wavs.get(segment.voice_name)
                if voice_path is None:
                    raise ToolError(5, f"找不到播报素材：{segment.voice_name}")
                with wave.open(str(voice_path), "rb") as voice_reader:
                    data = voice_reader.readframes(frames)
                writer.writeframes(apply_gain_and_fade(data, frames, gain_db))
            else:
                raise ToolError(5, f"未知片段类型：{segment.kind}")
    expected_frames = int(round(plan.total_ms * FRAMES_PER_MS))
    with wave.open(str(master_wav), "rb") as reader:
        if abs(reader.getnframes() - expected_frames) > 2:
            raise ToolError(5, "母带帧数与时间轴不一致")
    return master_wav


def max_dbfs(path: Path, start_ms: int, duration_ms: int) -> float:
    if np is None:
        raise ToolError(2, "缺少 numpy，请先安装")
    with wave.open(str(path), "rb") as reader:
        start = max(0, int(round(start_ms * FRAMES_PER_MS)))
        frames = max(0, int(round(duration_ms * FRAMES_PER_MS)))
        if start > reader.getnframes():
            return float("-inf")
        reader.setpos(start)
        data = reader.readframes(frames)
    if not data:
        return float("-inf")
    samples = np.frombuffer(data, dtype="<i2").astype(np.int32)
    if samples.size == 0:
        return float("-inf")
    peak = int(np.max(np.abs(samples)))
    if peak <= 0:
        return float("-inf")
    return 20.0 * math.log10(peak / 32768.0)


def validate_master(master_wav: Path, plan: MixPlan) -> None:
    for round_mix in plan.rounds:
        for question in round_mix.questions:
            duration = min(MUSIC_PRESENCE_MS, question.new_music_end - question.new_music_start)
            level = max_dbfs(master_wav, question.new_music_start, duration)
            if level <= -20.0:
                raise ToolError(
                    6,
                    f"第 {question.number} 题音乐起点处无有效音频（{level:.1f} dBFS）",
                )
    for slot in plan.slots:
        presence_start = slot.start_ms + (LEAD_MS if slot.kind != "closing" else 0)
        level = max_dbfs(master_wav, presence_start, slot.clip_duration_ms)
        if level <= -20.0:
            raise ToolError(
                6,
                f"播报槽内无人声：{slot.clip_name} @ {slot.start_ms} ms（{level:.1f} dBFS）",
            )
        trailing_level = max_dbfs(master_wav, slot.end_ms - slot.trailing_ms, slot.trailing_ms)
        if trailing_level >= -50.0:
            raise ToolError(
                6,
                f"播报槽尾部不为静音：{slot.clip_name} @ {slot.start_ms} ms"
                f"（{trailing_level:.1f} dBFS）",
            )


def encode_mp3(master_wav: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    run_command(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(master_wav),
            "-c:a",
            "libmp3lame",
            "-b:a",
            "192k",
            "-ar",
            str(SAMPLE_RATE),
            "-ac",
            str(CHANNELS),
            "-write_xing",
            "1",
            str(output),
        ],
        code=5,
    )


def validate_mp3(output: Path, expected_ms: int) -> dict[str, Any]:
    info = probe_media(output, input_file=False)
    duration_value = info.get("format", {}).get("duration")
    if duration_value is None:
        raise ToolError(5, "成品音频缺少时长信息")
    duration = float(duration_value)
    expected_seconds = expected_ms / 1000.0
    if abs(duration - expected_seconds) > 0.1:
        raise ToolError(
            5,
            f"成品音频时长 {duration:.3f} 秒与期望 {expected_seconds:.3f} 秒相差超过 0.1 秒",
        )
    streams = info.get("streams") or []
    if not streams:
        raise ToolError(5, "成品音频缺少音频流")
    stream = streams[0]
    if int(stream.get("sample_rate", 0)) != SAMPLE_RATE:
        raise ToolError(5, f"成品音频采样率不是 {SAMPLE_RATE}")
    if int(stream.get("channels", 0)) != CHANNELS:
        raise ToolError(5, f"成品音频声道数不是 {CHANNELS}")
    bit_rate = int(info.get("format", {}).get("bit_rate", 0))
    if not (180_000 <= bit_rate <= 204_000):
        raise ToolError(5, f"成品音频码率 {bit_rate} bps 不在 CBR 192 kbps 范围")
    return info

def compare_timelines(expected: dict[str, Any], actual: dict[str, Any]) -> list[str]:
    differences: list[str] = []
    for key in ("eventId", "eventName", "audioFile"):
        if expected.get(key) != actual.get(key):
            differences.append(f"{key}: 期望 {expected.get(key)!r}，实际 {actual.get(key)!r}")
    expected_rounds = expected.get("rounds") or []
    actual_rounds = actual.get("rounds") or []
    if len(expected_rounds) != len(actual_rounds):
        differences.append(f"rounds 数量: 期望 {len(expected_rounds)}，实际 {len(actual_rounds)}")
    for round_index, (expected_round, actual_round) in enumerate(zip(expected_rounds, actual_rounds)):
        prefix = f"rounds[{round_index}]"
        for key in ("id", "name", "startMs", "endMs"):
            if expected_round.get(key) != actual_round.get(key):
                differences.append(
                    f"{prefix}.{key}: 期望 {expected_round.get(key)!r}，实际 {actual_round.get(key)!r}"
                )
        expected_items = expected_round.get("items") or []
        actual_items = actual_round.get("items") or []
        if len(expected_items) != len(actual_items):
            differences.append(
                f"{prefix}.items 数量: 期望 {len(expected_items)}，实际 {len(actual_items)}"
            )
        fields = ("atMs", "action", "questionId", "audioStartMs", "audioEndMs")
        for item_index, (expected_item, actual_item) in enumerate(zip(expected_items, actual_items)):
            for field_name in fields:
                expected_value = expected_item.get(field_name)
                actual_value = actual_item.get(field_name)
                if expected_value != actual_value:
                    differences.append(
                        f"{prefix}.items[{item_index}].{field_name}: "
                        f"期望 {expected_value!r}，实际 {actual_value!r}"
                    )
    return differences


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_report(
    path: Path,
    plan: MixPlan,
    gain_info: GainInfo | None,
    *,
    zero_insert: bool,
    differences: list[str],
) -> None:
    lines = [
        "# 播报混音报告",
        "",
        f"- 模式：{'零插入自检' if zero_insert else '混音生成'}",
        f"- 母带总长：{plan.total_ms} 毫秒",
        f"- 插入合计：{plan.insert_total} 毫秒",
    ]
    if gain_info is not None:
        lines.extend(
            [
                f"- 播报增益：{gain_info.gain_db:.2f} dB（{'自动' if gain_info.auto else '指定'}）",
                f"- 音乐积分响度：{gain_info.music_lufs:.2f} LUFS",
                f"- 播报峰值：{gain_info.peak_db:.2f} dBFS",
            ]
        )
    lines.extend(["", "## 轮次", "", "| 轮次 | startMs | endMs |", "| :--- | ---: | ---: |"])
    for round_mix in plan.rounds:
        lines.append(f"| {round_mix.name} | {round_mix.start_ms} | {round_mix.end_ms} |")
    lines.extend(
        [
            "",
            "## 逐题槽位",
            "",
            "| 题号 | id | 音乐区间 | 原空档 | 播报 | 片段 | 槽长 | 插入 | 新空档 | 槽后余量 |",
            "| ---: | :--- | :--- | ---: | :--- | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for round_mix in plan.rounds:
        for question in round_mix.questions:
            lines.append(
                f"| {question.number} | `{question.qid}` | "
                f"{question.new_music_start}–{question.new_music_end} | "
                f"{question.original_gap} | `{question.clip_name}` | "
                f"{question.clip_duration_ms} | {question.slot_length} | "
                f"{question.insertion} | {question.new_gap} | {question.remainder} |"
            )
    lines.extend(
        [
            "",
            "## 动作时刻",
            "",
            "| 题号 | LOAD | NEXT | LOCK | REVEAL | audioStart | audioEnd |",
            "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for round_mix in plan.rounds:
        for question in round_mix.questions:
            lines.append(
                f"| {question.number} | {question.load_at} | {question.next_at} | "
                f"{question.lock_at} | {question.reveal_at} | "
                f"{question.audio_start} | {question.audio_end} |"
            )
    if zero_insert:
        lines.extend(["", "## 零插入自检", ""])
        if differences:
            lines.append(f"发现 {len(differences)} 处差异，前 20 条：")
            lines.append("")
            for difference in differences[:20]:
                lines.append(f"- {difference}")
        else:
            lines.append("通过：重算结果与输入的 timeline.json 完全一致。")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

def install_outputs(
    data_dir: Path,
    output_audio: Path,
    output_timeline: Path,
    audio_file: str,
) -> Path:
    backup_dir = data_dir.parent / "data-backup"
    try:
        shutil.copytree(data_dir, backup_dir, dirs_exist_ok=True)
    except OSError as exc:
        raise ToolError(7, f"备份 backend/data 失败：{exc}") from exc
    temporary_files: list[Path] = []
    try:
        audio_target = data_dir / audio_file
        audio_temp = data_dir / (audio_file + ".tmp")
        timeline_target = data_dir / "timeline.json"
        timeline_temp = data_dir / "timeline.json.tmp"
        temporary_files.extend([audio_temp, timeline_temp])
        shutil.copy2(output_audio, audio_temp)
        shutil.copy2(output_timeline, timeline_temp)
        os.replace(audio_temp, audio_target)
        os.replace(timeline_temp, timeline_target)
    except OSError as exc:
        for path in temporary_files:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        raise ToolError(7, f"替换 backend/data 失败：{exc}") from exc
    return backup_dir


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="把主持人播报混入题集母带，并重算 timeline.json。")
    parser.add_argument("--source", required=True, help="题集混拼源母带")
    parser.add_argument("--voice-dir", required=True, help="12 段播报素材目录")
    parser.add_argument("--script", required=True, help="编排 docx")
    parser.add_argument("--timeline", required=True, help="现行 timeline.json")
    parser.add_argument("--out-dir", default="work", help="成品、时间轴与报告的写入目录")
    parser.add_argument("--zero-insert", action="store_true", help="强制插入量为 0，只做时间轴自检")
    parser.add_argument("--install", action="store_true", help="校验通过后替换 backend/data 下的文件")
    parser.add_argument("--gain-db", type=float, default=None, help="播报统一增益")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if np is None:
        print("错误：缺少 numpy，请先安装后再运行。", file=sys.stderr)
        return 2

    source = Path(args.source)
    voice_dir = Path(args.voice_dir)
    script_path = Path(args.script)
    timeline_path = Path(args.timeline)
    out_dir = Path(args.out_dir)
    try:
        for path, label in (
            (source, "源母带"),
            (voice_dir, "播报素材目录"),
            (script_path, "编排文件"),
            (timeline_path, "timeline.json"),
        ):
            if not path.exists():
                raise ToolError(2, f"{label}不存在：{path}")
        if not voice_dir.is_dir():
            raise ToolError(2, f"播报素材路径不是目录：{voice_dir}")
        out_dir.mkdir(parents=True, exist_ok=True)

        script_rounds = parse_script(script_path)
        timeline = load_timeline(timeline_path)
        qids_by_round = timeline_question_ids(timeline, script_rounds)
        source_rounds = build_source_rounds(script_rounds, qids_by_round)
        voice_durations = read_voice_durations(voice_dir)

        if args.zero_insert:
            if args.install:
                print("零插入自检不执行安装。", file=sys.stderr)
            plan = build_mix_plan(
                script_rounds,
                source_rounds,
                timeline,
                voice_durations,
                zero_insert=True,
            )
            output_timeline = out_dir / "timeline.next.json"
            write_json(output_timeline, plan.timeline)
            differences = compare_timelines(timeline, plan.timeline)
            write_report(
                out_dir / "voice-mix-report.md",
                plan,
                None,
                zero_insert=True,
                differences=differences,
            )
            if differences:
                print(f"零插入自检失败，发现 {len(differences)} 处差异：", file=sys.stderr)
                for difference in differences[:20]:
                    print(f"  {difference}", file=sys.stderr)
                return 4
            print("零插入自检通过：重算结果与输入的 timeline.json 一致。")
            return 0

        gain_info = resolve_gain(source, voice_dir, args.gain_db)
        plan = build_mix_plan(
            script_rounds,
            source_rounds,
            timeline,
            voice_durations,
            zero_insert=False,
        )
        audio_file = timeline["audioFile"]
        output_audio = out_dir / audio_file
        output_timeline = out_dir / "timeline.next.json"
        with tempfile.TemporaryDirectory(prefix="voice-mix-", dir=out_dir) as temporary:
            working_dir = Path(temporary)
            master_wav = build_master_wav(plan, source, voice_dir, gain_info.gain_db, working_dir)
            validate_master(master_wav, plan)
            encode_mp3(master_wav, output_audio)
        validate_mp3(output_audio, plan.total_ms)
        write_json(output_timeline, plan.timeline)
        write_report(
            out_dir / "voice-mix-report.md",
            plan,
            gain_info,
            zero_insert=False,
            differences=[],
        )
        backup_dir = None
        if args.install:
            backup_dir = install_outputs(
                timeline_path.parent, output_audio, output_timeline, audio_file
            )
        print(f"生成完成：{output_audio}")
        print(f"时间轴：{output_timeline}")
        print(f"报告：{out_dir / 'voice-mix-report.md'}")
        print(f"母带总长：{plan.total_ms} 毫秒，插入合计：{plan.insert_total} 毫秒")
        if backup_dir is not None:
            print(f"已备份并替换：{backup_dir}")
        return 0
    except ToolError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return exc.code
    except KeyboardInterrupt:
        print("已中断。", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
