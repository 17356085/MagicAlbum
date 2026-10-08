"""
API 测试辅助模块 — 封装所有后端 API 调用。

每个函数返回 httpx.Response 以便测试用例自行断言。
"""

from typing import Any

import httpx


class ApiClient:
    """REST API 客户端"""

    def __init__(self, base_url: str = "http://localhost:9000/api/v1"):
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None
        self._client = httpx.Client(timeout=30)

    @property
    def headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    # ── Auth ─────────────────────────────────────────────────

    def login_phone_password(
        self, phone: str, password: str, **kwargs
    ) -> httpx.Response:
        payload = {"phone": phone, "password": password, **kwargs}
        return self._client.post(
            f"{self.base_url}/auth/login", json=payload, headers=self.headers
        )

    def login_email_password(
        self, email: str, password: str, **kwargs
    ) -> httpx.Response:
        payload = {"email": email, "password": password, **kwargs}
        return self._client.post(
            f"{self.base_url}/auth/login", json=payload, headers=self.headers
        )

    def start_email_code(
        self, channel: str, address: str, **kwargs
    ) -> httpx.Response:
        payload = {"channel": channel, "address": address, **kwargs}
        return self._client.post(
            f"{self.base_url}/auth/code/start", json=payload, headers=self.headers
        )

    def finish_email_code(
        self, channel: str, address: str, code: str, session: str
    ) -> httpx.Response:
        payload = {
            "channel": channel,
            "address": address,
            "code": code,
            "session": session,
        }
        return self._client.post(
            f"{self.base_url}/auth/code/finish", json=payload, headers=self.headers
        )

    def register(
        self, username: str, email: str = "", phone: str = "", password: str = ""
    ) -> httpx.Response:
        payload = {
            "username": username,
            "email": email,
            "phone": phone,
            "password": password,
        }
        return self._client.post(
            f"{self.base_url}/users/register", json=payload, headers=self.headers
        )

    def check_username_available(self, username: str) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/availability",
            params={"username": username},
            headers=self.headers,
        )

    # ── Threads ──────────────────────────────────────────────

    def create_thread(
        self, section_id: int, title: str, content: str, tags: list[str] | None = None
    ) -> httpx.Response:
        payload: dict[str, Any] = {
            "sectionId": section_id,
            "title": title,
            "content": content,
        }
        if tags:
            payload["tags"] = tags
        return self._client.post(
            f"{self.base_url}/threads", json=payload, headers=self.headers
        )

    def get_thread(self, thread_id: int) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/threads/{thread_id}", headers=self.headers
        )

    def get_thread_bookmark(self, thread_id: int) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/threads/{thread_id}/bookmark", headers=self.headers
        )

    def bookmark_thread(self, thread_id: int) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/threads/{thread_id}/bookmark", headers=self.headers
        )

    def unbookmark_thread(self, thread_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/threads/{thread_id}/bookmark", headers=self.headers
        )

    def list_bookmarks(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/me/bookmarks", params=params, headers=self.headers
        )

    def create_report(
        self, target_type: str, target_id: int, reason: str, detail: str = ""
    ) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/reports",
            json={
                "targetType": target_type,
                "targetId": target_id,
                "reason": reason,
                "detail": detail,
            },
            headers=self.headers,
        )

    def get_admin_status(self) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/admin/me", headers=self.headers
        )

    def update_thread(
        self, thread_id: int, payload: dict
    ) -> httpx.Response:
        return self._client.patch(
            f"{self.base_url}/threads/{thread_id}",
            json=payload,
            headers=self.headers,
        )

    def delete_thread(self, thread_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/threads/{thread_id}", headers=self.headers
        )

    def list_threads(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/threads", params=params, headers=self.headers
        )

    def list_thread_ranking(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/threads/ranking", params=params, headers=self.headers
        )

    def like_thread(self, thread_id: int) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/threads/{thread_id}/like", headers=self.headers
        )

    def unlike_thread(self, thread_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/threads/{thread_id}/like", headers=self.headers
        )

    # ── Posts ────────────────────────────────────────────────

    def create_post(
        self, thread_id: int, content_md: str, reply_to_post_id: int | None = None
    ) -> httpx.Response:
        payload: dict[str, Any] = {"contentMd": content_md}
        if reply_to_post_id is not None:
            payload["replyToPostId"] = reply_to_post_id
        return self._client.post(
            f"{self.base_url}/threads/{thread_id}/posts",
            json=payload,
            headers=self.headers,
        )

    def list_posts(self, thread_id: int, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/threads/{thread_id}/posts",
            params=params,
            headers=self.headers,
        )

    def update_post(self, post_id: int, payload: dict) -> httpx.Response:
        return self._client.patch(
            f"{self.base_url}/posts/{post_id}", json=payload, headers=self.headers
        )

    def delete_post(self, post_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/posts/{post_id}", headers=self.headers
        )

    def like_post(self, post_id: int) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/posts/{post_id}/like", headers=self.headers
        )

    def unlike_post(self, post_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/posts/{post_id}/like", headers=self.headers
        )

    # ── Users ────────────────────────────────────────────────

    def get_current_user(self) -> httpx.Response:
        return self._client.get(f"{self.base_url}/users/me", headers=self.headers)

    def get_user_profile(self, user_id: int) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/{user_id}/profile", headers=self.headers
        )

    def list_users(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users", params=params, headers=self.headers
        )

    def follow_user(self, user_id: int) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/users/{user_id}/follow", headers=self.headers
        )

    def unfollow_user(self, user_id: int) -> httpx.Response:
        return self._client.delete(
            f"{self.base_url}/users/{user_id}/follow", headers=self.headers
        )

    def get_user_following(self, user_id: int, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/{user_id}/following",
            params=params,
            headers=self.headers,
        )

    def get_user_followers(self, user_id: int, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/{user_id}/followers",
            params=params,
            headers=self.headers,
        )

    # ── Sections ─────────────────────────────────────────────

    def list_sections(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/sections", params=params, headers=self.headers
        )

    # ── Tags ─────────────────────────────────────────────────

    def list_popular_tags(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/tags/popular", params=params, headers=self.headers
        )

    def list_tags(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/tags", params=params, headers=self.headers
        )

    # ── Settings ─────────────────────────────────────────────

    def get_my_profile(self) -> httpx.Response:
        return self._client.get(f"{self.base_url}/users/me", headers=self.headers)

    def update_my_profile(self, payload: dict) -> httpx.Response:
        return self._client.patch(
            f"{self.base_url}/users/me", json=payload, headers=self.headers
        )

    def get_my_basic_info(self) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/users/me/basic", headers=self.headers
        )

    def update_my_basic_info(self, payload: dict) -> httpx.Response:
        return self._client.patch(
            f"{self.base_url}/users/me/basic", json=payload, headers=self.headers
        )

    def update_my_password(
        self, current_password: str, new_password: str
    ) -> httpx.Response:
        payload = {"currentPassword": current_password, "newPassword": new_password}
        return self._client.post(
            f"{self.base_url}/users/me/password", json=payload, headers=self.headers
        )

    # ── Notifications ────────────────────────────────────────

    def list_notifications(self, **params) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/notifications", params=params, headers=self.headers
        )

    def mark_notification_read(self, notification_id: int) -> httpx.Response:
        return self._client.patch(
            f"{self.base_url}/notifications/{notification_id}/read",
            headers=self.headers,
        )

    # ── AI ───────────────────────────────────────────────────

    def get_summary(self, thread_id: int) -> httpx.Response:
        return self._client.get(
            f"{self.base_url}/ai/summary/{thread_id}", headers=self.headers
        )

    def trigger_summary(self, thread_id: int, force: bool = False) -> httpx.Response:
        return self._client.post(
            f"{self.base_url}/ai/summary/{thread_id}?force={force}",
            headers=self.headers,
        )

    # ── Helper ───────────────────────────────────────────────

    def set_token(self, token: str) -> None:
        self.token = token

    def close(self) -> None:
        self._client.close()
