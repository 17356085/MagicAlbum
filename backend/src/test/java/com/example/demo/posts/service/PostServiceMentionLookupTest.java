package com.example.demo.posts.service;

import com.example.demo.notifications.client.NotificationClient;
import com.example.demo.posts.dto.CreatePostRequest;
import com.example.demo.posts.dto.PostQueryView;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.user.service.UserReadService;
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
    private final UserReadService users = mock(UserReadService.class);
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
        when(users.displayNameById(1L)).thenReturn(Optional.of("self"));
        when(posts.save(any(Post.class))).thenAnswer(invocation -> {
            Post saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        PostQueryView view = mock(PostQueryView.class);
        when(view.getId()).thenReturn(10L);
        when(view.getThreadId()).thenReturn(7L);
        when(posts.findViewById(10L)).thenReturn(Optional.of(view));
        service = new PostService(posts, null, threads, users, null, notifications);
    }

    @Test
    void repeatedKnownAndUnknownNamesAreLookedUpOncePerComment() {
        when(users.findIdByUsername("bob")).thenReturn(Optional.of(4L));
        String content = "@bob ".repeat(200) + "@none ".repeat(200);
        assertThat(content.length()).isLessThan(3000);

        create(content, null);

        verify(users).findIdByUsername("bob");
        verify(users).findIdByUsername("none");
        verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);

        create(content, null);
        verify(users, times(2)).findIdByUsername("bob");
        verify(users, times(2)).findIdByUsername("none");
    }

    @Test
    void preservesSpellingSpecificDatabaseMatchingAndFirstRecipientOrder() {
        when(users.findIdByUsername("Alpha")).thenReturn(Optional.of(4L));
        when(users.findIdByUsername("alpha")).thenReturn(Optional.of(4L));
        when(users.findIdByUsername("bravo")).thenReturn(Optional.of(5L));

        create("@Alpha @bravo @alpha @Alpha @bravo", null);

        InOrder lookups = inOrder(users);
        lookups.verify(users).findIdByUsername("Alpha");
        lookups.verify(users).findIdByUsername("bravo");
        lookups.verify(users).findIdByUsername("alpha");
        verify(users, times(3)).findIdByUsername(anyString());
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
        when(users.findIdByUsername("self")).thenReturn(Optional.of(1L));
        when(users.findIdByUsername("owner")).thenReturn(Optional.of(2L));
        when(users.findIdByUsername("parent")).thenReturn(Optional.of(3L));
        when(users.findIdByUsername("other")).thenReturn(Optional.of(4L));

        create("@self @owner @parent @other @other", 9L);

        verify(notifications).sendReply(eq(2L), anyString(), anyString(), eq(7L), eq(10L));
        verify(notifications).sendReply(eq(3L), anyString(), anyString(), eq(7L), eq(10L));
        verify(notifications).sendMention(eq(4L), anyString(), anyString(), eq(7L), eq(10L));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void retainsMentionRegexBoundariesInsteadOfMatchingEmailAddresses() {
        when(users.findIdByUsername("real_user")).thenReturn(Optional.of(4L));

        create("name@example.com .@blocked @xy (@real_user) @real_user", null);

        verify(users).findIdByUsername("real_user");
        verify(users, times(1)).findIdByUsername(anyString());
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

}
