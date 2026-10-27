package com.example.demo.posts.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PostServiceMentionLookupTest {
    private final PostRepository posts = mock(PostRepository.class);
    private final ThreadRepository threads = mock(ThreadRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationClient notifications = mock(NotificationClient.class);
    private final Thread thread = new Thread();
    private PostService service;

    @BeforeEach
    void setUp() {
        thread.setId(7L);
        thread.setAuthorId(1L);
        thread.setTitle("Thread");
        thread.setStatus("NORMAL");
        when(threads.findByIdForInteraction(7L)).thenReturn(Optional.of(thread));
        when(users.existsById(1L)).thenReturn(true);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, "self")));
        when(posts.save(any(Post.class))).thenAnswer(invocation -> {
            Post saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(10L);
        when(view.getThreadId()).thenReturn(7L);
        when(posts.findViewById(10L)).thenReturn(Optional.of(view));
        service = new PostService(posts, null, threads, users, null, null, notifications);
    }

    @Test
    void repeatedKnownAndUnknownNamesAreLookedUpOncePerComment() {
        when(users.findByUsername("bob")).thenReturn(Optional.of(user(4L, "bob")));
        String content = "@bob ".repeat(200) + "@none ".repeat(200);
        assertThat(content.length()).isLessThan(3000);

        create(content, null);

        verify(users).findByUsername("bob");
        verify(users).findByUsername("none");
        verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);

        create(content, null);
        verify(users, times(2)).findByUsername("bob");
        verify(users, times(2)).findByUsername("none");
    }

    @Test
    void preservesSpellingSpecificDatabaseMatchingAndFirstRecipientOrder() {
        when(users.findByUsername("Alpha")).thenReturn(Optional.of(user(4L, "Alpha")));
        when(users.findByUsername("alpha")).thenReturn(Optional.of(user(4L, "Alpha")));
        when(users.findByUsername("bravo")).thenReturn(Optional.of(user(5L, "bravo")));

        create("@Alpha @bravo @alpha @Alpha @bravo", null);

        InOrder lookups = inOrder(users);
        lookups.verify(users).findByUsername("Alpha");
        lookups.verify(users).findByUsername("bravo");
        lookups.verify(users).findByUsername("alpha");
        verify(users, times(3)).findByUsername(anyString());
        InOrder delivery = inOrder(notifications);
        delivery.verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        delivery.verify(notifications).sendMention(eq(5L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void preservesSelfThreadAuthorAndParentReplyExclusions() {
        thread.setAuthorId(2L);
        Post parent = new Post();
        parent.setId(9L);
        parent.setAuthorId(3L);
        parent.setThreadId(7L);
        parent.setStatus("NORMAL");
        when(posts.findByIdForInteraction(9L)).thenReturn(Optional.of(parent));
        when(users.findByUsername("self")).thenReturn(Optional.of(user(1L, "self")));
        when(users.findByUsername("owner")).thenReturn(Optional.of(user(2L, "owner")));
        when(users.findByUsername("parent")).thenReturn(Optional.of(user(3L, "parent")));
        when(users.findByUsername("other")).thenReturn(Optional.of(user(4L, "other")));

        create("@self @owner @parent @other @other", 9L);

        verify(notifications).sendReply(eq(2L), anyString(), anyString(), eq(7L), eq(10L));
        verify(notifications).sendReply(eq(3L), anyString(), anyString(), eq(7L), eq(10L));
        verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void retainsMentionRegexBoundariesInsteadOfMatchingEmailAddresses() {
        when(users.findByUsername("real_user")).thenReturn(Optional.of(user(4L, "real_user")));

        create("name@example.com .@blocked @xy (@real_user) @real_user", null);

        verify(users).findByUsername("real_user");
        verify(users, times(1)).findByUsername(anyString());
        verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);
    }

    private void create(String content, Long parentId) {
        CreatePostRequest request = new CreatePostRequest();
        request.setContentMd(content);
        request.setReplyToPostId(parentId);
        assertThat(service.create(1L, 7L, request)).isNotNull();
        verify(posts, atLeastOnce()).save(any(Post.class));
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
