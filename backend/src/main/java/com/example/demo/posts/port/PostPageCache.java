package com.example.demo.posts.port;

import com.example.demo.posts.dto.PostDto;

import java.util.List;

/**
 * Optional cache for anonymous pages of comments and their related thread caches.
 *
 * <p>The port deliberately does not validate page freshness.  The Posts
 * application service owns that decision because it must consult the
 * authoritative Post read state before serving a cached page.</p>
 */
public interface PostPageCache {
    CachedPostPage read(Long threadId, String sort, int page, int size);

    void write(Long threadId, String sort, int page, int size, CachedPostPage payload);

    void evictThreadPosts(Long threadId);

    void evictThread(Long threadId);

    record CachedPostPage(List<PostDto> items, long total) {
    }
}
