package com.example.demo.storage.config;

import com.example.demo.storage.service.MediaUploadReconciler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.annotation.DurationFormat;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.media.registration-enabled", havingValue = "true")
public class MediaSchedulingConfiguration {
    // Object-store reconciliation must not occupy notification/search delivery threads.
    @Bean(name = "taskScheduler")
    @ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("scheduled-core-"); }

    @Bean(name = "mediaPublishScheduler")
    @ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler mediaPublishScheduler() { return scheduler("scheduled-media-publish-"); }

    @Bean(name = "mediaMaintenanceScheduler")
    @ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
    public ThreadPoolTaskScheduler mediaMaintenanceScheduler() { return scheduler("scheduled-media-storage-"); }

    @Bean
    @ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "true")
    ProcessingReconcileSchedule processingReconcileSchedule(MediaUploadReconciler reconciler) {
        return new ProcessingReconcileSchedule(reconciler);
    }

    @Bean
    @ConditionalOnProperty(name = "app.media.processing-enabled", havingValue = "false", matchIfMissing = true)
    RegistrationOnlyReconcileSchedule registrationOnlyReconcileSchedule(MediaUploadReconciler reconciler,
            @Value("${app.media.reconcile-delay-ms:60000}") String interval) {
        return new RegistrationOnlyReconcileSchedule(reconciler,
                DurationFormatterUtils.detectAndParse(interval, DurationFormat.Unit.MILLIS));
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    static final class ProcessingReconcileSchedule {
        private final MediaUploadReconciler reconciler;

        ProcessingReconcileSchedule(MediaUploadReconciler reconciler) {
            this.reconciler = reconciler;
        }

        @Scheduled(fixedDelayString = "${app.media.reconcile-delay-ms:60000}", scheduler = "mediaMaintenanceScheduler")
        public void reconcileUploads() {
            reconciler.reconcileUploads();
        }
    }

    /** Keeps registration-only object reads off the application's scheduler without registering another scheduler bean. */
    static final class RegistrationOnlyReconcileSchedule implements SmartLifecycle {
        private final MediaUploadReconciler reconciler;
        private final Duration interval;
        private ThreadPoolTaskScheduler worker;
        private boolean running;

        RegistrationOnlyReconcileSchedule(MediaUploadReconciler reconciler, Duration interval) {
            this.reconciler = reconciler;
            this.interval = interval;
        }

        @Override
        public synchronized void start() {
            if (running) return;
            ThreadPoolTaskScheduler next = scheduler("scheduled-media-storage-");
            next.initialize();
            try {
                next.scheduleWithFixedDelay(reconciler::reconcileUploads, interval);
            } catch (RuntimeException | Error failure) {
                next.shutdown();
                throw failure;
            }
            worker = next;
            running = true;
        }

        @Override
        public synchronized void stop() {
            if (!running) return;
            worker.shutdown();
            worker = null;
            running = false;
        }

        @Override
        public synchronized boolean isRunning() {
            return running;
        }
    }
}
