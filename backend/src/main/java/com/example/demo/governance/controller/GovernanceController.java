package com.example.demo.governance.controller;

import com.example.demo.common.PageResponse;
import com.example.demo.governance.dto.AdminRoleRequest;
import com.example.demo.governance.dto.AuditView;
import com.example.demo.governance.dto.ModeratorSectionsRequest;
import com.example.demo.governance.dto.PostingRestrictionRequest;
import com.example.demo.governance.dto.SectionRequest;
import com.example.demo.governance.dto.SectionView;
import com.example.demo.governance.dto.UserView;
import com.example.demo.governance.service.GovernanceService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/governance")
public class GovernanceController {
    private final GovernanceService governanceService;

    public GovernanceController(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping("/me")
    public GovernanceService.Access me(Authentication authentication) {
        Long actor = currentUserId(authentication);
        governanceService.requireManagerOrAuthenticated(actor);
        return governanceService.access(actor);
    }

    @GetMapping("/users")
    public PageResponse<UserView> users(
            Authentication authentication,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return PageResponse.from(governanceService.listUsers(q, page, size, currentUserId(authentication)));
    }

    @PutMapping("/users/{id}/admin")
    public UserView admin(Authentication authentication,
                          @PathVariable("id") Long targetUserId,
                          @RequestBody AdminRoleRequest request) {
        return governanceService.setAdmin(currentUserId(authentication), targetUserId,
                request == null ? null : request.enabled(), request == null ? null : request.reason());
    }

    @PutMapping("/users/{id}/moderator-sections")
    public UserView moderatorSections(Authentication authentication,
                                      @PathVariable("id") Long targetUserId,
                                      @RequestBody ModeratorSectionsRequest request) {
        return governanceService.setModeratorSections(currentUserId(authentication), targetUserId,
                request == null ? null : request.sectionIds(), request == null ? null : request.reason());
    }

    @PutMapping("/users/{id}/posting-restriction")
    public UserView postingRestriction(Authentication authentication,
                                       @PathVariable("id") Long targetUserId,
                                       @RequestBody PostingRestrictionRequest request) {
        return governanceService.setPostingRestriction(currentUserId(authentication), targetUserId,
                request == null ? null : request.restricted(), request == null ? null : request.until(),
                request == null ? null : request.reason());
    }

    @GetMapping("/sections")
    public List<SectionView> sections(Authentication authentication) {
        return governanceService.listSections(currentUserId(authentication));
    }

    @PostMapping("/sections")
    public ResponseEntity<SectionView> createSection(Authentication authentication,
                                                     @RequestBody SectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(governanceService.createSection(currentUserId(authentication), request));
    }

    @PutMapping("/sections/{id}")
    public SectionView updateSection(Authentication authentication,
                                     @PathVariable("id") Long sectionId,
                                     @RequestBody SectionRequest request) {
        return governanceService.updateSection(currentUserId(authentication), sectionId, request);
    }

    @GetMapping("/audit")
    public PageResponse<AuditView> audit(Authentication authentication,
                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                         @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<AuditView> result = governanceService.listAudit(page, size, currentUserId(authentication));
        return PageResponse.from(result);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Number number) {
            return number.longValue();
        }
        if (principal instanceof String value) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
