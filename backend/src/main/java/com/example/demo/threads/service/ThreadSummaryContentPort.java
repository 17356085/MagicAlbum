package com.example.demo.threads.service;

import com.example.demo.threads.dto.ThreadSummarySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Content owner of summary fields. Model calls and task state never run inside this port. */
@Service
public class ThreadSummaryContentPort {
    private static final String SELECT = """
            SELECT id,author_id,section_id,content_version,status,content_md,summary_task_id,summary,summary_status
            FROM threads WHERE id=?
            """;
    private final JdbcTemplate jdbc;
    private final ThreadCacheInvalidator cache;

    public ThreadSummaryContentPort(JdbcTemplate jdbc, ObjectProvider<StringRedisTemplate> redis,
                                     ObjectProvider<ObjectMapper> mapper) {
        this.jdbc = jdbc;
        this.cache = new ThreadCacheInvalidator(redis.getIfAvailable(), mapper.getIfAvailable(ObjectMapper::new));
    }

    public ThreadSummarySnapshot read(Long threadId) { return select(threadId, false); }

    @Transactional(propagation = Propagation.MANDATORY)
    public ThreadSummarySnapshot lock(Long threadId) { return select(threadId, true); }

    private ThreadSummarySnapshot select(Long id, boolean lock) {
        if (id == null || id <= 0) return null;
        var rows = jdbc.query(SELECT + (lock ? " FOR UPDATE" : ""), (rs, n) -> new ThreadSummarySnapshot(
                rs.getLong("id"), rs.getLong("author_id"), rs.getLong("section_id"), rs.getLong("content_version"),
                rs.getString("status"), rs.getString("content_md"), rs.getString("summary_task_id"),
                rs.getString("summary"), rs.getString("summary_status")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean assign(Long id, long version, String taskId) {
        boolean accepted = jdbc.update("""
                UPDATE threads SET summary_task_id=?,summary=NULL,summary_status='PENDING',
                  summary_owner=NULL,summary_lease_until=NULL,summary_attempts=0
                WHERE id=? AND status='NORMAL' AND content_version=?
                """, taskId, id, version) == 1;
        if (accepted) evictAfterCommit(id);
        return accepted;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean update(Long id, long version, String taskId, String status, String summary) {
        boolean accepted = jdbc.update("""
                UPDATE threads SET summary=?,summary_status=?
                WHERE id=? AND status='NORMAL' AND content_version=? AND summary_task_id=?
                """, summary, status, id, version, taskId) == 1;
        if (accepted) evictAfterCommit(id);
        return accepted;
    }

    private void evictAfterCommit(Long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evictThreadMetadata(id); }
        });
    }
}
