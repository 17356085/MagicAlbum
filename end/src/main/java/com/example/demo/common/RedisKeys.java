package com.example.demo.common;

public final class RedisKeys {
    private RedisKeys() {
    }

    public static String rateLimitUser(long userId, int windowSeconds, int maxOps, long bucket) {
        return "rate_limit:user:%d:%d:%d:%d".formatted(userId, windowSeconds, maxOps, bucket);
    }

    public static String oauthState(String state) {
        return "oauth:state:" + state;
    }

    public static String authQr(String qrId) {
        return "auth:qr:" + qrId;
    }

    public static String sectionsList(String q, int page, int size) {
        String normalizedQ = q == null ? "" : q.trim();
        return "cache:sections:list:" + normalizedQ + ":" + page + ":" + size;
    }

    public static String threadsList(String q, Long sectionId, int page, int limit) {
        return threadsList(q, null, sectionId, page, limit);
    }

    public static String threadsList(String q, String tag, Long sectionId, int page, int limit) {
        String normalizedQ = q == null ? "" : q.trim();
        String normalizedTag = tag == null ? "" : tag.trim();
        String normalizedSectionId = sectionId == null ? "" : String.valueOf(sectionId);
        return "cache:threads:list:" + normalizedQ + ":" + normalizedTag + ":" + normalizedSectionId + ":" + page + ":" + limit;
    }

    public static String threadsListPattern() {
        return "cache:threads:list:*";
    }

    public static String threadDetail(long threadId) {
        return "cache:threads:detail:" + threadId;
    }

    public static String threadPosts(long threadId, String sort, int page, int size) {
        String normalizedSort = sort == null ? "time" : sort.trim();
        return "cache:posts:thread:" + threadId + ":" + normalizedSort + ":" + page + ":" + size;
    }

    public static String threadPostsPattern(long threadId) {
        return "cache:posts:thread:" + threadId + ":*";
    }

    public static String aiSummary(long threadId) {
        return "cache:ai:summary:" + threadId;
    }
}
