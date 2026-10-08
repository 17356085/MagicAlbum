package com.example.demo.user.controller;

import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountControllerPaginationTest {

    @Test
    void listUsers_reports_page_metadata_after_service_clamps_request() {
        UserService userService = mock(UserService.class);
        DistributedRateLimiter rateLimiter = mock(DistributedRateLimiter.class);
        when(userService.list(isNull(), eq(0), eq(100), isNull()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        var body = new UserAccountController(userService, rateLimiter)
                .listUsers(null, 0, 100, null)
                .getBody();

        assertThat(body).containsEntry("page", 1).containsEntry("size", 20);
        verify(userService).list(isNull(), eq(0), eq(100), isNull());
    }
}
