package com.example.demo.search.service;

import com.example.demo.threads.event.dto.ContentEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
public class ContentSearchEventDecoder {
    private final ObjectMapper mapper;

    public ContentSearchEventDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ContentEventMessage decode(byte[] body) {
        if (body == null || body.length > 8192) {
            throw new InvalidContentEventException("INVALID_ENVELOPE_SIZE");
        }
        try {
            ContentEventMessage event = mapper.readValue(body, ContentEventMessage.class);
            ContentSearchProjection.validate(event);
            return event;
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidContentEventException("INVALID_ENVELOPE");
        }
    }
}
