#!/usr/bin/env python3
"""Prepare and verify the disposable account used by the full Python gate.

The helper deliberately uses only the public gateway API.  It creates one
throw-away account, reads the persisted user id back through ``/users/me``,
and can seed enough real threads for the legacy browser tests to exercise
pagination and filters.  Credentials are written only to a caller-owned
0600 file and are never included in evidence JSON or error messages.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import string
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from uuid import uuid4


class BootstrapError(RuntimeError):
    """A safe, non-secret preparation failure."""


def _json_body(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(raw.decode("utf-8")) if raw else {}
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BootstrapError(f"{label} returned invalid JSON ({type(exc).__name__})") from exc
    if not isinstance(value, dict):
        raise BootstrapError(f"{label} returned an unexpected JSON shape")
    return value


def _payload(body: dict[str, Any]) -> dict[str, Any]:
    nested = body.get("data")
    return nested if isinstance(nested, dict) else body


def _id_from(value: Any, label: str) -> int:
    candidates: list[Any] = []
    if isinstance(value, dict):
        candidates.extend((value.get("id"), value.get("userId"), value.get("user_id")))
        for key in ("user", "data"):
            nested = value.get(key)
            if isinstance(nested, dict):
                candidates.extend((nested.get("id"), nested.get("userId"), nested.get("user_id")))
    for candidate in candidates:
        try:
            parsed = int(candidate)
        except (TypeError, ValueError):
            continue
        if parsed > 0:
            return parsed
    raise BootstrapError(f"{label} did not return a positive numeric id")


def _request(
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    device_id: str,
    body: dict[str, Any] | None = None,
    params: dict[str, Any] | None = None,
) -> tuple[int, dict[str, Any], str | None]:
    url = f"{base_url.rstrip('/')}{path}"
    if params:
        url = f"{url}?{urlencode(params)}"
    headers = {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Device-Id": device_id,
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    request = Request(url, data=payload, headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            status = int(response.status)
            raw = response.read()
            retry_after = response.headers.get("Retry-After")
    except HTTPError as exc:
        raw = exc.read()
        status = int(exc.code)
        retry_after = exc.headers.get("Retry-After") if exc.headers else None
    except URLError as exc:
        raise BootstrapError(f"{method} {path} could not reach the isolated gateway ({type(exc.reason).__name__})") from exc
    except TimeoutError as exc:
        raise BootstrapError(f"{method} {path} timed out") from exc
    return status, _json_body(raw, f"{method} {path}"), retry_after


def _require(status: int, expected: tuple[int, ...], label: str, retry_after: str | None = None) -> None:
    if status in expected:
        return
    suffix = f"; Retry-After={retry_after}" if status == 429 and retry_after else ""
    raise BootstrapError(f"{label} returned HTTP {status}{suffix}")


def _identity() -> dict[str, str]:
    suffix = uuid4().hex[:12]
    password = "Ci1!" + "".join(secrets.choice(string.ascii_letters + string.digits) for _ in range(28))
    return {
        "username": f"fullci{suffix}",
        "email": f"fullci-{suffix}@example.test",
        "phone": f"13{secrets.randbelow(10**9):09d}",
        "password": password,
    }


def _write_env(path: Path, identity: dict[str, str], user_id: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "\n".join(
        (
            f"TEST_EMAIL={identity['email']}",
            f"TEST_PHONE={identity['phone']}",
            f"TEST_PASSWORD={identity['password']}",
            f"FULL_TEST_USER_ID={user_id}",
            f"APP_ADMIN_EMAIL={identity['email']}",
            f"APP_ADMIN_USER_ID={user_id}",
        )
    ) + "\n"
    try:
        path.write_text(text, encoding="utf-8", newline="\n")
        path.chmod(0o600)
    except OSError as exc:
        # Never leave generated credentials behind with a weaker mode.  The
        # failure text intentionally omits the path and all credential values.
        try:
            path.unlink()
        except OSError:
            pass
        raise BootstrapError("could not create private full-regression credentials file with mode 0600") from exc


def _read_env(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise BootstrapError(f"cannot read private full-regression credentials file ({type(exc).__name__})") from exc
    values: dict[str, str] = {}
    for line in lines:
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    required = ("TEST_EMAIL", "TEST_PHONE", "TEST_PASSWORD", "FULL_TEST_USER_ID", "APP_ADMIN_EMAIL", "APP_ADMIN_USER_ID")
    if any(not values.get(key) for key in required):
        raise BootstrapError("private full-regression credentials file is incomplete")
    return values


def _demo_base_url(base_url: str) -> None:
    """Require the demo writer to target only the local HTTP gateway API."""

    if base_url != base_url.strip():
        raise BootstrapError("demo preparation requires an exact loopback HTTP /api/v1 base URL")
    try:
        parsed = urlsplit(base_url)
        hostname = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise BootstrapError("demo preparation requires an exact loopback HTTP /api/v1 base URL") from exc
    if (
        parsed.scheme != "http"
        or hostname != "127.0.0.1"
        or parsed.path != "/api/v1"
        or parsed.query
        or parsed.fragment
        or parsed.username is not None
        or parsed.password is not None
        or (port is not None and not 1 <= port <= 65535)
    ):
        raise BootstrapError("demo preparation requires an exact loopback HTTP /api/v1 base URL")


def _demo_output_preflight(credentials_path: Path, demo_accounts_path: Path, evidence_path: Path) -> None:
    """Check demo outputs before any request and keep them beside the env file."""

    try:
        credentials = credentials_path.resolve(strict=True)
        caller_directory = credentials.parent
    except OSError as exc:
        raise BootstrapError("demo preparation could not resolve the existing credentials file") from exc
    if not credentials.is_file():
        raise BootstrapError("demo preparation requires an existing credentials file")

    paths = (demo_accounts_path, evidence_path)
    resolved_paths: list[Path] = []
    for path in paths:
        try:
            if path.exists() or path.is_symlink():
                raise BootstrapError("demo preparation requires new output paths")
            parent = path.parent.resolve(strict=True)
            resolved = path.resolve(strict=False)
        except BootstrapError:
            raise
        except OSError as exc:
            raise BootstrapError("demo preparation could not resolve its output directory") from exc
        if parent != caller_directory or resolved == credentials:
            raise BootstrapError("demo outputs must be new files under the credentials directory")
        resolved_paths.append(resolved)
    if resolved_paths[0] == resolved_paths[1]:
        raise BootstrapError("demo account and evidence outputs must be different files")


def _write_demo_json(path: Path, value: dict[str, Any]) -> None:
    """Create one new UTF-8 file with restricted permissions."""
    contents = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    created = False
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(contents)
    except OSError as exc:
        if created:
            path.unlink(missing_ok=True)
        raise BootstrapError("demo preparation could not create its output") from exc


def prepare_demo(base_url: str, credentials_path: Path, demo_accounts_path: Path, evidence_path: Path) -> None:
    _demo_base_url(base_url)
    _demo_output_preflight(credentials_path, demo_accounts_path, evidence_path)
    values = _read_env(credentials_path)
    expected_admin = _id_from({"id": values["FULL_TEST_USER_ID"]}, "demo configured ID")
    if expected_admin != _id_from({"id": values["APP_ADMIN_USER_ID"]}, "demo configured admin"):
        raise BootstrapError("demo trusted user IDs did not match")

    def request(method, path, label, session, expected=200, **options):
        status, body, retry_after = _request(
            base_url, method, path, token=session.get("token"), device_id=session["device"], **options,
        )
        _require(status, (expected,), f"demo {label}", retry_after)
        return _payload(body)

    def prepare_actor(identity, label, *, register):
        active = {"device": str(uuid4())}
        if register:
            response = request("POST", "/users/register", f"{label} registration", active, 201, body=identity)
            registered_id = _id_from(response, f"demo {label} registration")
        else:
            registered_id = expected_admin
        login = request("POST", "/auth/login", f"{label} login", active,
                        body={"email": identity["email"], "password": identity["password"]})
        token = login.get("accessToken")
        if not isinstance(token, str) or not token:
            raise BootstrapError(f"demo {label} login did not return an access token")
        active["token"] = token
        user = request("GET", "/users/me", f"{label} identity", active)
        if _id_from(user, f"demo {label} identity") != registered_id:
            raise BootstrapError(f"demo {label} current-user identity mismatch")
        profile = request("GET", "/governance/me" if register else "/admin/me", f"{label} role", active)
        if profile.get("admin") is not (not register):
            raise BootstrapError(f"demo {label} role mismatch")
        if register and (profile.get("moderatedSectionIds") != [] or profile.get("postingRestricted") is not False):
            raise BootstrapError(f"demo {label} is not an unrestricted ordinary user")
        return active, {
            **identity, "username": user.get("username") or identity.get("username"),
            "id": registered_id, "role": "ordinary" if register else "admin",
        }

    administrator_identity = {
        "email": values["TEST_EMAIL"], "phone": values["TEST_PHONE"], "password": values["TEST_PASSWORD"],
    }
    admin, admin_account = prepare_actor(administrator_identity, "administrator", register=False)
    sections = request("GET", "/sections", "sections", admin, params={"page": 1, "size": 100}).get("items")
    if not isinstance(sections, list) or not sections:
        raise BootstrapError("demo stack did not expose a writable section")
    section_id = _id_from(sections[0], "demo section")

    suffix = uuid4().hex[:10]
    accounts = {"admin": admin_account}
    sessions = {}
    for key in ("A", "B"):
        identity = _identity()
        identity.update(username=f"demo{key}{suffix}", email=f"demo-{key.lower()}-{suffix}@example.test")
        sessions[key], accounts[f"user{key}"] = prepare_actor(identity, f"user {key}", register=True)
    author_id, reader_id = accounts["userA"]["id"], accounts["userB"]["id"]
    if len({expected_admin, author_id, reader_id}) != 3:
        raise BootstrapError("demo actors must be distinct")
    author, reader = sessions["A"], sessions["B"]

    thread_ids: list[int] = []
    for title in ("同频演示 · 欢迎交流", "同频演示 · 每周分享"):
        data = request("POST", "/threads", "topic creation", author, 201,
                       body={"sectionId": section_id, "title": title, "content": "欢迎在评论区参与讨论。", "tags": ["demo"]})
        thread_id = _id_from(data, "demo topic")
        topic = request("GET", f"/threads/{thread_id}", "topic readback", reader)
        if topic.get("authorId") != author_id or topic.get("title") != title:
            raise BootstrapError("demo topic readback did not match its author and title")
        thread_ids.append(thread_id)

    posts_path = f"/threads/{thread_ids[0]}/posts"
    comment_text = "读者在演示主题下留下了一条评论。"
    comment = request("POST", posts_path, "comment creation", reader, 201, body={"contentMd": comment_text})
    comment_id = _id_from(comment, "demo comment")
    readback = request("GET", posts_path, "comment readback", author, params={"page": 1, "size": 20}).get("items")
    if not isinstance(readback, list) or not any(
        row.get("id") == comment_id and row.get("authorId") == reader_id and row.get("content") == comment_text
        for row in readback if isinstance(row, dict)
    ):
        raise BootstrapError("demo comment readback did not match its author and content")

    request("POST", f"/users/{author_id}/follow", "follow creation", reader)
    follow = request("GET", f"/users/{author_id}/follow-status", "follow readback", reader)
    if follow.get("followedByMe") is not True:
        raise BootstrapError("demo follow readback did not return followedByMe=true")
    feed = request("GET", "/threads/following", "following feed", reader,
                   params={"pagination": "cursor", "page": 1, "size": 20}).get("items")
    if not isinstance(feed, list):
        raise BootstrapError("demo following feed did not return an items list")
    feed_ids = {row.get("id") for row in feed if isinstance(row, dict)}
    if not set(thread_ids).issubset(feed_ids):
        raise BootstrapError("demo following feed did not contain both topics")

    cases = (
        "trusted-admin-readback", "writable-section-readback", "ordinary-user-a-readback",
        "ordinary-user-b-readback", "sample-threads-readback", "comment-readback", "follower-feed-readback",
    )
    _write_demo_json(evidence_path, {
        "schemaVersion": 1, "mode": "prepare-demo",
        "cases": [{"name": name, "result": "passed"} for name in cases],
        "ids": {"adminUserId": expected_admin, "sectionId": section_id, "userAId": author_id,
                "userBId": reader_id, "threadIds": thread_ids, "commentId": comment_id},
        "counts": {"ordinaryAccounts": 2, "threads": len(thread_ids), "comments": 1,
                   "followerFeedItems": len(feed), "followerFeedMatches": len(thread_ids)},
    })
    try:
        _write_demo_json(demo_accounts_path, {"schemaVersion": 1, "mode": "prepare-demo", "accounts": accounts})
    except BootstrapError:
        evidence_path.unlink(missing_ok=True)
        raise


def bootstrap(base_url: str, env_path: Path) -> None:
    identity = _identity()
    device_id = str(uuid4())
    status, body, retry_after = _request(
        base_url,
        "POST",
        "/users/register",
        device_id=device_id,
        body=identity,
    )
    _require(status, (201,), "full-regression account registration", retry_after)
    registered_id = _id_from(_payload(body), "full-regression account registration")

    status, body, retry_after = _request(
        base_url,
        "POST",
        "/auth/login",
        device_id=device_id,
        body={"email": identity["email"], "password": identity["password"]},
    )
    _require(status, (200,), "full-regression account login", retry_after)
    data = _payload(body)
    token = data.get("accessToken") or data.get("access_token") or data.get("token")
    if not isinstance(token, str) or not token:
        raise BootstrapError("full-regression account login did not return an access token")

    status, current, retry_after = _request(
        base_url,
        "GET",
        "/users/me",
        token=token,
        device_id=device_id,
    )
    _require(status, (200,), "full-regression current-user readback", retry_after)
    readback_id = _id_from(_payload(current), "full-regression current-user readback")
    if readback_id != registered_id:
        raise BootstrapError("full-regression current-user id did not match registration")
    _write_env(env_path, identity, readback_id)


def verify_admin_and_seed(base_url: str, env_path: Path, evidence_path: Path, count: int) -> None:
    values = _read_env(env_path)
    device_id = str(uuid4())
    expected_user_id = int(values["FULL_TEST_USER_ID"])
    admin_user_id = int(values["APP_ADMIN_USER_ID"])
    if expected_user_id != admin_user_id:
        raise BootstrapError("full-regression trusted user ids did not match")
    # Credentials are read from TEST_* names but kept in local variables only.
    email = values["TEST_EMAIL"]
    password = values["TEST_PASSWORD"]
    status, body, retry_after = _request(
        base_url,
        "POST",
        "/auth/login",
        device_id=device_id,
        body={"email": email, "password": password},
    )
    _require(status, (200,), "full-regression admin login", retry_after)
    data = _payload(body)
    token = data.get("accessToken") or data.get("access_token") or data.get("token")
    if not isinstance(token, str) or not token:
        raise BootstrapError("full-regression admin login did not return an access token")

    status, current_body, retry_after = _request(base_url, "GET", "/users/me", token=token, device_id=device_id)
    _require(status, (200,), "full-regression current-user post-restart readback", retry_after)
    current_user_id = _id_from(_payload(current_body), "full-regression current-user post-restart readback")
    if current_user_id != expected_user_id:
        raise BootstrapError("full-regression current-user post-restart id did not match registration")

    status, admin_body, retry_after = _request(base_url, "GET", "/admin/me", token=token, device_id=device_id)
    _require(status, (200,), "full-regression admin readback", retry_after)
    admin = _payload(admin_body).get("admin")
    if admin is not True:
        raise BootstrapError("full-regression admin readback did not return admin=true")

    status, sections_body, retry_after = _request(
        base_url,
        "GET",
        "/sections",
        token=token,
        device_id=device_id,
        params={"page": 1, "size": 100},
    )
    _require(status, (200,), "full-regression section readback", retry_after)
    sections = _payload(sections_body).get("items")
    if not isinstance(sections, list) or not sections:
        raise BootstrapError("full-regression stack did not expose a writable section")
    section = sections[0]
    section_id = _id_from(section, "full-regression section readback")

    thread_ids: list[int] = []
    thread_readback_ids: list[int] = []
    for index in range(count):
        title = f"Full Python regression {index + 1} {uuid4().hex[:8]}"
        status, thread_body, retry_after = _request(
            base_url,
            "POST",
            "/threads",
            token=token,
            device_id=device_id,
            body={
                "sectionId": section_id,
                "title": title,
                "content": f"Disposable full Python regression fixture {index + 1}.",
                "tags": ["full-regression"],
            },
        )
        _require(status, (201,), f"full-regression thread seed {index + 1}", retry_after)
        thread_id = _id_from(_payload(thread_body), f"full-regression thread seed {index + 1}")
        thread_ids.append(thread_id)

        status, readback_body, retry_after = _request(
            base_url,
            "GET",
            f"/threads/{thread_id}",
            token=token,
            device_id=device_id,
        )
        _require(status, (200,), f"full-regression thread readback {index + 1}", retry_after)
        readback = _payload(readback_body)
        readback_id = _id_from(readback, f"full-regression thread readback {index + 1}")
        if readback_id != thread_id:
            raise BootstrapError(f"full-regression thread readback {index + 1} returned a different id")

        owner_value: Any = None
        for key in ("authorId", "author_id", "ownerId", "owner_id", "userId", "user_id"):
            if readback.get(key) is not None:
                owner_value = readback[key]
                break
        if owner_value is None:
            for key in ("author", "owner", "user"):
                nested = readback.get(key)
                if isinstance(nested, dict):
                    owner_value = nested.get("id") or nested.get("userId") or nested.get("user_id")
                    if owner_value is not None:
                        break
        try:
            owner_id = int(owner_value)
        except (TypeError, ValueError):
            owner_id = 0
        if owner_id != admin_user_id:
            raise BootstrapError(f"full-regression thread readback {index + 1} returned a different owner")
        if readback.get("title") != title:
            raise BootstrapError(f"full-regression thread readback {index + 1} returned a different title")
        thread_readback_ids.append(readback_id)

    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    evidence_path.write_text(
        json.dumps(
            {
                "adminUserId": admin_user_id,
                "sectionId": section_id,
                "threadIds": thread_ids,
                "threadCount": len(thread_ids),
                "threadReadbackIds": thread_readback_ids,
                "threadReadbackCount": len(thread_readback_ids),
                "tag": "full-regression",
                "adminReadback": True,
                "createdAtEpoch": int(time.time()),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--credentials-file", required=True, type=Path)
    parser.add_argument("--bootstrap", action="store_true")
    parser.add_argument("--verify-admin-and-seed", action="store_true")
    parser.add_argument("--prepare-demo", action="store_true")
    parser.add_argument("--demo-accounts-file", type=Path)
    parser.add_argument("--evidence-file", type=Path)
    parser.add_argument("--seed-count", type=int, default=11)
    args = parser.parse_args()

    modes = (args.bootstrap, args.verify_admin_and_seed, args.prepare_demo)
    if sum(modes) != 1:
        parser.error("choose exactly one of --bootstrap, --verify-admin-and-seed, or --prepare-demo")
    if args.seed_count < 11 or args.seed_count > 50:
        parser.error("--seed-count must be between 11 and 50 for pagination coverage")
    try:
        if args.bootstrap:
            bootstrap(args.base_url, args.credentials_file)
        elif args.prepare_demo:
            if args.demo_accounts_file is None:
                parser.error("--demo-accounts-file is required with --prepare-demo")
            if args.evidence_file is None:
                parser.error("--evidence-file is required with --prepare-demo")
            prepare_demo(
                args.base_url,
                args.credentials_file,
                args.demo_accounts_file,
                args.evidence_file,
            )
        else:
            if args.evidence_file is None:
                parser.error("--evidence-file is required with --verify-admin-and-seed")
            verify_admin_and_seed(args.base_url, args.credentials_file, args.evidence_file, args.seed_count)
    except BootstrapError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
