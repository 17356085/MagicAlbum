package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.OAuthProvider;
import com.example.demo.common.RedisKeys;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OAuthStateService {
    private final long stateTtlSeconds;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, OAuthStateSession> stateStore = new ConcurrentHashMap<>();

    @Autowired
    public OAuthStateService(@Value("${app.auth.oauth.state-ttl-seconds:300}") long stateTtlSeconds,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(stateTtlSeconds, redisTemplateProvider.getIfAvailable());
    }

    OAuthStateService(long stateTtlSeconds, StringRedisTemplate redisTemplate) {
        this.stateTtlSeconds = Math.max(stateTtlSeconds, 60);
        this.redisTemplate = redisTemplate;
    }

    static OAuthStateService forTest(long stateTtlSeconds) {
        return new OAuthStateService(stateTtlSeconds, (StringRedisTemplate) null);
    }

    public String issue(OAuthProvider provider) {
        purgeExpiredStates();
        String state = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(stateTtlSeconds);
        if (!storeInRedis(state, provider)) {
            stateStore.put(state, new OAuthStateSession(provider, expiresAt));
        }
        return state;
    }

    public void consume(OAuthProvider provider, String state) {
        String normalized = trimToEmpty(state);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 OAuth state");
        }

        purgeExpiredStates();
        OAuthStateSession session = consumeFromRedis(normalized);
        if (session == null) {
            session = stateStore.remove(normalized);
        }
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state 无效或已过期");
        }
        if (session.provider() != provider) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state 与当前 Provider 不匹配");
        }
    }

    public boolean consumeSafely(OAuthProvider provider, String state) {
        try {
            consume(provider, state);
            return true;
        } catch (ResponseStatusException ex) {
            return false;
        }
    }

    private void purgeExpiredStates() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        stateStore.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private boolean storeInRedis(String state, OAuthProvider provider) {
        if (redisTemplate == null || provider == null) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(redisKey(state), provider.name(), Duration.ofSeconds(stateTtlSeconds));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private OAuthStateSession consumeFromRedis(String state) {
        if (redisTemplate == null || state == null || state.isBlank()) {
            return null;
        }
        try {
            String providerName = redisTemplate.opsForValue().getAndDelete(redisKey(state));
            if (providerName == null || providerName.isBlank()) {
                return null;
            }
            OAuthProvider provider = OAuthProvider.valueOf(providerName);
            return new OAuthStateSession(provider, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(stateTtlSeconds));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String redisKey(String state) {
        return RedisKeys.oauthState(state);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record OAuthStateSession(
            OAuthProvider provider,
            OffsetDateTime expiresAt
    ) {}
}
