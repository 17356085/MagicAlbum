package com.example.demo.bookmarks.service;

import com.example.demo.bookmarks.dto.BookmarkItem;
import com.example.demo.bookmarks.dto.BookmarkPageResponse;
import com.example.demo.bookmarks.dto.BookmarkStatusResponse;
import com.example.demo.bookmarks.entity.ThreadBookmark;
import com.example.demo.bookmarks.repo.ThreadBookmarkRepository;
import com.example.demo.threads.service.ThreadService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThreadBookmarkService {


    private final ThreadBookmarkRepository bookmarkRepository;
    private final ThreadService threadService;

    public ThreadBookmarkService(ThreadBookmarkRepository bookmarkRepository, ThreadService threadService) {
        this.bookmarkRepository = bookmarkRepository;
        this.threadService = threadService;
    }

    public BookmarkStatusResponse getStatus(Long userId, Long threadId) {
        threadService.requireNormalThread(threadId);
        return new BookmarkStatusResponse(bookmarkRepository.existsByUserIdAndThreadId(userId, threadId));
    }

    public BookmarkStatusResponse add(Long userId, Long threadId) {
        threadService.requireNormalThread(threadId);
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
}
