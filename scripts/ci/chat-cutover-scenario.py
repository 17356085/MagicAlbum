#!/usr/bin/env python3
"""Exercise the same public Chat API before a disposable split and after its rollback."""

from __future__ import annotations

import argparse
from base64 import b64decode
from hashlib import sha256
import json
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from chat_cutover_support import Api, ScenarioError, register, require, send_direct


def history(api, room, after=None):
    return api.request("GET", f"/chat/conversations/{room}/messages", params={"afterSeq": after} if after is not None else None)


def contains_message(result, message):
    require(sum(
        row["id"] == message["id"] and row["seq"] == message["seq"] and row.get("content") == message["content"]
        for row in result["items"]
    ) == 1, "history did not contain exactly one matching message")


def hidden_in(result, message):
    rows = [row for row in result["items"] if row["id"] == message["id"]]
    require(len(rows) == 1 and rows[0]["seq"] == message["seq"] and rows[0].get("content") is None,
            "hidden history did not preserve one sequence without a message body")


def group_send(api, room, content):
    return api.request("POST", f"/chat/groups/{room}/messages",
                       body={"clientMessageId": str(uuid.uuid4()), "content": content})


def report(api, status, report_id):
    page = api.request("GET", "/admin/reports", params={"status": status, "page": 1, "size": 50})
    return next((row for row in page["items"] if row["id"] == report_id), None)


def set_preference(api):
    result = api.request("PATCH", "/chat/settings", body={"allowRequests": True})
    require(result["allowRequests"], "chat preferences were not saved")


def seed(transport, origin, state, admin_email):
    marker = uuid.uuid4().hex[:12]
    a, actor = register(transport, origin, marker, "a")
    b, peer = register(transport, origin, marker, "b")
    c, stranger = register(transport, origin, marker, "c")
    _, admin = register(transport, origin, marker, "adm", admin_email)
    for api in (a, b, c):
        set_preference(api)
    a.request("POST", f"/users/{peer['id']}/follow")
    b.request("POST", f"/users/{actor['id']}/follow")

    operation = str(uuid.uuid4())
    direct = send_direct(a, peer["id"], f"{marker} direct before split", operation, 201)
    room = direct["conversationId"]
    first = direct["message"]
    baseline = history(b, room)
    contains_message(baseline, first)

    group = a.request("POST", "/chat/groups", 201, body={"title": f"Cutover {marker}", "inviteeIds": [peer["id"]]})["id"]
    b.request("GET", f"/chat/conversations/{group}/messages", 404)
    b.request("PUT", f"/chat/groups/{group}/invitations/me", 204, body={"accept": True})
    first_group = group_send(a, group, f"{marker} group before split")
    contains_message(history(b, group), first_group)
    b.request("PUT", f"/chat/conversations/{group}/read", 204, body={"seq": first_group["seq"]})

    request = send_direct(a, stranger["id"], f"{marker} stranger before split", str(uuid.uuid4()), 201)
    require(request["state"] == "PENDING" and request["quota"]["remaining"] == 9, "initial stranger quota was not persisted")
    state.update({
        "marker": marker, "accounts": {"a": actor, "b": peer, "c": stranger, "admin": admin},
        "direct": room, "first": first, "operation": operation, "revision": baseline["revision"],
        "group": group, "first_group": first_group, "stranger_room": request["conversationId"],
        "quota": request["quota"]["remaining"],
    })


