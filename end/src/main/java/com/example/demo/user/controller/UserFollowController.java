package com.example.demo.user.controller;

import com.example.demo.user.dto.UserFollowResponse;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.service.UserFollowService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserFollowController extends AbstractUserControllerSupport {
    private final UserFollowService userFollowService;

    public UserFollowController(UserFollowService userFollowService) {
        this.userFollowService = userFollowService;
    }

    @PostMapping("/{id}/follow")
    public ResponseEntity<UserFollowResponse> follow(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userFollowService.follow(getUserId(), id));
    }

    @DeleteMapping("/{id}/follow")
    public ResponseEntity<UserFollowResponse> unfollow(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userFollowService.unfollow(getUserId(), id));
    }

    @GetMapping("/{id}/follow-status")
    public ResponseEntity<UserFollowResponse> status(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userFollowService.status(getUserId(), id));
    }

    @GetMapping("/{id}/followers")
    public ResponseEntity<Map<String, Object>> followers(
            @PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(toPageBody(userFollowService.listFollowers(id, page, size), page, size));
    }

    @GetMapping("/{id}/following")
    public ResponseEntity<Map<String, Object>> following(
            @PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ResponseEntity.ok(toPageBody(userFollowService.listFollowing(id, page, size), page, size));
    }

    private Map<String, Object> toPageBody(Page<UserSummaryDto> pageData, int page, int size) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", pageData.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", pageData.getTotalElements());
        return body;
    }
}
