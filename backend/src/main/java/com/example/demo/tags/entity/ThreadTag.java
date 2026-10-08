package com.example.demo.tags.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "thread_tags")
@IdClass(ThreadTagId.class)
public class ThreadTag {
    @Id
    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    public Long getThreadId() { return threadId; }
    public void setThreadId(Long threadId) { this.threadId = threadId; }
    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
}
