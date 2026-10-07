import json
import os
import sys
from datetime import datetime

def display_width(s):
    if s is None:
        return 0
    return sum(2 if ord(c) > 127 else 1 for c in str(s))

def pad_right(s, target_width):
    if s is None:
        s = ""
    s = str(s)
    current = display_width(s)
    if current >= target_width:
        return s
    return s + " " * (target_width - current)

def find_file(relative_name):
    candidates = [
        relative_name,
        os.path.join("backend", relative_name),
        os.path.join("..", relative_name),
    ]
    for c in candidates:
        if os.path.exists(c):
            return os.path.abspath(c)
    return os.path.abspath(candidates[0])

def parse_iso_time(ts_str):
    if not ts_str:
        return "未知时间"
    try:
        if ts_str.endswith("Z"):
            ts_str = ts_str[:-1] + "+00:00"
        dt = datetime.fromisoformat(ts_str)
        return dt.astimezone().strftime("%Y-%m-%d %H:%M:%S")
    except Exception:
        return str(ts_str)

def generate_summary():
    json_path = find_file(os.path.join("data", "leaderboards.json"))
    if not os.path.exists(json_path):
        print(f"未找到排行榜数据文件：{json_path}")
        return

    txt_dir = os.path.dirname(json_path)
    txt_path = os.path.join(txt_dir, "leaderboard_summary.txt")

    with open(json_path, "r", encoding="utf-8") as f:
        history = json.load(f)

    lines = []
    round_index = 1
    all_entries = []

    for snapshot in history:
        event_name = snapshot.get("eventName", "未知活动")
        finished_at_raw = snapshot.get("finishedAt")
        finished_at = parse_iso_time(finished_at_raw)
        entries = snapshot.get("entries", [])
        player_count = len(entries)

        lines.append("=" * 80)
        lines.append(f"【第 {round_index} 轮】{event_name}")
        lines.append(f"结束时间: {finished_at} | 参与人数: {player_count} 人")
        lines.append("-" * 80)
        lines.append(
            f"{pad_right('排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('最终得分', 10)} {pad_right('答题耗时', 14)} 玩家ID"
        )
        lines.append("-" * 80)

        if player_count == 0:
            lines.append("（本轮无有效答题记录）")
        else:
            for entry in entries:
                rank = entry.get("rank", 0)
                rank_str = f"第 {rank} 名"
                nickname = entry.get("nickname", "未命名")
                score = entry.get("score", 0)
                score_str = f"{score} 分"
                duration_ms = entry.get("durationMs", 0) or 0
                duration_sec = duration_ms / 1000.0
                duration_str = f"{duration_sec:.1f} 秒"
                player_id = entry.get("playerId", "")

                lines.append(
                    f"{pad_right(rank_str, 10)} {pad_right(nickname, 18)} {pad_right(score_str, 10)} {pad_right(duration_str, 14)} {player_id}"
                )

                all_entries.append({
                    "round_index": round_index,
                    "round_rank": rank,
                    "nickname": nickname,
                    "score": score,
                    "duration_ms": duration_ms,
                    "finished_at_raw": finished_at_raw or "",
                    "finished_at_str": finished_at,
                    "player_id": player_id,
                })
        lines.append("")
        round_index += 1

    # Grand Summary
    all_entries.sort(key=lambda x: (
        -x["score"],
        x["duration_ms"],
        x["finished_at_raw"],
        x["round_rank"],
        x["nickname"],
    ))

    unique_nicknames = len(set(x["nickname"] for x in all_entries))
    total_rounds = len(history)
    total_plays = len(all_entries)

    lines.append("=" * 120)
    lines.append("【全场总排行榜 · 所有轮次大总结】")
    lines.append(f"总轮次: {total_rounds} 轮 | 累计答题人次: {total_plays} 人次 | 独立玩家数: {unique_nicknames} 人")
    lines.append("排名规则: 最终得分降序 > 答题耗时升序 > 轮次时间升序 > 轮内排名")
    lines.append("-" * 120)
    lines.append(
        f"{pad_right('总排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('最终得分', 10)} {pad_right('答题耗时', 14)} {pad_right('所属轮次', 12)} {pad_right('结束时间', 22)} 玩家ID"
    )
    lines.append("-" * 120)

    if not all_entries:
        lines.append("（全场暂无有效答题记录）")
    else:
        for idx, entry in enumerate(all_entries, 1):
            rank_str = f"第 {idx} 名"
            score_str = f"{entry['score']} 分"
            duration_sec = entry["duration_ms"] / 1000.0
            duration_str = f"{duration_sec:.1f} 秒"
            round_str = f"第 {entry['round_index']} 轮"

            lines.append(
                f"{pad_right(rank_str, 10)} {pad_right(entry['nickname'], 18)} {pad_right(score_str, 10)} {pad_right(duration_str, 14)} {pad_right(round_str, 12)} {pad_right(entry['finished_at_str'], 22)} {entry['player_id']}"
            )

    if unique_nicknames < total_plays:
        by_nickname = {}
        for entry in all_entries:
            nick = entry["nickname"]
            if nick not in by_nickname:
                by_nickname[nick] = []
            by_nickname[nick].append(entry)

        lines.append("-" * 120)
        lines.append(f"【个人最佳成绩总榜 · 奖品发放参考（按昵称去重，共 {unique_nicknames} 人）】")
        lines.append("规则说明: 依据“每人限制参与一次，奖品先到先得”，多次参与者仅取其单轮最佳成绩参与最终排名")
        lines.append("-" * 120)
        lines.append(
            f"{pad_right('总排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('最高得分', 10)} {pad_right('对应耗时', 14)} {pad_right('产生轮次', 12)} {pad_right('参与次数', 16)} 玩家ID"
        )
        lines.append("-" * 120)

        best_rank = 1
        for nick, records in by_nickname.items():
            best = records[0]
            count = len(records)
            rank_str = f"第 {best_rank} 名"
            best_rank += 1
            score_str = f"{best['score']} 分"
            duration_sec = best["duration_ms"] / 1000.0
            duration_str = f"{duration_sec:.1f} 秒"
            round_str = f"第 {best['round_index']} 轮"
            count_str = f"共参与 {count} 轮"

            lines.append(
                f"{pad_right(rank_str, 10)} {pad_right(nick, 18)} {pad_right(score_str, 10)} {pad_right(duration_str, 14)} {pad_right(round_str, 12)} {pad_right(count_str, 16)} {best['player_id']}"
            )

    lines.append("=" * 120)

    content = "\n".join(lines) + "\n"
    with open(txt_path, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"已成功提取并生成 TXT 排行榜汇总（含大总结）：{txt_path}")
    print(f"共解析轮次：{total_rounds} 轮，累计答题记录：{total_plays} 条")

if __name__ == "__main__":
    generate_summary()
