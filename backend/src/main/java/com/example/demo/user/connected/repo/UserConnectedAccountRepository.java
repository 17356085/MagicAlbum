package com.example.demo.user.connected.repo;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserConnectedAccountRepository extends JpaRepository<UserConnectedAccount, Long> {
    List<UserConnectedAccount> findByUserId(Long userId);

    Optional<UserConnectedAccount> findByUserIdAndProvider(Long userId, String provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM UserConnectedAccount a WHERE a.userId = :userId AND a.provider = :provider")
    Optional<UserConnectedAccount> findForUpdate(@Param("userId") Long userId, @Param("provider") String provider);

    Optional<UserConnectedAccount> findByProviderAndExternalId(String provider, String externalId);

}
