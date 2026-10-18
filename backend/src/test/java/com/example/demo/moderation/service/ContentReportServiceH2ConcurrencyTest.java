package com.example.demo.moderation.service;

import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportReason;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.moderation.repo.ContentReportRepository;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = com.example.demo.Demo1Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-reports-concurrency;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContentReportServiceH2ConcurrencyTest {

    @Autowired
    private ContentReportService reportService;

    @Autowired
    private ContentReportRepository reportRepository;

    @Autowired
    private ThreadRepository threadRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearRows() {
        reportRepository.deleteAll();
        threadRepository.deleteAll();
    }

    @Test
    void concurrent_hide_and_dismiss_have_one_winner_and_consistent_target_state() throws Exception {
        Thread thread = new Thread();
        thread.setSectionId(1L);
        thread.setAuthorId(2L);
        thread.setTitle("Concurrency target");
        thread.setContentMd("content");
        thread.setStatus("NORMAL");
        thread.setSpoiler(false);
        thread.setSummaryStatus("PENDING");
        thread.setSummaryAttempts(0);
        thread = threadRepository.saveAndFlush(thread);

        ContentReport report = new ContentReport();
        report.setReporterId(7L);
        report.setTargetType(ReportTargetType.THREAD);
        report.setTargetId(thread.getId());
        report.setReason(ReportReason.SPAM);
        report.setStatus(ReportStatus.PENDING);
        report = reportRepository.saveAndFlush(report);

        Long reportId = report.getId();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            Callable<ResolutionAttempt> hide = () -> resolve(reportId, ReportAction.HIDE, start);
            Callable<ResolutionAttempt> dismiss = () -> resolve(reportId, ReportAction.DISMISS, start);
            List<Future<ResolutionAttempt>> results = workers.invokeAll(List.of(hide, dismiss), 10, TimeUnit.SECONDS);
            List<ResolutionAttempt> attempts = List.of(results.get(0).get(), results.get(1).get());

            assertThat(attempts).anyMatch(ResolutionAttempt::succeeded);
            assertThat(attempts).anyMatch(ResolutionAttempt::conflict);

            ContentReport stored = reportRepository.findById(reportId).orElseThrow();
            Thread storedThread = threadRepository.findById(thread.getId()).orElseThrow();
            assertThat(stored.getStatus()).isIn(ReportStatus.HIDDEN, ReportStatus.DISMISSED);
            if (stored.getStatus() == ReportStatus.HIDDEN) {
                assertThat(storedThread.getStatus()).isEqualTo("HIDDEN");
            } else {
                assertThat(storedThread.getStatus()).isEqualTo("NORMAL");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private ResolutionAttempt resolve(Long reportId, ReportAction action, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            ReportResolutionResponse response = transaction.execute(status ->
                    reportService.resolve(42L, reportId, request(action)));
            return ResolutionAttempt.success(response);
        } catch (ResponseStatusException ex) {
            return ResolutionAttempt.conflict(ex);
        }
    }

    private ResolveReportRequest request(ReportAction action) {
        ResolveReportRequest request = new ResolveReportRequest();
        request.setAction(action);
        return request;
    }

    private record ResolutionAttempt(ReportResolutionResponse response, ResponseStatusException failure) {
        private static ResolutionAttempt success(ReportResolutionResponse response) {
            return new ResolutionAttempt(response, null);
        }

        private static ResolutionAttempt conflict(ResponseStatusException failure) {
            return new ResolutionAttempt(null, failure);
        }

        private boolean succeeded() {
            return response != null;
        }

        private boolean conflict() {
            return failure != null && failure.getStatusCode().value() == 409;
        }
    }
}
