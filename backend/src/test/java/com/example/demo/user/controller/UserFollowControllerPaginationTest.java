package com.example.demo.user.controller;

import com.example.demo.user.service.UserFollowService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserFollowControllerPaginationTest {

    @Test
    void followers_reports_page_metadata_after_service_clamps_request() {
        UserFollowService userFollowService = mock(UserFollowService.class);
        when(userFollowService.listFollowers(9L, 0, 100))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        var body = new UserFollowController(userFollowService)
                .followers(9L, 0, 100)
                .getBody();

        assertThat(body).containsEntry("page", 1).containsEntry("size", 50);
        verify(userFollowService).listFollowers(eq(9L), eq(0), eq(100));
    }

    @Test
    void following_reports_page_metadata_after_service_clamps_request() {
        UserFollowService userFollowService = mock(UserFollowService.class);
        when(userFollowService.listFollowing(9L, 0, 100))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        var body = new UserFollowController(userFollowService)
                .following(9L, 0, 100)
                .getBody();

        assertThat(body).containsEntry("page", 1).containsEntry("size", 50);
        verify(userFollowService).listFollowing(eq(9L), eq(0), eq(100));
    }
}
