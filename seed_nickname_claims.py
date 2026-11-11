"""Build a nickname claim seed report from historical leaderboard files.

The script only reads leaderboard JSON and writes a report. It never writes
backend/data/nickname_claims.json; the host reviews the report before copying
the claims array into the production file.
"""

import argparse
import json
import os
import re
import sys
import unicodedata
import uuid
from datetime import datetime, timezone

ZERO_WIDTH = re.compile("[\u200b-\u200d\u2060\ufeff]")
WHITESPACE = re.compile(r"\s+")


def nickname_key(value):
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = ZERO_WIDTH.sub("", text)
    text = WHITESPACE.sub(" ", text.strip())
    return text.lower()


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


def iso(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z") if value else None


def integer(value):
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def touch(candidate, seen_at):
    parsed = parse_time(seen_at)
    if parsed is None:
        return
    if candidate["firstSeenAt"] is None or parsed < candidate["firstSeenAt"]:
        candidate["firstSeenAt"] = parsed
    if candidate["lastSeenAt"] is None or parsed > candidate["lastSeenAt"]:
        candidate["lastSeenAt"] = parsed


def candidate_for(group, participant_id):
    candidate = group["candidates"].get(participant_id)
    if candidate is None:
        candidate = {
            "participantId": participant_id,
            "nicknames": set(),
            "offlineBestByRound": {},
            "onlineBestByEvent": {},
            "firstSeenAt": None,
            "lastSeenAt": None,
        }
        group["candidates"][participant_id] = candidate
    return candidate


def group_for(groups, event_id, participant_id, nickname, seen_at):
    key = nickname_key(nickname)
    if not key:
        return None
    group = groups.setdefault((event_id, key), {
        "eventId": event_id,
        "nicknameKey": key,
        "displayTimes": {},
        "candidates": {},
    })
    candidate = candidate_for(group, participant_id)
    candidate["nicknames"].add(nickname)
    current = group["displayTimes"].get(nickname)
    parsed = parse_time(seen_at)
    if current is None or (parsed is not None and (current[0] is None or parsed > current[0])):
        group["displayTimes"][nickname] = (parsed, nickname)
    touch(candidate, seen_at)
    return group, candidate


def summarize(group):
    rows = []
    for candidate in group["candidates"].values():
        offline_score = sum(candidate["offlineBestByRound"].values())
        online_score = sum(candidate["onlineBestByEvent"].values())
        rows.append({
            "participantId": candidate["participantId"],
            "nicknames": sorted(candidate["nicknames"]),
            "offlineScore": offline_score,
            "onlineScore": online_score,
            "totalScore": offline_score + online_score,
            "offlineRoundCount": len(candidate["offlineBestByRound"]),
            "firstSeenAt": iso(candidate["firstSeenAt"]),
            "lastSeenAt": iso(candidate["lastSeenAt"]),
        })
    rows.sort(key=lambda item: (
        -item["totalScore"],
        -item["offlineRoundCount"],
        item["firstSeenAt"] or "9999",
        item["participantId"],
    ))
    display = ""
    display_time = None
    for nickname, (seen_at, _) in group["displayTimes"].items():
        if seen_at is not None and (display_time is None or seen_at > display_time):
            display = nickname
            display_time = seen_at
        elif not display:
            display = nickname
    return {
        "eventId": group["eventId"],
        "nicknameKey": group["nicknameKey"],
        "nickname": display,
        "ambiguous": len(rows) > 1,
        "candidate": rows[0] if rows else None,
        "candidates": rows,
    }


def build(offline_records, online_records):
    groups = {}

    for snapshot in offline_records:
        event_id = str(snapshot.get("eventId") or "")
        round_id = snapshot.get("roundId") or f"legacy-{snapshot.get('finishedAt')}"
        finished_at = snapshot.get("finishedAt")
        for entry in snapshot.get("entries") or []:
            if not isinstance(entry, dict):
                continue
            nickname = str(entry.get("nickname") or "").strip()
            participant_id = entry.get("participantId") or entry.get("playerId")
            if not nickname or not participant_id:
                continue
            seen_at = entry.get("joinedAt") or finished_at
            built = group_for(groups, event_id, str(participant_id), nickname, seen_at)
            if built is None:
                continue
            _, candidate = built
            best = candidate["offlineBestByRound"].get(round_id)
            score = integer(entry.get("score"))
            if best is None or score > best:
                candidate["offlineBestByRound"][round_id] = score

    for record in online_records:
        event_id = str(record.get("eventId") or "")
        nickname = str(record.get("nickname") or "").strip()
        participant_id = record.get("participantId")
        if not nickname or not participant_id:
            continue
        built = group_for(groups, event_id, str(participant_id), nickname, record.get("lockedAt"))
        if built is None:
            continue
        _, candidate = built
        score = integer(record.get("score"))
        best = candidate["onlineBestByEvent"].get(event_id)
        if best is None or score > best:
            candidate["onlineBestByEvent"][event_id] = score

    return [summarize(groups[key]) for key in sorted(groups)]


def to_claims(rows):
    now = datetime.now(timezone.utc)
    claims = []
    for row in rows:
        winner = row["candidate"]
        claims.append({
            "claimId": "c-" + str(uuid.uuid4()),
            "eventId": row["eventId"],
            "nicknameKey": row["nicknameKey"],
            "nickname": row["nickname"],
            "participantId": winner["participantId"],
            "source": "SEED",
            "claimedAt": iso(now),
            "updatedAt": iso(now),
        })
    return claims


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description="生成昵称认领种子报告")
    parser.add_argument("--offline", default=find_file("data/leaderboards.json"))
    parser.add_argument("--online", default=find_file("data/online_leaderboard.json"))
    parser.add_argument("--output", default=os.path.join("work", "nickname-claim-seed.json"))
    arguments = parser.parse_args()

    offline_records = load_records(arguments.offline)
    online_records = load_records(arguments.online)
    rows = build(offline_records, online_records)
    claims = to_claims(rows)
    review = [row for row in rows if row["ambiguous"]]

    report = {
        "generatedAt": iso(datetime.now(timezone.utc)),
        "sourceFiles": {
            "offline": os.path.abspath(arguments.offline),
            "online": os.path.abspath(arguments.online),
        },
        "summary": {
            "nicknames": len(rows),
            "ambiguous": len(review),
            "claims": len(claims),
        },
        "claims": claims,
        "review": review,
    }

    output = os.path.abspath(arguments.output)
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        json.dump(report, file, ensure_ascii=False, indent=2)

    print(f"昵称总数：{len(rows)}")
    print(f"需要人工确认：{len(review)}")
    print(f"输出：{output}")
    for row in review:
        ids = "、".join(item["participantId"] for item in row["candidates"])
        print(f"  [冲突] {row['nickname']} -> {ids}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
