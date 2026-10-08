package com.example.demo.moderation.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ReportPageResponse;
import com.example.demo.moderation.dto.ReportResponse;
import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.entity.ReportStatus;
import com.example.demo.moderation.service.ContentReportService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ContentReportController {

    private final ContentReportService reportService;
    private final AdminAccessService adminAccessService;

    public ContentReportController(ContentReportService reportService,
                                   AdminAccessService adminAccessService) {
        this.reportService = reportService;
        this.adminAccessService = adminAccessService;
    }

    @PostMapping("/reports")
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest request) {
        ReportResponse response = reportService.create(currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/admin/reports")
    public ResponseEntity<ReportPageResponse> list(
            @RequestParam(value = "status", defaultValue = "PENDING") ReportStatus status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        Long adminId = currentUserIdOrNull();
        adminAccessService.requireAdmin(adminId);
        Page<ReportResponse> result = reportService.list(status, page, size);
        return ResponseEntity.ok(new ReportPageResponse(
                result.getContent(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements()
        ));
    }

    @PostMapping("/admin/reports/{id}/resolve")
    public ResponseEntity<ReportResolutionResponse> resolve(
            @PathVariable("id") Long reportId,
            @Valid @RequestBody ResolveReportRequest request
    ) {
        Long adminId = currentUserIdOrNull();
        adminAccessService.requireAdmin(adminId);
        return ResponseEntity.ok(reportService.resolve(adminId, reportId, request));
    }

    private Long currentUserId() {
        Long userId = currentUserIdOrNull();
        if (userId != null) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private Long currentUserIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
