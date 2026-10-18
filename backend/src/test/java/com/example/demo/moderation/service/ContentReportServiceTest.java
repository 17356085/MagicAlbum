package com.example.demo.moderation.service;

import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ReportResponse;
import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.moderation.repo.ContentReportRepository;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import com.example.demo.threads.service.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentReportServiceTest {

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private ThreadRepository threadRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private ThreadService threadService;

    @Mock
    private PostService postService;

    private ContentReportService service;

    @BeforeEach
    void setUp() {
        service = new ContentReportService(
                reportRepository,
                threadRepository,
                postRepository,
                threadService,
                postService
        );
    }

    @Test
    void duplicate_report_returns_existing_metadata_without_saving_again() {
        CreateReportRequest request = createRequest(ReportTargetType.THREAD, 11L);
        ContentReport existing = report(31L, 7L, ReportTargetType.THREAD, 11L, ReportStatus.PENDING);
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetId(7L, ReportTargetType.THREAD, 11L))
                .thenReturn(Optional.of(existing));

        ReportResponse response = service.create(7L, request);

        assertEquals(31L, response.getId());
        assertEquals(ReportStatus.PENDING, response.getStatus());
        verify(reportRepository, never()).save(any(ContentReport.class));
        verifyNoInteractions(threadRepository, postRepository);
    }

    @Test
    void hidden_thread_is_not_reportable() {
        CreateReportRequest request = createRequest(ReportTargetType.THREAD, 11L);
        Thread hidden = new Thread();
        hidden.setId(11L);
        hidden.setStatus("HIDDEN");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetId(7L, ReportTargetType.THREAD, 11L))
                .thenReturn(Optional.empty());
        when(threadRepository.findById(11L)).thenReturn(Optional.of(hidden));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.create(7L, request)
        );

        assertEquals(HttpStatus.NOT_FOUND.value(), failure.getStatusCode().value());
        verify(reportRepository, never()).save(any(ContentReport.class));
    }

    @Test
    void post_in_hidden_thread_is_not_reportable() {
        CreateReportRequest request = createRequest(ReportTargetType.POST, 21L);
        Post post = new Post();
        post.setId(21L);
        post.setThreadId(11L);
        post.setStatus("NORMAL");
        Thread hiddenThread = new Thread();
        hiddenThread.setId(11L);
        hiddenThread.setStatus("DELETED");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetId(7L, ReportTargetType.POST, 21L))
                .thenReturn(Optional.empty());
        when(postRepository.findById(21L)).thenReturn(Optional.of(post));
        when(threadRepository.findById(11L)).thenReturn(Optional.of(hiddenThread));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.create(7L, request)
        );

        assertEquals(HttpStatus.NOT_FOUND.value(), failure.getStatusCode().value());
        verify(reportRepository, never()).save(any(ContentReport.class));
    }

    @Test
    void post_report_context_includes_parent_thread_and_is_null_after_post_deletion() {
        ContentReport postReport = report(31L, 7L, ReportTargetType.POST, 21L, ReportStatus.PENDING);
        Page<ContentReport> stored = new PageImpl<>(
                List.of(postReport),
                org.springframework.data.domain.PageRequest.of(0, 20),
                1
        );
        Post post = new Post();
        post.setId(21L);
        post.setThreadId(11L);
        when(reportRepository.findByStatus(eq(ReportStatus.PENDING), any(Pageable.class))).thenReturn(stored);
        when(postRepository.findById(21L)).thenReturn(Optional.of(post));

        ReportResponse liveResponse = service.list(ReportStatus.PENDING, 1, 20).getContent().get(0);

        assertEquals(11L, liveResponse.getTargetThreadId());

        when(postRepository.findById(21L)).thenReturn(Optional.empty());
        ReportResponse deletedResponse = service.list(ReportStatus.PENDING, 1, 20).getContent().get(0);

        org.junit.jupiter.api.Assertions.assertNull(deletedResponse.getTargetThreadId());

        post.setStatus("DELETED");
        when(postRepository.findById(21L)).thenReturn(Optional.of(post));
        ReportResponse softDeletedResponse = service.list(ReportStatus.PENDING, 1, 20).getContent().get(0);

        org.junit.jupiter.api.Assertions.assertNull(softDeletedResponse.getTargetThreadId());
    }

    @Test
    void list_uses_pending_status_descending_sort_and_caps_size_at_fifty() {
        ContentReport report = report(31L, 7L, ReportTargetType.THREAD, 11L, ReportStatus.PENDING);
        Page<ContentReport> stored = new PageImpl<>(
                List.of(report),
                org.springframework.data.domain.PageRequest.of(2, 50),
                101
        );
        when(reportRepository.findByStatus(eq(ReportStatus.PENDING), any(Pageable.class))).thenReturn(stored);

        Page<ReportResponse> result = service.list(ReportStatus.PENDING, 3, 100);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findByStatus(eq(ReportStatus.PENDING), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(2, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
                pageable.getSort().getOrderFor("createdAt").getDirection());
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC,
                pageable.getSort().getOrderFor("id").getDirection());
        assertEquals(101, result.getTotalElements());
    }

    @Test
    void hide_resolution_persists_reviewer_and_hides_thread() {
        ContentReport pending = report(31L, 7L, ReportTargetType.THREAD, 11L, ReportStatus.PENDING);
        when(reportRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(pending));
        when(reportRepository.save(any(ContentReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ResolveReportRequest request = resolveRequest(ReportAction.HIDE);

        ReportResolutionResponse response = service.resolve(42L, 31L, request);

        assertEquals(ReportStatus.HIDDEN, response.getStatus());
        assertEquals(42L, response.getReviewerId());
        assertNotNull(response.getResolvedAt());
        verify(reportRepository).findByIdForUpdate(31L);
        verify(threadService).hideForModeration(11L);
        verifyNoInteractions(postService);
    }

    @Test
    void dismiss_resolution_leaves_target_service_untouched() {
        ContentReport pending = report(31L, 7L, ReportTargetType.POST, 21L, ReportStatus.PENDING);
        when(reportRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(pending));
        when(reportRepository.save(any(ContentReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReportResolutionResponse response = service.resolve(42L, 31L, resolveRequest(ReportAction.DISMISS));

        assertEquals(ReportStatus.DISMISSED, response.getStatus());
        assertEquals(42L, response.getReviewerId());
        verify(reportRepository).findByIdForUpdate(31L);
        verifyNoInteractions(threadService, postService);
    }

    @Test
    void same_resolution_action_is_idempotent_without_hiding_again() {
        ContentReport hidden = report(31L, 7L, ReportTargetType.THREAD, 11L, ReportStatus.HIDDEN);
        hidden.setReviewerId(42L);
        hidden.setResolvedAt(Instant.parse("2026-10-07T00:00:00Z"));
        when(reportRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(hidden));

        ReportResolutionResponse response = service.resolve(42L, 31L, resolveRequest(ReportAction.HIDE));

        assertEquals(ReportStatus.HIDDEN, response.getStatus());
        assertEquals(42L, response.getReviewerId());
        verify(reportRepository, never()).save(any(ContentReport.class));
        verifyNoInteractions(threadService, postService);
    }

    @Test
    void different_resolution_action_conflicts_after_resolution() {
        ContentReport hidden = report(31L, 7L, ReportTargetType.THREAD, 11L, ReportStatus.HIDDEN);
        when(reportRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(hidden));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.resolve(42L, 31L, resolveRequest(ReportAction.DISMISS))
        );

        assertEquals(HttpStatus.CONFLICT.value(), failure.getStatusCode().value());
        verifyNoInteractions(threadService, postService);
    }

    @Test
    void report_creation_requires_authentication() {
        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.create(null, createRequest(ReportTargetType.THREAD, 11L))
        );

        assertEquals(HttpStatus.UNAUTHORIZED.value(), failure.getStatusCode().value());
    }

    private CreateReportRequest createRequest(ReportTargetType targetType, Long targetId) {
        CreateReportRequest request = new CreateReportRequest();
        request.setTargetType(targetType);
        request.setTargetId(targetId);
        request.setReason(ReportReason.SPAM);
        request.setDetail("spam detail");
        return request;
    }

    private ResolveReportRequest resolveRequest(ReportAction action) {
        ResolveReportRequest request = new ResolveReportRequest();
        request.setAction(action);
        return request;
    }

    private ContentReport report(Long id,
                                 Long reporterId,
                                 ReportTargetType targetType,
                                 Long targetId,
                                 ReportStatus status) {
        ContentReport report = new ContentReport();
        report.setId(id);
        report.setReporterId(reporterId);
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReason(ReportReason.SPAM);
        report.setDetail("spam detail");
        report.setStatus(status);
        report.setCreatedAt(Instant.parse("2026-10-07T00:00:00Z"));
        return report;
    }
}
