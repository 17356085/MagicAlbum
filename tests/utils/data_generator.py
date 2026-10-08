"""Generate unique data for isolated API and E2E scenarios."""

from __future__ import annotations

import random
import string
import time


def _timestamp_suffix() -> str:
    return str(int(time.time() * 1000))[-8:]


def _random_suffix(length: int = 8) -> str:
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=length))


def unique_username() -> str:
    return f"test_user_{_timestamp_suffix()}"


def unique_email() -> str:
    return f"test_{_timestamp_suffix()}@example.com"


def unique_phone() -> str:
    return f"138{random.randint(10000000, 99999999)}"


def unique_thread_title() -> str:
    return f"自动化测试帖子_{_timestamp_suffix()}"


def unique_thread_content() -> str:
    return f"""## 自动化测试内容

这是自动生成的测试内容，生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}

### 测试要点
- 确保内容唯一性
- 验证 Markdown 渲染
- 测试后自动清理
"""


def unique_comment() -> str:
    return f"自动化测试评论_{_timestamp_suffix()}"


def unique_comment_content() -> str:
    return unique_comment()


def unique_tag_name() -> str:
    return f"testtag{_random_suffix(4)}"
