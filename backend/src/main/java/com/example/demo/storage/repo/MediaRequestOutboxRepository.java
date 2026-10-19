package com.example.demo.storage.repo;

import com.example.demo.storage.entity.MediaRequestOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MediaRequestOutboxRepository extends JpaRepository<MediaRequestOutbox, String> {

    Optional<MediaRequestOutbox> findByEventId(String eventId);

    Optional<MediaRequestOutbox> findByJobIdAndJobVersion(String jobId, int jobVersion);

    long countByJobIdAndJobVersion(String jobId, int jobVersion);
}
