import json
import os
import sys
from datetime import datetime, timezone


def display_width(value):
    if value is None:
        return 0
    return sum(2 if ord(character) > 127 else 1 for character in str(value))


def pad_right(value, target_width):
    if value is None:
        value = ""
    text = str(value)
    current_width = display_width(text)
    if current_width >= target_width:
        return text
    return text + " " * (target_width - current_width)


def find_file(relative_name):
    candidates = [
        relative_name,
        os.path.join("backend", relative_name),
        os.path.join("..", relative_name),
    ]
    for candidate in candidates:
        if os.path.exists(candidate):
            return os.path.abspath(candidate)
    return os.path.abspath(candidates[0])


def load_records(path):
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, list):
        raise ValueError(f"排行榜根节点必须为数组：{path}")
    return value


def parse_time(value):
    if not value:
        return None
    text = str(value)
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed


def format_time(value):
    parsed = parse_time(value)
    if parsed is None:
        return "未知时间"
    return parsed.astimezone().strftime("%Y-%m-%d %H:%M:%S")


def integer(value):
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def canonical_online(records):
    chosen = {}
    for record in records:
        participant_id = record.get("participantId")
        if not participant_id:
            continue
        existing = chosen.get(participant_id)
        if existing is None:
            chosen[participant_id] = record
            continue
        existing_time = parse_time(existing.get("lockedAt")) or datetime.min.replace(tzinfo=timezone.utc)
        candidate_time = parse_time(record.get("lockedAt")) or datetime.min.replace(tzinfo=timezone.utc)
        if candidate_time > existing_time:
            chosen[participant_id] = record
        elif candidate_time == existing_time:
            if str(record.get("attemptId") or "") < str(existing.get("attemptId") or ""):
                chosen[participant_id] = record
    return list(chosen.values())


def online_sort_key(record):
    return (
        -integer(record.get("score")),
        integer(record.get("durationMs")),
        parse_time(record.get("lockedAt")) or datetime.min.replace(tzinfo=timezone.utc),
        str(record.get("nickname") or ""),
    )


def offline_better(candidate, existing):
    if candidate["score"] != existing["score"]:
        return candidate["score"] > existing["score"]
    if candidate["durationMs"] != existing["durationMs"]:
        return candidate["durationMs"] < existing["durationMs"]
    if candidate["finishedAt"] and existing["finishedAt"] and candidate["finishedAt"] != existing["finishedAt"]:
        return candidate["finishedAt"] < existing["finishedAt"]
    if candidate["finishedAt"] and not existing["finishedAt"]:
        return True
    if not candidate["finishedAt"] and existing["finishedAt"]:
        return False
    return candidate["sequence"] < existing["sequence"]


