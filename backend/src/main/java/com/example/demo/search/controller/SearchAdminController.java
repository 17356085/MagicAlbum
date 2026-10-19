package com.example.demo.search.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.search.service.ContentSearchReplayService;
import com.example.demo.search.service.SearchRebuildService;
import com.example.demo.threads.maintenance.service.ContentWriteGateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search")
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class SearchAdminController {
    private final AdminAccessService access;
    private final SearchRebuildService rebuild;
    private final ContentSearchReplayService replay;
    private final ContentWriteGateService control;

    public SearchAdminController(AdminAccessService access, SearchRebuildService rebuild,
                                 ContentSearchReplayService replay, ContentWriteGateService control) {
        this.access = access;
        this.rebuild = rebuild;
        this.replay = replay;
        this.control = control;
    }

    @GetMapping("/status")
    public ContentWriteGateService.Snapshot status(Authentication authentication) {
        access.requireAdmin(userId(authentication));
        return control.snapshot();
    }

    @PostMapping("/rebuild")
    public SearchRebuildService.RebuildResult rebuild(Authentication authentication) {
        access.requireAdmin(userId(authentication));
        return rebuild.rebuild();
    }

    @PostMapping("/failed/replay")
    public ContentSearchReplayService.ReplayResult replay(Authentication authentication,
                                                         @RequestParam(defaultValue = "10") int limit) {
        access.requireAdmin(userId(authentication));
        return replay.replay(limit);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof Number number ? number.longValue() : null;
    }
}
