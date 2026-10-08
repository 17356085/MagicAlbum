package com.example.demo.bookmarks.service;

import com.example.demo.bookmarks.dto.BookmarkItem;
import com.example.demo.bookmarks.dto.BookmarkPageResponse;
import com.example.demo.bookmarks.dto.BookmarkStatusResponse;
import com.example.demo.bookmarks.entity.ThreadBookmark;
import com.example.demo.bookmarks.repo.ThreadBookmarkRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ThreadBookmarkService {

    private static final String NORMAL_STATUS = "NORMAL";

    private final ThreadBookmarkRepository bookmarkRepository;
    private final ThreadRepository threadRepository;

    public ThreadBookmarkService(ThreadBookmarkRepository bookmarkRepository, ThreadRepository threadRepository) {
        this.bookmarkRepository = bookmarkRepository;
        this.threadRepository = threadRepository;
    }

    public BookmarkStatusResponse getStatus(Long userId, Long threadId) {
        requireNormalThread(threadId);
        return new BookmarkStatusResponse(bookmarkRepository.existsByUserIdAndThreadId(userId, threadId));
    }

    public BookmarkStatusResponse add(Long userId, Long threadId) {
        requireNormalThread(threadId);
        if (!bookmarkRepository.existsByUserIdAndThreadId(userId, threadId)) {
            ThreadBookmark bookmark = new ThreadBookmark();
            bookmark.setUserId(userId);
            bookmark.setThreadId(threadId);
            try {
                bookmarkRepository.save(bookmark);
            } catch (DataIntegrityViolationException exception) {
                // The composite unique key makes a concurrent duplicate POST idempotent too.
                // Do not hide unrelated integrity failures such as an unknown user id.
                if (!bookmarkRepository.existsByUserIdAndThreadId(userId, threadId)) {
                    throw exception;
                }
            }
        }
        return new BookmarkStatusResponse(true);
    }

    @Transactional
    public BookmarkStatusResponse remove(Long userId, Long threadId) {
        bookmarkRepository.deleteByUserIdAndThreadId(userId, threadId);
        return new BookmarkStatusResponse(false);
    }

    @Transactional(readOnly = true)
    public BookmarkPageResponse list(Long userId, int page, int size) {
        int actualPage = Math.max(page, 1);
        int actualSize = Math.min(Math.max(size, 1), 20);
        Page<ThreadBookmarkRepository.BookmarkItemProjection> result = bookmarkRepository
                .findVisiblePageByUserId(userId, PageRequest.of(actualPage - 1, actualSize));

        return new BookmarkPageResponse(
                result.getContent().stream()
                        .map(item -> new BookmarkItem(
                                item.getThreadId(),
                                item.getTitle(),
                                item.getSectionName(),
                                item.getBookmarkedAt()))
                        .toList(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements());
    }

    private Thread requireNormalThread(Long threadId) {
        Thread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在"));
        if (!NORMAL_STATUS.equals(thread.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在");
        }
        return thread;
    }
}