def aggregate_total(event_id, history, online_records):
    aggregates = {}
    sequence = 0
    snapshots = [snapshot for snapshot in history if snapshot.get("eventId") == event_id]
    for snapshot_index, snapshot in enumerate(snapshots, 1):
        round_id = snapshot.get("roundId") or f"legacy-round-{snapshot_index}"
        round_name = snapshot.get("roundName") or f"第 {snapshot_index} 轮"
        finished_at = parse_time(snapshot.get("finishedAt"))
        for entry in snapshot.get("entries") or []:
            participant_id = entry.get("participantId") or entry.get("playerId")
            if not participant_id:
                continue
            aggregate = aggregates.setdefault(participant_id, {
                "participantId": participant_id,
                "rounds": {},
                "online": None,
            })
            candidate = {
                "roundId": round_id,
                "roundName": round_name,
                "nickname": str(entry.get("nickname") or ""),
                "score": integer(entry.get("score")),
                "durationMs": integer(entry.get("durationMs")),
                "finishedAt": finished_at,
                "sequence": sequence,
            }
            sequence += 1
            existing = aggregate["rounds"].get(round_id)
            if existing is None or offline_better(candidate, existing):
                aggregate["rounds"][round_id] = candidate

    for record in canonical_online(online_records):
        participant_id = record.get("participantId")
        if not participant_id:
            continue
        aggregate = aggregates.setdefault(participant_id, {
            "participantId": participant_id,
            "rounds": {},
            "online": None,
        })
        aggregate["online"] = record

    entries = []
    for aggregate in aggregates.values():
        rounds = list(aggregate["rounds"].values())
        online = aggregate["online"]
        offline_score = sum(item["score"] for item in rounds)
        offline_duration = sum(item["durationMs"] for item in rounds)
        online_score = integer(online.get("score")) if online else 0
        online_duration = integer(online.get("durationMs")) if online else 0
        offline_last = max(
            (item["finishedAt"] for item in rounds if item["finishedAt"]),
            default=None,
        )
        online_last = parse_time(online.get("lockedAt")) if online else None
        candidates = [value for value in (offline_last, online_last) if value]
        last_activity = max(candidates) if candidates else None
        nickname = ""
        if online and online_last and (offline_last is None or online_last >= offline_last):
            nickname = str(online.get("nickname") or "")
        elif rounds:
            latest = rounds[0]
            for item in rounds[1:]:
                if item["finishedAt"] and (latest["finishedAt"] is None or item["finishedAt"] > latest["finishedAt"]):
                    latest = item
            nickname = latest["nickname"]
        entries.append({
            "participantId": aggregate["participantId"],
            "nickname": nickname,
            "totalScore": offline_score + online_score,
            "offlineScore": offline_score,
            "onlineScore": online_score,
            "totalDurationMs": offline_duration + online_duration,
            "offlineRoundCount": len(rounds),
            "onlineLocked": online is not None,
            "lastActivityAt": last_activity,
            "offlineContributions": rounds,
            "onlineContribution": online,
        })

    entries.sort(key=lambda item: (
        -item["totalScore"],
        item["totalDurationMs"],
        item["lastActivityAt"] or datetime.min.replace(tzinfo=timezone.utc),
        item["nickname"],
    ))
    return entries


