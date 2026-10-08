package com.example.demo.threads.service.mp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.mp.ThreadMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "feature.mp.read.threads", havingValue = "true")
public class ThreadReadServiceMp {
    private final ThreadMapper threadMapper;

    public ThreadReadServiceMp(ThreadMapper threadMapper) {
        this.threadMapper = threadMapper;
    }

    public Page<Thread> searchNewest(String q, Long sectionId, int page, int size) {
        int current = Math.max(page, 1);
        int limit = Math.min(Math.max(size, 1), 10);

        LambdaQueryWrapper<Thread> qw = new LambdaQueryWrapper<>();
        qw.eq(Thread::getStatus, "NORMAL");
        if (sectionId != null) {
            qw.eq(Thread::getSectionId, sectionId);
        }
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            qw.and(w -> w.like(Thread::getTitle, keyword).or().like(Thread::getContentMd, keyword));
        }
        // 与 JPA 的 searchNewest 保持一致：按 createdAt 倒序
        qw.orderByDesc(Thread::getCreatedAt);

        Page<Thread> pageParam = new Page<>(current, limit);
        return threadMapper.selectPage(pageParam, qw);
    }
}