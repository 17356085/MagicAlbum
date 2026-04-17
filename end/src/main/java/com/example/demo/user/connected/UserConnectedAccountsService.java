package com.example.demo.user.connected;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserConnectedAccountsService {
    private final UserConnectedAccountRepository repository;
    private final ConcurrentHashMap<Long, Map<String, ConnectedAccountDto>> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> externalAccountIndex = new ConcurrentHashMap<>();
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "wechat"};

    @Autowired
    public UserConnectedAccountsService(UserConnectedAccountRepository repository) {
        this.repository = repository;
    }

    public UserConnectedAccountsService() {
        this.repository = null;
    }

    private Map<String, ConnectedAccountDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, k -> {
            Map<String, ConnectedAccountDto> map = new ConcurrentHashMap<>();
            for (String p : DEFAULT_PROVIDERS) {
                map.put(p, new ConnectedAccountDto(p, false, null, null));
            }
            return map;
        });
    }

    public Map<String, Object> list(Long userId) {
        if (repository != null) {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("items", listFromRepository(userId));
            return body;
        }
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        List<ConnectedAccountDto> items = new ArrayList<>(map.values());
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        return body;
    }

    public ConnectedAccountDto connect(Long userId, String provider) {
        if (repository != null) {
            String normalized = normalizeProvider(provider);
            UserConnectedAccount entity = repository.findByUserIdAndProvider(userId, normalized)
                    .orElseGet(UserConnectedAccount::new);
            entity.setUserId(userId);
            entity.setProvider(normalized);
            if (entity.getDisplayName() == null || entity.getDisplayName().isBlank()) {
                entity.setDisplayName((normalized + "_user").toLowerCase());
            }
            entity.setLinkedAt(Instant.now());
            return toDto(repository.save(entity));
        }
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, p -> new ConnectedAccountDto(p, false, null, null));
        dto.setConnected(true);
        if (dto.getDisplayName() == null || dto.getDisplayName().isBlank()) {
            dto.setDisplayName((provider + "_user").toLowerCase());
        }
        dto.setLinkedAt(Instant.now());
        return dto;
    }

    public void bindExternalAccount(Long userId, String provider, String externalId, String displayName) {
        if (repository != null) {
            String normalized = normalizeProvider(provider);
            UserConnectedAccount entity = repository.findByUserIdAndProvider(userId, normalized)
                    .orElseGet(UserConnectedAccount::new);
            entity.setUserId(userId);
            entity.setProvider(normalized);
            entity.setExternalId(trimToNull(externalId));
            entity.setDisplayName(trimToNull(displayName));
            entity.setLinkedAt(Instant.now());
            repository.save(entity);
            return;
        }
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, p -> new ConnectedAccountDto(p, false, null, null));
        dto.setConnected(true);
        dto.setDisplayName(displayName);
        dto.setLinkedAt(Instant.now());
        if (externalId != null && !externalId.isBlank()) {
            externalAccountIndex.put(buildExternalKey(provider, externalId), userId);
        }
    }

    public Long findUserIdByExternalAccount(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        if (repository != null) {
            return repository.findByProviderAndExternalId(normalizeProvider(provider), externalId.trim())
                    .map(UserConnectedAccount::getUserId)
                    .orElse(null);
        }
        return externalAccountIndex.get(buildExternalKey(provider, externalId));
    }

    public void disconnect(Long userId, String provider) {
        if (repository != null) {
            repository.deleteByUserIdAndProvider(userId, normalizeProvider(provider));
            return;
        }
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, p -> new ConnectedAccountDto(p, false, null, null));
        dto.setConnected(false);
        dto.setLinkedAt(null);
    }

    private List<ConnectedAccountDto> listFromRepository(Long userId) {
        Map<String, ConnectedAccountDto> items = new java.util.LinkedHashMap<>();
        for (String provider : DEFAULT_PROVIDERS) {
            items.put(provider, new ConnectedAccountDto(provider, false, null, null));
        }

        for (UserConnectedAccount entity : repository.findByUserId(userId)) {
            items.put(normalizeProvider(entity.getProvider()), toDto(entity));
        }

        return new ArrayList<>(items.values());
    }

    private ConnectedAccountDto toDto(UserConnectedAccount entity) {
        return new ConnectedAccountDto(
                normalizeProvider(entity.getProvider()),
                entity.getLinkedAt() != null,
                entity.getDisplayName(),
                entity.getLinkedAt()
        );
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String buildExternalKey(String provider, String externalId) {
        return provider.trim().toLowerCase() + ":" + externalId.trim();
    }
}
