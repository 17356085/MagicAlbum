package com.example.demo.governance.dto;

import java.util.List;

public record ModeratorSectionsRequest(List<Long> sectionIds, String reason) {
}