def split(state, apis):
    a, b, c, admin = (apis[key] for key in ("a", "b", "c", "admin"))
    peer, stranger = (state["accounts"][key]["id"] for key in ("b", "c"))
    room, group, first, first_group = (state[key] for key in ("direct", "group", "first", "first_group"))
    baseline = history(b, room)
    contains_message(baseline, first)
    require(baseline["revision"] == state["revision"], "copy changed history revision")
    retry = send_direct(a, peer, first["content"], state["operation"], 200)
    require(retry["conversationId"] == room and retry["message"]["id"] == first["id"] and not retry["created"],
            "split replay did not preserve the original resource")

    require(b.request("GET", f"/chat/groups/{group}/membership")["state"] == "JOINED", "group membership was lost")
    inbox = b.request("GET", "/chat/conversations")["items"]
    require(any(row["id"] == group and not row["unread"] for row in inbox), "group read cursor was not copied")
    contains_message(history(b, group), first_group)
    next_group = group_send(a, group, f"{state['marker']} group after split")
    contains_message(history(b, group, first_group["seq"]), next_group)

    stranger_detail = a.request("GET", f"/chat/direct/{stranger}")
    require(stranger_detail["id"] == state["stranger_room"] and stranger_detail["quota"]["remaining"] == state["quota"],
            "stranger quota or conversation was lost")
    c.request("PUT", f"/chat/conversations/{state['stranger_room']}/request", body={"accept": True})
    new_quota = send_direct(a, stranger, "second stranger message", str(uuid.uuid4()), 201)["quota"]["remaining"]
    require(new_quota == state["quota"] - 1, "split writer did not apply the shared daily quota")

    added = send_direct(a, peer, f"{state['marker']} direct after split", str(uuid.uuid4()), 201)["message"]
    contains_message(history(b, room, first["seq"]), added)
    report_data = {"targetType": "CHAT_MESSAGE", "targetId": added["id"], "reason": "OTHER"}
    admin.request("POST", "/reports", 404, body=report_data)
    opened = b.request("POST", "/reports", 201, body=report_data)
    evidence = report(admin, "PENDING", opened["id"])
    require(evidence is not None and evidence["chatEvidence"] == added["content"], "admin did not receive exact report evidence")
    state.update({"added": added, "next_group": next_group, "report": opened["id"], "quota": new_quota})


def queue_offline(state, apis):
    admin = apis["admin"]
    report_id = state["report"]
    for _ in range(2):
        result = admin.request("POST", f"/admin/reports/{report_id}/resolve", 202,
                               body={"action": "HIDE", "reason": "Isolated cutover moderation"})
        require(result["status"] == "PENDING" and result["pendingAction"] == "HIDE",
                "offline resolution did not remain queued")
    pending = report(admin, "PENDING", report_id)
    require(pending is not None and pending.get("chatEvidenceUnavailable") is True,
            "pending case was unavailable instead of showing evidence outage")


def upload_digest(api, path):
    require(isinstance(path, str) and re.fullmatch(r"/uploads/[A-Za-z0-9_./-]+", path) and ".." not in path,
            "backup fixture expected a local uploaded image")
    response = api.transport.get(api.origin.removesuffix("/api/v1") + path)
    require(response.status_code == 200 and response.headers.get("Content-Type", "").startswith("image/")
            and response.content, "uploaded image did not return image bytes")
    return sha256(response.content).hexdigest()


def backup_ready(state, apis):
    member, admin = apis["b"], apis["admin"]
    png = b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    uploaded = member.request("POST", "/uploads/images", files={"file": ("backup.png", png, "image/png")})
    state["backup_image"] = {"path": uploaded["url"], "sha256": upload_digest(member, uploaded["url"])}
    require(state["backup_image"]["sha256"] == sha256(png).hexdigest(), "source upload bytes differ from the fixture")
    original = state["first_group"]
    opened = member.request("POST", "/reports", 201,
                            body={"targetType": "CHAT_MESSAGE", "targetId": original["id"], "reason": "OTHER"})
    admin.request("POST", f"/admin/reports/{opened['id']}/resolve", 202,
                  body={"action": "HIDE", "reason": "Isolated backup fixture"})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if report(admin, "HIDDEN", opened["id"]):
            hidden_in(history(member, state["group"]), original)
            state["backup_confirmed_report"] = opened["id"]
            return
        time.sleep(0.5)
    raise ScenarioError("backup fixture did not produce a confirmed chat moderation receipt")


def backup_pending(state, apis):
    queue_offline(state, apis)
    apis["c"].request("POST", f"/users/{state['accounts']['b']['id']}/follow")


def backup_recovered(state, apis):
    recover(state, apis)
    original = state["first_group"]
    hidden_in(history(apis["b"], state["group"]), original)
    saved = report(apis["admin"], "HIDDEN", state["backup_confirmed_report"])
    require(saved is not None and saved["chatEvidence"] == original["content"],
            "restore lost the previously confirmed chat decision")
    image = state["backup_image"]
    require(upload_digest(apis["b"], image["path"]) == image["sha256"], "restored upload bytes differ from the source")
    rollback(state, apis)


