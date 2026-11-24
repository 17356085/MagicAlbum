package com.example.demo.threads.service;

import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Content-owned reads and locks used when accepting or resolving forum reports. */
@Service
public class ContentReportTargets {
    private static final String NORMAL = "NORMAL";

    private final ThreadRepository threads;
    private final PostRepository posts;

    public ContentReportTargets(ThreadRepository threads, PostRepository posts) {
        this.threads = threads;
        this.posts = posts;
    }

    public boolean isThreadReportable(Long threadId) {
        return threads.findById(threadId).filter(thread -> NORMAL.equals(thread.getStatus())).isPresent();
    }

    public boolean isPostReportable(Long postId) {
        return posts.findById(postId).filter(post -> NORMAL.equals(post.getStatus()))
                .map(post -> isThreadReportable(post.getThreadId())).orElse(false);
    }

    public Optional<Long> findPostThreadId(Long postId) {
        return posts.findById(postId).map(Post::getThreadId);
    }

    /** Holds the target thread's row lock through the caller's moderation transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> lockTargetSection(Long targetId, boolean isPost) {
        Long threadId = isPost ? posts.findThreadIdByPostId(targetId).orElse(null) : targetId;
        return threadId == null ? Optional.empty()
                : threads.findByIdForInteraction(threadId).map(Thread::getSectionId);
    }
}
