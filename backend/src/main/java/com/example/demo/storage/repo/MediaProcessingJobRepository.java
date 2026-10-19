package com.example.demo.storage.repo;

import com.example.demo.storage.entity.MediaProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MediaProcessingJobRepository extends JpaRepository<MediaProcessingJob, String> {

    Optional<MediaProcessingJob> findFirstByAssetIdOrderByJobVersionDesc(String assetId);

    Optional<MediaProcessingJob> findByAssetIdAndJobVersionAndPreset(
            String assetId,
            int jobVersion,
            String preset
    );

    long countByAssetId(String assetId);
}