def recover(state, apis):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        hidden = report(apis["admin"], "HIDDEN", state["report"])
        if hidden:
            require(hidden["status"] == "HIDDEN" and hidden["chatEvidence"] == state["added"]["content"],
                    "confirmed case did not retain its exact evidence")
            observed = history(apis["b"], state["direct"])
            hidden_in(observed, state["added"])
            require(observed["revision"] == state["revision"] + 1, "moderation did not advance revision once")
            state["revision"] = observed["revision"]
            return
        time.sleep(0.5)
    raise ScenarioError("queued moderation was not confirmed after the chat process restarted")


def rollback(state, apis):
    a, b = apis["a"], apis["b"]
    peer, stranger = (state["accounts"][key]["id"] for key in ("b", "c"))
    room, group, first = (state[key] for key in ("direct", "group", "first"))
    result = history(b, room)
    contains_message(result, first)
    hidden_in(result, state["added"])
    require(result["revision"] == state["revision"], "reverse copy lost the hidden history revision")
    restored_case = report(apis["admin"], "HIDDEN", state["report"])
    require(restored_case is not None and restored_case["chatEvidence"] == state["added"]["content"],
            "rollback lost the confirmed case or its scoped evidence")
    contains_message(history(b, group, state["first_group"]["seq"]), state["next_group"])
    detail = a.request("GET", f"/chat/direct/{stranger}")
    require(detail["quota"]["remaining"] == state["quota"], "reverse copy lost the split writer's usage")
    operation = str(uuid.uuid4())
    content = f"{state['marker']} direct after rollback"
    fresh = send_direct(a, peer, content, operation, 201)["message"]
    repeated = send_direct(a, peer, content, operation, 200)["message"]
    require(fresh["id"] == repeated["id"] and fresh["id"] > state["added"]["id"]
            and fresh["seq"] > state["added"]["seq"],
            "rollback writer duplicated a replay or reused a sequence")
    synced = history(b, room, first["seq"])
    contains_message(synced, fresh)
    hidden_in(synced, state["added"])


STEPS = {
    "split": ("seed", split),
    "queue-offline": ("split", queue_offline),
    "recover": ("queue-offline", recover),
    "rollback": ("recover", rollback),
    "backup-ready": ("split", backup_ready),
    "backup-pending": ("backup-ready", backup_pending),
    "backup-recovered": ("backup-pending", backup_recovered),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("seed", *STEPS))
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--admin-email", required=True)
    args = parser.parse_args()
    try:
        endpoint = urlsplit(args.api_url)
        require(endpoint.scheme == "http" and endpoint.hostname == "127.0.0.1" and endpoint.port
                and endpoint.path == "/api/v1" and not (endpoint.username or endpoint.query or endpoint.fragment),
                "expected the disposable loopback Gateway API address")
        state = {} if args.phase == "seed" else json.loads(args.state.read_text(encoding="utf-8"))
        with httpx.Client(trust_env=False, follow_redirects=False, timeout=10) as transport:
            if args.phase == "seed":
                require(not args.state.exists(), "refusing to overwrite existing cutover state")
                seed(transport, args.api_url, state, args.admin_email)
            else:
                previous, step = STEPS[args.phase]
                require(state["phase"] == previous, "invalid chat scenario phase order")
                apis = {role: Api(transport, args.api_url, account["token"]) for role, account in state["accounts"].items()}
                step(state, apis)
        state["phase"] = args.phase
        temporary = args.state.with_suffix(".tmp")
        temporary.write_text(json.dumps(state), encoding="utf-8")
        temporary.replace(args.state)
    except ScenarioError as error:
        print(f"phase={args.phase} result=failed reason={error}", file=sys.stderr)
        return 1
    except (httpx.HTTPError, ValueError, KeyError, OSError) as error:
        print(f"phase={args.phase} result=failed reason={type(error).__name__}", file=sys.stderr)
        return 1
    print(f"phase={args.phase} result=passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
