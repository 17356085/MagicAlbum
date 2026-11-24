package com.example.gateway;

enum HomeOverviewResource {
    THREADS("threads", "/api/v1/threads?page=1&size=10", false, false),
    SECTIONS("sections", "/api/v1/sections?page=1&size=20", false, false),
    PROFILE("profile", "/api/v1/users/me", true, false),
    FOLLOWING("following", "/api/v1/threads/following?page=1&size=4", true, false),
    UNREAD_NOTIFICATIONS(
            "unreadNotifications", "/api/v1/notifications?page=1&size=1&unread=true", true, true);

    private final String fieldName;
    private final String path;
    private final boolean personalized;
    private final boolean notificationService;

    HomeOverviewResource(String fieldName, String path, boolean personalized, boolean notificationService) {
        this.fieldName = fieldName;
        this.path = path;
        this.personalized = personalized;
        this.notificationService = notificationService;
    }

    String fieldName() {
        return fieldName;
    }

    String path() {
        return path;
    }

    boolean personalized() {
        return personalized;
    }

    boolean notificationService() {
        return notificationService;
    }
}
