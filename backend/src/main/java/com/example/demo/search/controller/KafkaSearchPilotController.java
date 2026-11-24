package com.example.demo.search.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.search.service.ContentSearchPilotIndexes;
import com.example.demo.search.service.ContentSearchPilotIndexes.Carrier;
import com.example.demo.search.service.SearchRebuildService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/search/kafka-pilot")
@ConditionalOnProperty(name = {"app.search.enabled", "app.content.events.enabled", "app.content.events.kafka.enabled"},
        havingValue = "true")
public class KafkaSearchPilotController {
    private final AdminAccessService access;
    private final SearchRebuildService rebuild;
    private final ContentSearchPilotIndexes indexes;

    public KafkaSearchPilotController(AdminAccessService access, SearchRebuildService rebuild,
                                      ContentSearchPilotIndexes indexes) {
        this.access = access;
        this.rebuild = rebuild;
        this.indexes = indexes;
    }

    @PostMapping("/prepare")
    public SearchRebuildService.RebuildResult prepare(Authentication authentication, @RequestParam Carrier carrier) {
        requireAdmin(authentication);
        return rebuild.preparePilotIndex(indexes.target(carrier));
    }

    @PostMapping("/activate")
    public SearchRebuildService.RebuildResult activate(Authentication authentication, @RequestParam Carrier carrier) {
        requireAdmin(authentication);
        return rebuild.activatePilotIndex(indexes.target(carrier), indexes.peer(carrier));
    }

    private void requireAdmin(Authentication authentication) {
        Long userId = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Number number ? number.longValue() : null;
        access.requireAdmin(userId);
    }
}
