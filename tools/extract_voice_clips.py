#!/usr/bin/env python3
"""Extract the 12 announcer clips from the legacy offline master."""

from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np


SAMPLE_RATE = 32000


CLIPS = [
    ("开幕.mp3", 200, 32935),
    ("1→2改.wav", 69735, 6970),
    ("2→3改改.wav", 128735, 5790),
    ("3→4.wav", 181735, 12680),
    ("4→5.wav", 220015, 14740),
    ("5→6.wav", 268355, 10120),
    ("6→7.wav", 329075, 12640),
    ("7→8.wav", 372315, 13100),
    ("8→9.wav", 441015, 13920),
    ("9→10.wav", 487535, 11160),
    ("结束改.wav", 547295, 14660),
    ("再见.wav", 2777900, 1910),
]


class ExtractionError(Exception):
    pass


def run_ffmpeg(args: list[str]) -> None:
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ExtractionError(f"ffmpeg 失败：{detail}")


def extract(source: Path, destination: Path, start_ms: int, duration_ms: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    run_ffmpeg(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-ss",
            f"{start_ms / 1000:.3f}",
            "-t",
            f"{duration_ms / 1000:.3f}",
            "-i",
            str(source),
            "-af",
            "aresample=32000",
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            "-c:a",
            "pcm_s16le",
            "-f",
            "wav",
            str(destination),
        ]
    )


def read_wav(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as reader:
        if reader.getnchannels() != 1 or reader.getsampwidth() != 2:
            raise ExtractionError(f"格式不符：{path}")
        frames = reader.readframes(reader.getnframes())
        return np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0, reader.getframerate()


def max_dbfs(samples: np.ndarray) -> float:
    if samples.size == 0:
        return float("-inf")
    peak = float(np.max(np.abs(samples)))
    if peak <= 0.0:
        return float("-inf")
    return 20.0 * math.log10(peak)


def check_silence(source: Path, start_ms: int, duration_ms: int, label: str) -> None:
    result = subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-nostats",
            "-ss",
            f"{max(0, start_ms) / 1000:.3f}",
            "-t",
            f"{duration_ms / 1000:.3f}",
            "-i",
            str(source),
            "-af",
            "volumedetect",
            "-f",
            "null",
            "-",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    text = result.stderr.decode("utf-8", "replace")
    matches = re.findall(r"max_volume:\s*(-?inf|-?\d+(?:\.\d+)?)\s*dB", text)
    if not matches:
        raise ExtractionError(f"无法测量静音边界：{label}")
    value = matches[-1]
    level = float("-inf") if value == "-inf" else float(value)
    if level > -45.0:
        raise ExtractionError(f"{label} 边界不是静音：{level:.1f} dBFS")


def verify_clip(path: Path, duration_ms: int) -> dict[str, float | int]:
    samples, rate = read_wav(path)
    if rate != SAMPLE_RATE:
        raise ExtractionError(f"采样率不符：{path}")
    expected = int(round(duration_ms * SAMPLE_RATE / 1000))
    if abs(len(samples) - expected) > 2:
        raise ExtractionError(f"时长不符：{path} 预期 {expected} 帧，实际 {len(samples)} 帧")
    rms = float(np.sqrt(np.mean(samples**2)))
    if rms <= 1e-4:
        raise ExtractionError(f"片段近乎静音：{path}")
    return {
        "frames": int(len(samples)),
        "duration_ms": int(round(len(samples) * 1000 / SAMPLE_RATE)),
        "rms_dbfs": round(20.0 * math.log10(rms), 2),
        "peak_dbfs": round(max_dbfs(samples), 2),
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="从旧线下母带提取 12 段主持人播报。")
    parser.add_argument(
        "--source",
        required=True,
        help="含主持人播报的旧线下母带；不得指向已混入新 BGM 的成品",
    )
    parser.add_argument("--out-dir", default="work/voice-extract", help="播报片段输出目录")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    source = Path(args.source)
    out_dir = Path(args.out_dir)
    if not source.is_file():
        print(f"错误：源母带不存在：{source}", file=sys.stderr)
        return 2

    manifest: list[dict[str, object]] = []
    try:
        for name, start_ms, duration_ms in CLIPS:
            destination = out_dir / name
            extract(source, destination, start_ms, duration_ms)
            check_silence(source, start_ms - 120, 120, f"{name} 起始")
            check_silence(source, start_ms + duration_ms, 120, f"{name} 结束")
            stats = verify_clip(destination, duration_ms)
            manifest.append(
                {
                    "name": name,
                    "source": str(source),
                    "start_ms": start_ms,
                    "expected_duration_ms": duration_ms,
                    **stats,
                }
            )
            print(f"{name}: {stats['duration_ms']} ms, RMS {stats['rms_dbfs']} dBFS", flush=True)
        (out_dir / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    except ExtractionError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 3
    print(f"完成：{out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
