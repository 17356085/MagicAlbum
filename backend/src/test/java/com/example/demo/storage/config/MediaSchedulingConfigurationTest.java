package com.example.demo.storage.config;

import com.example.demo.storage.service.MediaDeliveryTransactions;
import com.example.demo.storage.service.MediaMaintenance;
import com.example.demo.storage.service.MediaMaintenanceTransactions;
import com.example.demo.storage.service.MediaObjectStore;
import com.example.demo.storage.service.MediaUploadReconciler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class MediaSchedulingConfigurationTest {

    @Test
    void registrationOnlyPreservesBootSchedulerSettings() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(
                        "app.media.registration-enabled=true",
                        "app.media.processing-enabled=false",
                        "spring.task.scheduling.pool.size=3",
                        "spring.task.scheduling.thread-name-prefix=existing-core-")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class)
                .run(context -> {
                    ThreadPoolTaskScheduler core = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
                    assertThat(core.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertThat(await(probe.ordinaryLatch, probe.ordinaryThread)).startsWith("existing-core-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyPreservesAnExistingCustomScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class, ExistingSchedulerConfiguration.class)
                .run(context -> {
                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertThat(await(probe.ordinaryLatch, probe.ordinaryThread)).startsWith("existing-custom-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyPreservesAnExistingScheduledExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class, ExistingExecutorConfiguration.class)
                .run(context -> {
                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertThat(await(probe.ordinaryLatch, probe.ordinaryThread)).startsWith("existing-executor-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyPreservesTheNamedExecutorWhenSeveralExist() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class, MultipleExecutorsConfiguration.class)
                .run(context -> {
                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertThat(await(probe.ordinaryLatch, probe.ordinaryThread)).startsWith("existing-named-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyKeepsTheFrameworkFallbackWhenBootSchedulingIsExcluded() {
        new ApplicationContextRunner()
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class)
                .run(context -> {
                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    assertThat(await(probe.ordinaryLatch, probe.ordinaryThread)).doesNotStartWith("scheduled-media-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyPreservesBootVirtualThreadScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false",
                        "spring.threads.virtual.enabled=true")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean("taskScheduler")).isInstanceOf(SimpleAsyncTaskScheduler.class);
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                });
    }

    @Test
    void registrationOnlyRoutesUnqualifiedAndMaintenanceTasksToSeparateSchedulers() {
        AtomicReference<MediaSchedulingConfiguration.RegistrationOnlyReconcileSchedule> lifecycle = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(
                        "app.media.registration-enabled=true",
                        "app.media.processing-enabled=false",
                        "app.media.reconcile-delay-ms=PT0.05S")
                .withUserConfiguration(MediaSchedulingConfiguration.class, SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class))
                            .containsOnlyKeys("taskScheduler");

                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    String ordinaryThread = await(probe.ordinaryLatch, probe.ordinaryThread);

                    assertThat(ordinaryThread).startsWith("scheduling-");
                    assertThat(ordinaryThread).doesNotStartWith("scheduled-media-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                    lifecycle.set(context.getBean(MediaSchedulingConfiguration.RegistrationOnlyReconcileSchedule.class));
                    assertThat(lifecycle.get().isRunning()).isTrue();
                });
        assertThat(lifecycle.get().isRunning()).isFalse();
    }

    @Test
    void registrationOnlyContinuesSchedulingAfterATaskFailureAndStopsOnClose() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch laterPass = new CountDownLatch(1);
        MediaUploadReconciler reconciler = mock(MediaUploadReconciler.class);
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("simulated database failure");
            laterPass.countDown();
            return null;
        }).when(reconciler).reconcileUploads();
        AtomicReference<MediaSchedulingConfiguration.RegistrationOnlyReconcileSchedule> lifecycle = new AtomicReference<>();

        new ApplicationContextRunner()
                .withPropertyValues("app.media.registration-enabled=true", "app.media.processing-enabled=false",
                        "app.media.reconcile-delay-ms=50")
                .withBean(MediaUploadReconciler.class, () -> reconciler)
                .withUserConfiguration(MediaSchedulingConfiguration.class)
                .run(context -> {
                    assertThat(laterPass.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(calls.get()).isGreaterThanOrEqualTo(2);
                    lifecycle.set(context.getBean(MediaSchedulingConfiguration.RegistrationOnlyReconcileSchedule.class));
                    assertThat(lifecycle.get().isRunning()).isTrue();
                });
        assertThat(lifecycle.get().isRunning()).isFalse();
    }

    @Test
    void processingAddsOnlyThePublishSchedulerAndKeepsTaskRoutingExplicit() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "app.media.registration-enabled=true",
                        "app.media.processing-enabled=true")
                .withUserConfiguration(
                        MediaSchedulingConfiguration.class,
                        SchedulingProbeConfiguration.class,
                        ReconcileProbeConfiguration.class,
                        PublishSchedulingProbeConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class))
                            .containsOnlyKeys("taskScheduler", "mediaMaintenanceScheduler", "mediaPublishScheduler");

                    SchedulingProbe probe = context.getBean(SchedulingProbe.class);
                    String ordinaryThread = await(probe.ordinaryLatch, probe.ordinaryThread);
                    PublishSchedulingProbe publishProbe = context.getBean(PublishSchedulingProbe.class);
                    String publishThread = await(publishProbe.publishLatch, publishProbe.publishThread);

                    assertThat(ordinaryThread).startsWith("scheduled-core-");
                    assertThat(ordinaryThread).doesNotStartWith("scheduled-media-");
                    assertMediaThread(context.getBean(ReconcileProbe.class));
                    assertThat(publishThread).startsWith("scheduled-media-publish-");
                });
    }

    @Test
    void disabledRegistrationDoesNotCreateMediaSchedulers() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "app.media.registration-enabled=false",
                        "app.media.processing-enabled=false")
                .withUserConfiguration(MediaSchedulingConfiguration.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(ThreadPoolTaskScheduler.class)).isEmpty();
                    assertThat(context.getBeansOfType(TaskScheduler.class)).isEmpty();
                });
    }

    @Test
    void processingWithoutRegistrationPreservesTheMediaMaintenanceFailure() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "app.media.registration-enabled=false",
                        "app.media.processing-enabled=true",
                        "app.media.orphan-retention=PT24H")
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(MediaDeliveryTransactions.class, () -> mock(MediaDeliveryTransactions.class))
                .withBean(MediaObjectStore.class, () -> mock(MediaObjectStore.class))
                .withBean(MediaMaintenanceTransactions.class, () -> mock(MediaMaintenanceTransactions.class))
                .withUserConfiguration(MediaMaintenance.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Media processing requires upload registration");
                });
    }

    private static String await(CountDownLatch latch, AtomicReference<String> threadName)
            throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return threadName.get();
    }

    private static void assertMediaThread(ReconcileProbe probe) throws InterruptedException {
        assertThat(await(probe.latch, probe.threadName)).startsWith("scheduled-media-storage-");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulingProbeConfiguration {
        @Bean
        SchedulingProbe schedulingProbe() {
            return new SchedulingProbe();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReconcileProbeConfiguration {
        @Bean
        ReconcileProbe reconcileProbe() {
            return new ReconcileProbe();
        }

        @Bean
        MediaUploadReconciler mediaUploadReconciler(ReconcileProbe probe) {
            return probe.reconciler;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PublishSchedulingProbeConfiguration {
        @Bean
        PublishSchedulingProbe publishSchedulingProbe() {
            return new PublishSchedulingProbe();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExistingSchedulerConfiguration {
        @Bean
        ThreadPoolTaskScheduler existingScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("existing-custom-");
            return scheduler;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExistingExecutorConfiguration {
        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService existingExecutor() {
            return Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "existing-executor-1");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MultipleExecutorsConfiguration {
        @Bean(name = "taskScheduler", destroyMethod = "shutdownNow")
        ScheduledExecutorService namedExecutor() {
            return executor("existing-named-");
        }

        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService otherExecutor() {
            return executor("existing-other-");
        }

        private static ScheduledExecutorService executor(String name) {
            return Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, name + "1");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    static class SchedulingProbe {
        private final CountDownLatch ordinaryLatch = new CountDownLatch(1);
        private final AtomicReference<String> ordinaryThread = new AtomicReference<>();

        @Scheduled(initialDelay = 10, fixedDelay = 60_000)
        public void ordinaryTask() {
            capture(ordinaryThread, ordinaryLatch);
        }

        private static void capture(AtomicReference<String> threadName, CountDownLatch latch) {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            latch.countDown();
        }
    }

    static class ReconcileProbe {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<String> threadName = new AtomicReference<>();
        private final MediaUploadReconciler reconciler = mock(MediaUploadReconciler.class);

        ReconcileProbe() {
            doAnswer(invocation -> {
                threadName.compareAndSet(null, Thread.currentThread().getName());
                latch.countDown();
                return null;
            }).when(reconciler).reconcileUploads();
        }
    }

    static class PublishSchedulingProbe {
        private final CountDownLatch publishLatch = new CountDownLatch(1);
        private final AtomicReference<String> publishThread = new AtomicReference<>();

        @Scheduled(initialDelay = 10, fixedDelay = 60_000, scheduler = "mediaPublishScheduler")
        public void publishTask() {
            capture(publishThread, publishLatch);
        }

        private static void capture(AtomicReference<String> threadName, CountDownLatch latch) {
            threadName.compareAndSet(null, Thread.currentThread().getName());
            latch.countDown();
        }
    }
}
