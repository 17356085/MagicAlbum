"""Shared HTTP fixtures for the isolated split-chat runtime checks."""

import secrets
import uuid

import httpx


class ScenarioError(RuntimeError):
    pass


def require(condition, description):
    if not condition:
        raise ScenarioError(description)


class Api:
    def __init__(self, transport, origin, token=None):
        self.transport = transport
        self.origin = origin
        self.token = token
        self.device = str(uuid.uuid4())

    def request(self, method, path, expected=200, *, body=None, params=None, files=None):
        headers = {"X-Device-Id": self.device}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            response = self.transport.request(
                method, self.origin + path, headers=headers, json=body, params=params, files=files
            )
        except httpx.HTTPError as error:
            raise ScenarioError(f"{method} {path} failed ({type(error).__name__})") from None
        require(response.status_code == expected, f"{method} {path} returned HTTP {response.status_code}, expected {expected}")
        return response.json() if expected < 400 and response.content else None


def register(transport, origin, marker, role, email=None):
    api = Api(transport, origin)
    address = email or f"cc{role}{marker}@example.test"
    password = f"Cutover!{secrets.token_hex(16)}aA1"
    identity = api.request("POST", "/users/register", 201, body={
        "email": address, "username": f"cc{role}{marker}",
        "phone": f"13{secrets.randbelow(10**9):09d}", "password": password,
    })
    session = api.request("POST", "/auth/login", body={"email": address, "password": password})
    require(identity["id"] == session["user"]["id"], "login returned a different account")
    api.token = session["accessToken"]
    return api, {"id": identity["id"], "token": api.token}


def send_direct(api, peer, content, operation, status):
    return api.request("POST", f"/chat/direct/{peer}/messages", status,
                       body={"clientMessageId": operation, "content": content})
