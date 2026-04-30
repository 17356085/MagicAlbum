package com.example.demo.user.connected.service.persistent;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnBean(UserConnectedAccountRepository.class)
public class PersistentConnectedAccountsService implements ConnectedAccountsService {
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "wechat"};

    private final UserConnectedAccountRepository repository;

    public PersistentConnectedAccountsService(UserConnectedAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<String, Object> list(Long userId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", listFromRepository(userId));
        return body;
    }

    @Override
    public ConnectedAccountDto connect(Long userId, String provider) {
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

    @Override
    public void bindExternalAccount(Long userId, String provider, String externalId, String displayName) {
        String normalized = normalizeProvider(provider);
        UserConnectedAccount entity = repository.findByUserIdAndProvider(userId, normalized)
                .orElseGet(UserConnectedAccount::new);
        entity.setUserId(userId);
        entity.setProvider(normalized);
        entity.setExternalId(trimToNull(externalId));
        entity.setDisplayName(trimToNull(displayName));
        entity.setLinkedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    public Long findUserIdByExternalAccount(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        return repository.findByProviderAndExternalId(normalizeProvider(provider), externalId.trim())
                .map(UserConnectedAccount::getUserId)
                .orElse(null);
    }

    @Override
    public void disconnect(Long userId, String provider) {
        repository.deleteByUserIdAndProvider(userId, normalizeProvider(provider));
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
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
