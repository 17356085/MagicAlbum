"""AI summary API tests."""

import time
from uuid import uuid4

import pytest

from tests.api.api_client import ApiClient


def _payload(body: dict) -> dict:
    nested = body.get("data")
    return nested if isinstance(nested, dict) else body


class TestAISummary:
    """AI 摘要接口测试。"""

    @pytest.mark.p2
    @pytest.mark.api
    def test_get_summary_for_nonexistent_thread(
        self, auth_api_client: ApiClient
    ) -> None:
        response = auth_api_client.get_summary(99999999)
        assert response.status_code == 404

    @pytest.mark.p2
    @pytest.mark.api
    def test_trigger_summary(self, auth_api_client: ApiClient) -> None:
        sections = auth_api_client.list_sections(page=1, size=1)
        assert sections.status_code == 200
        section_items = _payload(sections.json()).get("items", [])
        assert section_items, "摘要测试需要一个可写分区"

        title = f"AI summary regression {uuid4().hex[:10]}"
        created = auth_api_client.create_thread(
            int(section_items[0]["id"]),
            title,
            "A disposable thread created by the AI summary regression test.",
        )
        assert created.status_code == 201
        thread_id = int(_payload(created.json())["id"])
        try:
            response = auth_api_client.trigger_summary(
                thread_id,
                force=True,
                idempotency_key=str(uuid4()),
            )
            assert response.status_code == 200
            started = _payload(response.json())
            task_id = started.get("taskId")
            assert isinstance(task_id, str) and task_id

            deadline = time.monotonic() + 30
            final: dict = {}
            while time.monotonic() < deadline:
                task_response = auth_api_client.get_summary_task(task_id)
                assert task_response.status_code == 200
                final = _payload(task_response.json())
                if final.get("status") in {"COMPLETED", "FAILED", "CANCELLED", "SUPERSEDED"}:
                    break
                time.sleep(0.25)

            assert final.get("status") == "COMPLETED"
            assert final.get("summary")
            assert final.get("provider") == "mock", "full regression must prove server-side Mock AI"

            readback = auth_api_client.get_summary(thread_id)
            assert readback.status_code == 200
            readback_body = _payload(readback.json())
            assert readback_body.get("status") == "COMPLETED"
            assert readback_body.get("summary") == final.get("summary")
            assert readback_body.get("provider") == "mock"
        finally:
            deleted = auth_api_client.delete_thread(thread_id)
            assert deleted.status_code in (204, 404)
