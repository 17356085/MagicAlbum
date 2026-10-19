package com.example.demo.threads.event.repo;

import com.example.demo.threads.event.entity.ContentEventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentEventOutboxRepository extends JpaRepository<ContentEventOutbox, Long> {
    Optional<ContentEventOutbox> findByEventId(String eventId);

    Optional<ContentEventOutbox> findByAggregateIdAndAggregateVersion(Long aggregateId, long aggregateVersion);
}
