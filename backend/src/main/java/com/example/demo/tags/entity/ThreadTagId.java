package com.example.demo.tags.entity;

import java.io.Serializable;
import java.util.Objects;

public class ThreadTagId implements Serializable {
    private Long threadId;
    private Long tagId;

    public ThreadTagId() {
    }

    public ThreadTagId(Long threadId, Long tagId) {
        this.threadId = threadId;
        this.tagId = tagId;
    }

    public Long getThreadId() { return threadId; }
    public void setThreadId(Long threadId) { this.threadId = threadId; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThreadTagId that)) return false;
        return Objects.equals(threadId, that.threadId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(threadId, tagId);
    }
}
