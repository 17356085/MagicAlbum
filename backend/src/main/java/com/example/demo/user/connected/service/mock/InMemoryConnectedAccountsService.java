package com.example.demo.user.connected.service.mock;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class InMemoryConnectedAccountsService implements ConnectedAccountsService {
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "wechat"};

    private final Map<Long, Map<String, Binding>> store = new HashMap<>();
    private final Map<ExternalAccountKey, Long> externalAccountIndex = new HashMap<>();

    @Override
    public synchronized Map<String, Object> list(Long userId) {
        Objects.requireNonNull(userId, "userId");
        Map<String, Binding> map = store.getOrDefault(userId, Map.of());
        List<ConnectedAccountDto> items = new ArrayList<>();
        for (String provider : DEFAULT_PROVIDERS) {
            items.add(toDto(provider, map.get(provider)));
        }
        for (Map.Entry<String, Binding> entry : map.entrySet()) {
            if (!isDefaultProvider(entry.getKey())) {
                items.add(toDto(entry.getKey(), entry.getValue()));
            }
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        return body;
    }

    @Override
    public ConnectedAccountDto connect(Long userId, String provider) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成第三方授权，不能直接标记为已绑定");
    }

    @Override
    public synchronized void bindExternalAccount(Long userId, String provider, String externalId, String displayName) {
        Objects.requireNonNull(userId, "userId");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("第三方账号标识不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("第三方平台不能为空");
        }
        String normalizedProvider = normalizeProvider(provider);
        String normalizedExternalId = externalId.trim();
        ExternalAccountKey key = new ExternalAccountKey(normalizedProvider, normalizedExternalId);
        Long existingOwner = externalAccountIndex.get(key);
        if (existingOwner != null && !existingOwner.equals(userId)) {
            throw new DataIntegrityViolationException("第三方账号已绑定其他用户");
        }

        Map<String, Binding> map = store.computeIfAbsent(userId, ignored -> new LinkedHashMap<>());
        Binding previous = map.get(normalizedProvider);
        if (previous != null && !previous.externalId().equals(normalizedExternalId)) {
            ExternalAccountKey previousKey = new ExternalAccountKey(normalizedProvider, previous.externalId());
            if (userId.equals(externalAccountIndex.get(previousKey))) {
                externalAccountIndex.remove(previousKey);
            }
        }
        map.put(normalizedProvider, new Binding(
                normalizedExternalId,
                trimToNull(displayName),
                Instant.now()
        ));
        externalAccountIndex.put(key, userId);
    }

    @Override
    public synchronized Long findUserIdByExternalAccount(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        return externalAccountIndex.get(new ExternalAccountKey(normalizeProvider(provider), externalId.trim()));
    }

    @Override
    public synchronized void disconnect(Long userId, String provider) {
        Objects.requireNonNull(userId, "userId");
        Map<String, Binding> map = store.get(userId);
        if (map == null) {
            return;
        }
        String normalizedProvider = normalizeProvider(provider);
        Binding previous = map.remove(normalizedProvider);
        if (previous != null) {
            ExternalAccountKey key = new ExternalAccountKey(normalizedProvider, previous.externalId());
            if (userId.equals(externalAccountIndex.get(key))) {
                externalAccountIndex.remove(key);
            }
        }
        if (map.isEmpty()) {
            store.remove(userId);
        }
    }

    private ConnectedAccountDto toDto(String provider, Binding binding) {
        if (binding == null) {
            return new ConnectedAccountDto(provider, false, null, null);
        }
        return new ConnectedAccountDto(provider, true, binding.displayName(), binding.linkedAt());
    }

    private boolean isDefaultProvider(String provider) {
        for (String defaultProvider : DEFAULT_PROVIDERS) {
            if (defaultProvider.equals(provider)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record Binding(String externalId, String displayName, Instant linkedAt) {}

    private record ExternalAccountKey(String provider, String externalId) {}
}
