package com.example.demo.user.connected.repo;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserConnectedAccountRepository extends JpaRepository<UserConnectedAccount, Long> {
    List<UserConnectedAccount> findByUserId(Long userId);

    Optional<UserConnectedAccount> findByUserIdAndProvider(Long userId, String provider);

    Optional<UserConnectedAccount> findByProviderAndExternalId(String provider, String externalId);

    void deleteByUserIdAndProvider(Long userId, String provider);
}
