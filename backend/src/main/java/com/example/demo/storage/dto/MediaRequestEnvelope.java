package com.example.demo.storage.dto;

import java.time.Instant;

public record MediaRequestEnvelope(int schemaVersion, String eventType, String eventId,
                                   String assetId, String jobId, int jobVersion,
                                   String preset, String processorVersion, Instant occurredAt) {
    public boolean valid() {
        return schemaVersion == 1 && "MEDIA_PROCESSING_REQUESTED".equals(eventType)
                && uuid(eventId) && uuid(assetId) && uuid(jobId) && jobVersion >= 1
                && "preview".equals(preset) && "1".equals(processorVersion) && occurredAt != null;
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
}
