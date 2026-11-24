package com.example.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the opt-in home overview composition endpoint. */
@ConfigurationProperties(prefix = "home.bff")
public class HomeBffProperties {
    private boolean enabled;
    private URI coreBaseUrl;
    private URI notificationBaseUrl;
    private Duration callTimeout = Duration.ofMillis(750);
    private Duration publicCacheTtl = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public void setCoreBaseUrl(URI coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl;
    }

    public URI getNotificationBaseUrl() {
        return notificationBaseUrl;
    }

    public void setNotificationBaseUrl(URI notificationBaseUrl) {
        this.notificationBaseUrl = notificationBaseUrl;
    }

    public Duration getCallTimeout() {
        return callTimeout;
    }

    public void setCallTimeout(Duration callTimeout) {
        this.callTimeout = callTimeout;
    }

    public Duration getPublicCacheTtl() {
        return publicCacheTtl;
    }

    public void setPublicCacheTtl(Duration publicCacheTtl) {
        this.publicCacheTtl = publicCacheTtl;
    }
}
