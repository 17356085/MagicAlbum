package com.example.demo.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.example.demo.storage.service.provider.s3.S3StorageService;

/** Explicit deployment contract; missing infrastructure never silently enables per-process auth state. */
@Configuration
public class DeploymentSafetyConfiguration {
    public DeploymentSafetyConfiguration(Environment environment, ObjectProvider<StringRedisTemplate> redis,
            S3StorageService s3,
            @Value("${app.deployment.multi-instance:false}") boolean multipleInstances,
            @Value("${app.storage.local.shared:false}") boolean sharedUploads) {
        boolean development = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (redis.getIfAvailable() == null && (!development || multipleInstances)) {
            throw new IllegalStateException("Redis is required for shared authentication state outside local/test single-instance mode");
        }
        if (multipleInstances && !s3.isConfigured() && !sharedUploads) {
            throw new IllegalStateException("Multiple instances require S3 or an explicitly shared local upload directory");
        }
    }
}
