package com.example.demo.config;

import com.example.demo.storage.service.provider.s3.S3StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentSafetyConfigurationTest {
    @Test void localSingleInstanceMayUseFallbackButProductionAndMultipleInstancesCannot() {
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        var s3 = mock(S3StorageService.class);
        var local = new MockEnvironment(); local.setActiveProfiles("local");
        assertThatCode(() -> new DeploymentSafetyConfiguration(local, redis, s3, false, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new DeploymentSafetyConfiguration(new MockEnvironment(), redis, s3, false, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DeploymentSafetyConfiguration(local, redis, s3, true, true)).isInstanceOf(IllegalStateException.class);
        when(redis.getIfAvailable()).thenReturn(mock(StringRedisTemplate.class));
        assertThatThrownBy(() -> new DeploymentSafetyConfiguration(local, redis, s3, true, false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new DeploymentSafetyConfiguration(local, redis, s3, true, true)).doesNotThrowAnyException();
        when(s3.isConfigured()).thenReturn(true);
        assertThatCode(() -> new DeploymentSafetyConfiguration(local, redis, s3, true, false)).doesNotThrowAnyException();
    }
}
