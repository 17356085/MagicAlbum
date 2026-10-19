package com.example.demo.governance.dto;

import java.time.Instant;
import java.util.List;

public record UserView(
        Long id,
        String username,
        boolean admin,
        List<Long> moderatedSectionIds,
        boolean postingRestricted,
        Instant restrictedUntil
) {
    public UserView {
        moderatedSectionIds = moderatedSectionIds == null ? List.of() : List.copyOf(moderatedSectionIds);
    }
}
