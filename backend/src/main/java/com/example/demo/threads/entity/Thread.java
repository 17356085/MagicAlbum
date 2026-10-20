package com.example.demo.threads.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "threads", indexes = @Index(name = "idx_threads_summary_recovery",
        columnList = "status, summary_status, id, created_at"))
@TableName("threads")
public class Thread {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(name = "content_md", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String contentMd;

    @Column(nullable = false)
    private Boolean spoiler = false;

    @Column(nullable = false, length = 32)
    private String status = "NORMAL";

    @Column(name = "content_version", nullable = false)
    @ColumnDefault("0")
    private long contentVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "summary_status", length = 32)
    private String summaryStatus = "IDLE";

    @Column(name = "summary_task_id", length = 36)
    private String summaryTaskId;

    @Column(name = "summary_owner", length = 36)
    private String summaryOwner;

    @Column(name = "summary_lease_until")
    private Instant summaryLeaseUntil;

    @Column(name = "summary_attempts", nullable = false)
    private int summaryAttempts;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null || status.isBlank()) status = "NORMAL";
        if (spoiler == null) spoiler = false;
        if (summaryStatus == null) summaryStatus = "IDLE";
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSectionId() { return sectionId; }
    public void setSectionId(Long sectionId) { this.sectionId = sectionId; }
    public Long getAuthorId() { return authorId; }
    public void setAuthorId(Long authorId) { this.authorId = authorId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public Boolean getSpoiler() { return spoiler; }
    public void setSpoiler(Boolean spoiler) { this.spoiler = spoiler; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getContentVersion() { return contentVersion; }
    public void setContentVersion(long contentVersion) { this.contentVersion = contentVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
    public String getSummaryTaskId() { return summaryTaskId; }
    public void setSummaryTaskId(String id) { this.summaryTaskId = id; }
    public String getSummaryOwner() { return summaryOwner; }
    public void setSummaryOwner(String owner) { this.summaryOwner = owner; }
    public Instant getSummaryLeaseUntil() { return summaryLeaseUntil; }
    public void setSummaryLeaseUntil(Instant until) { this.summaryLeaseUntil = until; }
    public int getSummaryAttempts() { return summaryAttempts; }
    public void setSummaryAttempts(int attempts) { this.summaryAttempts = attempts; }
}
