package com.example.demo.storage.repo;

import com.example.demo.storage.entity.MediaAsset;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {

    Optional<MediaAsset> findByAssetIdAndOwnerId(String assetId, Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset where asset.assetId = :assetId")
    Optional<MediaAsset> findByAssetIdForUpdate(@Param("assetId") String assetId);
}