def generate_summary():
    offline_path = find_file(os.path.join("data", "leaderboards.json"))
    online_path = find_file(os.path.join("data", "online_leaderboard.json"))
    txt_path = os.path.join(os.path.dirname(offline_path), "leaderboard_summary.txt")
    history = load_records(offline_path)
    online = load_records(online_path)
    history.sort(key=lambda snapshot: (
        parse_time(snapshot.get("finishedAt")) is None,
        parse_time(snapshot.get("finishedAt")) or datetime.min.replace(tzinfo=timezone.utc),
    ))

    event_names = {}
    for snapshot in history:
        event_id = snapshot.get("eventId")
        event_name = snapshot.get("eventName")
        if event_id and event_name and event_id not in event_names:
            event_names[event_id] = event_name

    lines = []
    for round_index, snapshot in enumerate(history, 1):
        entries = snapshot.get("entries") or []
        round_name = snapshot.get("roundName") or f"第 {round_index} 轮"
        event_name = snapshot.get("eventName") or snapshot.get("eventId") or "未知活动"
        lines.append("=" * 80)
        lines.append(f"【线下轮次榜 · {round_name}】{event_name}")
        lines.append(
            f"结束时间: {format_time(snapshot.get('finishedAt'))} | 参与人数: {len(entries)} 人"
        )
        lines.append("-" * 80)
        lines.append(
            f"{pad_right('排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('得分', 10)} "
            f"{pad_right('耗时', 14)} {pad_right('已答', 10)} 参与者ID"
        )
        lines.append("-" * 80)
        if not entries:
            lines.append("（本轮暂无有效答题记录）")
        else:
            for entry in entries:
                duration = integer(entry.get("durationMs")) / 1000.0
                answered = f"{integer(entry.get('answeredCount'))}/{integer(entry.get('totalQuestions'))}"
                participant_id = entry.get("participantId") or entry.get("playerId") or ""
                lines.append(
                    f"{pad_right('第 ' + str(integer(entry.get('rank'))) + ' 名', 10)} "
                    f"{pad_right(entry.get('nickname', ''), 18)} "
                    f"{pad_right(str(integer(entry.get('score'))) + ' 分', 10)} "
                    f"{pad_right(f'{duration:.1f} 秒', 14)} "
                    f"{pad_right(answered, 10)} {participant_id}"
                )

    online_by_event = {}
    for record in online:
        event_id = record.get("eventId")
        if not event_id:
            continue
        online_by_event.setdefault(event_id, []).append(record)

    if not online_by_event:
        lines.append("=" * 80)
        lines.append("【线上积分榜】")
        lines.append("（暂无线上锁定记录）")
    else:
        for event_id, records in online_by_event.items():
            canonical = sorted(canonical_online(records), key=online_sort_key)
            lines.append("=" * 80)
            lines.append(f"【线上积分榜】{event_names.get(event_id, event_id)}")
            lines.append(
                f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} | "
                f"锁定人数: {len(canonical)} 人"
            )
            lines.append("-" * 80)
            lines.append(
                f"{pad_right('排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('得分', 10)} "
                f"{pad_right('耗时', 14)} {pad_right('已答', 10)} 锁定时间"
            )
            lines.append("-" * 80)
            for rank, record in enumerate(canonical, 1):
                duration = integer(record.get("durationMs")) / 1000.0
                answered = f"{integer(record.get('answeredCount'))}/{integer(record.get('totalQuestions'))}"
                lines.append(
                    f"{pad_right('第 ' + str(rank) + ' 名', 10)} "
                    f"{pad_right(record.get('nickname', ''), 18)} "
                    f"{pad_right(str(integer(record.get('score'))) + ' 分', 10)} "
                    f"{pad_right(f'{duration:.1f} 秒', 14)} "
                    f"{pad_right(answered, 10)} {format_time(record.get('lockedAt'))}"
                )

    event_ids = []
    for snapshot in history:
        event_id = snapshot.get("eventId")
        if event_id and event_id not in event_ids:
            event_ids.append(event_id)
    for record in online:
        event_id = record.get("eventId")
        if event_id and event_id not in event_ids:
            event_ids.append(event_id)

    if not event_ids:
        lines.append("=" * 80)
        lines.append("【总榜】")
        lines.append("规则: 线下各轮最佳成绩之和 + 线上锁定成绩")
        lines.append("（暂无有效成绩）")
    else:
        for event_id in event_ids:
            entries = aggregate_total(event_id, history, online)
            lines.append("=" * 80)
            lines.append(f"【总榜】{event_names.get(event_id, event_id)}")
            lines.append("规则: 线下各轮最佳成绩之和 + 线上锁定成绩")
            lines.append("-" * 80)
            lines.append(
                f"{pad_right('排名', 10)} {pad_right('玩家昵称', 18)} {pad_right('总分', 10)} "
                f"{pad_right('线下分', 10)} {pad_right('线上分', 10)} {pad_right('总耗时', 14)} "
                f"{pad_right('线下轮次', 12)} 线上锁定"
            )
            lines.append("-" * 80)
            if not entries:
                lines.append("（暂无有效成绩）")
            else:
                for rank, entry in enumerate(entries, 1):
                    duration = entry["totalDurationMs"] / 1000.0
                    lines.append(
                        f"{pad_right('第 ' + str(rank) + ' 名', 10)} "
                        f"{pad_right(entry['nickname'], 18)} "
                        f"{pad_right(str(entry['totalScore']) + ' 分', 10)} "
                        f"{pad_right(str(entry['offlineScore']) + ' 分', 10)} "
                        f"{pad_right(str(entry['onlineScore']) + ' 分', 10)} "
                        f"{pad_right(f'{duration:.1f} 秒', 14)} "
                        f"{pad_right(str(entry['offlineRoundCount']) + ' 轮', 12)} "
                        f"{'是' if entry['onlineLocked'] else '否'}"
                    )

    lines.append("=" * 80)
    with open(txt_path, "w", encoding="utf-8") as file:
        file.write("\n".join(lines) + "\n")
    print(f"已生成线下轮次榜、线上积分榜与总榜：{txt_path}")


def main():
    try:
        generate_summary()
    except Exception as error:
        print(f"排行榜解析失败：{error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
