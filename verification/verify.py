"""
Golden Man verification checker. Runs on GitHub Actions (no server to maintain).

- When a registration issue is opened: checks that profile, adds it to players.json, comments and closes the issue.
- Every hour: re-checks everyone in players.json.

Checks (from Hypixel's API):
  1. All API settings are on (otherwise we can't see anything)          -> pending
  2. Registered on a fresh profile below SkyBlock level 7               -> failed if not
  3. Auction House never used (bids, wins, auctions created, coins spent) -> failed if used
  4. Every co-op member is registered too                              -> pending until they are
Failures are permanent ("failed" never goes back to "verified" on its own).
The developer (DEV_UUIDS) is never marked failed; their problems are stored as notes instead.
"""
import datetime
import json
import os
import re
import sys
import urllib.error
import urllib.request

PLAYERS_FILE = "players.json"
DEV_UUIDS = {"1452663962ef444181bcd4c37d9200cc"}  # tedsoup, creator of the mod
MAX_LEVEL_AT_START = 7
AUCTION_KEYS = ["bids", "won", "created", "gold_spent", "highest_bid"]

API_KEY = os.environ.get("HYPIXEL_API_KEY", "")
GH_TOKEN = os.environ.get("GITHUB_TOKEN", "")
REPO = os.environ.get("GITHUB_REPOSITORY", "")


def http_json(url, headers=None, data=None, method=None):
    req = urllib.request.Request(url, headers=headers or {}, data=data, method=method)
    with urllib.request.urlopen(req, timeout=30) as res:
        body = res.read()
        return json.loads(body) if body else {}


def hypixel_profile(profile_id):
    data = http_json(f"https://api.hypixel.net/v2/skyblock/profile?profile={profile_id}", {"API-Key": API_KEY})
    return data.get("profile")


def current_name(uuid):
    try:
        return http_json(f"https://sessionserver.mojang.com/session/minecraft/profile/{uuid}")["name"]
    except Exception:
        return None


def clean_uuid(u):
    return re.sub(r"[^0-9a-fA-F]", "", u or "").lower()


def check(uuid, entry, players, registering):
    """Returns (status, reasons)."""
    fails, pending = [], []
    try:
        prof = hypixel_profile(entry["profile"])
    except urllib.error.HTTPError as e:
        return "pending", [f"Hypixel API error {e.code}"]
    if not prof:
        return "pending", ["profile not found"]

    members = prof.get("members", {})
    member = members.get(uuid)
    if member is None:
        return "failed", ["not a member of the registered profile"]

    # 1. API settings
    missing = []
    if "inventory" not in member:
        missing.append("Inventory API")
    if "collection" not in member:
        missing.append("Collections API")
    if "banking" not in prof:
        missing.append("Banking API")
    if missing:
        pending.append("turn on: " + ", ".join(missing))

    # 2. Fresh profile (only checked when registering)
    xp = member.get("leveling", {}).get("experience", 0) or 0
    level = int(xp // 100)
    if registering and level >= MAX_LEVEL_AT_START:
        fails.append(f"registered at SkyBlock level {level} (must be below {MAX_LEVEL_AT_START})")

    # 3. Auction House
    auctions = member.get("player_stats", {}).get("auctions", {}) or {}
    used = [k for k in AUCTION_KEYS if (auctions.get(k) or 0) > 0]
    if auctions.get("total_bought"):
        used.append("total_bought")
    if used:
        fails.append("used the Auction House (" + ", ".join(used) + ")")

    # 4. Co-op members must also be registered
    for other in members:
        if other == uuid:
            continue
        if members[other].get("profile", {}).get("deletion_notice"):
            continue  # left the co-op
        if other not in players:
            pending.append(f"co-op member {current_name(other) or other} isn't registered")

    if fails:
        return "failed", fails
    if pending:
        return "pending", pending
    return "verified", []


def apply_result(uuid, entry, status, reasons):
    if uuid in DEV_UUIDS:
        entry["status"] = "verified"
        entry["dev"] = True
        entry["notes"] = reasons  # what would have failed
        return
    if entry.get("status") == "failed":
        return  # failures are permanent; appeals are handled by hand
    entry["status"] = status
    entry["reasons"] = reasons
    if status == "verified" and not entry.get("verified_since"):
        entry["verified_since"] = datetime.date.today().isoformat()


def gh(path, payload=None, method="GET"):
    data = json.dumps(payload).encode() if payload is not None else None
    return http_json(f"https://api.github.com/repos/{REPO}{path}", {
        "Authorization": f"Bearer {GH_TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    }, data, method)


def parse_issue(body):
    fields = {}
    for label, value in re.findall(r"### (.+?)\s*\n\s*\n(.+?)(?:\n|$)", body or ""):
        fields[label.strip().lower()] = value.strip()
    return fields.get("username"), clean_uuid(fields.get("uuid")), (fields.get("profile id") or "").strip().lower()


def register(players, name, uuid, profile):
    """Checks a new registration, stores it, and returns a message describing the result."""
    if not uuid or not profile:
        return "Couldn't read the UUID / Profile ID. Please register again from the mod's menu."
    if uuid in players and players[uuid].get("status") == "failed":
        return "This account already failed Golden Man, so it can't register again."
    entry = {"name": current_name(uuid) or name, "profile": profile,
             "registered": datetime.date.today().isoformat()}
    status, reasons = check(uuid, entry, players, registering=True)
    players[uuid] = entry
    apply_result(uuid, entry, status, reasons)
    why = entry.get("notes") if entry.get("dev") else entry.get("reasons")
    msg = f"**{entry['name']}**: {entry['status'].upper()}" + (("\n\n- " + "\n- ".join(why)) if why else "")
    if entry.get("dev"):
        msg += "\n\n(Developer account: these checks are only notes.)"
    return msg


def main():
    if not API_KEY:
        sys.exit("HYPIXEL_API_KEY secret is missing")
    with open(PLAYERS_FILE) as f:
        data = json.load(f)
    players = data.setdefault("players", {})

    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")

    if event_name == "repository_dispatch" and event_path:
        with open(event_path) as f:
            event = json.load(f)
        payload = event.get("client_payload", {})
        if event.get("action") == "quit":
            uuid = clean_uuid(payload.get("uuid"))
            # Ending a run removes you from the list; a failed run stays on record.
            if uuid in players and players[uuid].get("status") != "failed":
                del players[uuid]
                print(f"{uuid} ended their run")
        else:
            print(register(players, payload.get("name"), clean_uuid(payload.get("uuid")),
                           (payload.get("profile_id") or "").strip().lower()))
    elif event_name == "issues" and event_path:
        with open(event_path) as f:
            issue = json.load(f)["issue"]
        name, uuid, profile = parse_issue(issue.get("body"))
        msg = register(players, name, uuid, profile)
        gh(f"/issues/{issue['number']}/comments", {"body": msg}, "POST")
        gh(f"/issues/{issue['number']}", {"state": "closed"}, "PATCH")
    else:
        for uuid, entry in list(players.items()):
            if entry.get("status") == "failed":
                continue
            status, reasons = check(uuid, entry, players, registering=False)
            entry["name"] = current_name(uuid) or entry.get("name")
            apply_result(uuid, entry, status, reasons)

    data["updated"] = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    with open(PLAYERS_FILE, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    main()
