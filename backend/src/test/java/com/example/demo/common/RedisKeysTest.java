package com.example.demo.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void thread_list_key_is_versioned_fixed_size_sha256_hex() {
        String key = RedisKeys.threadsList("query", "tag", 7L, 1, 10);

        assertThat(key).startsWith("cache:threads:list:v2:");
        assertThat(key.substring("cache:threads:list:v2:".length()))
                .matches("[0-9a-f]{64}");
        assertThat(key).hasSize("cache:threads:list:v2:".length() + 64);
    }

    @Test
    void thread_list_key_separates_delimiter_and_nul_ambiguous_tuples() {
        assertThat(RedisKeys.threadsList("a:b", "c", 7L, 1, 10))
                .isNotEqualTo(RedisKeys.threadsList("a", "b:c", 7L, 1, 10));
        assertThat(RedisKeys.threadsList("a\0b", "c", 7L, 1, 10))
                .isNotEqualTo(RedisKeys.threadsList("a", "b\0c", 7L, 1, 10));
        assertThat(RedisKeys.threadsList("同频:讨论", "标签🙂", 7L, 1, 10))
                .isNotEqualTo(RedisKeys.threadsList("同频", "讨论:标签🙂", 7L, 1, 10));
        assertThat(RedisKeys.threadsList(":", "%3A", 7L, 1, 10))
                .isNotEqualTo(RedisKeys.threadsList("%3A", ":", 7L, 1, 10));
    }

    @Test
    void thread_list_key_includes_every_tuple_dimension() {
        String base = RedisKeys.threadsList("q", "tag", 7L, 1, 10);
        Set<String> keys = new HashSet<>(Set.of(
                base,
                RedisKeys.threadsList("other", "tag", 7L, 1, 10),
                RedisKeys.threadsList("q", "other", 7L, 1, 10),
                RedisKeys.threadsList("q", "tag", 8L, 1, 10),
                RedisKeys.threadsList("q", "tag", 7L, 2, 10),
                RedisKeys.threadsList("q", "tag", 7L, 1, 20)
        ));

        assertThat(keys).hasSize(6);
    }

    @Test
    void thread_list_key_preserves_trim_and_null_normalization_and_overload() {
        String normalized = RedisKeys.threadsList(null, null, null, 1, 10);

        assertThat(RedisKeys.threadsList("  ", "\t", null, 1, 10)).isEqualTo(normalized);
        assertThat(RedisKeys.threadsList(" q ", 7L, 1, 10))
                .isEqualTo(RedisKeys.threadsList("q", null, 7L, 1, 10));
    }

    @Test
    void thread_list_key_does_not_reuse_legacy_raw_format_and_is_bounded_for_large_queries() {
        String hugeQueryKey = RedisKeys.threadsList("x".repeat(1_000_000), "tag", 7L, 1, 10);

        assertThat(hugeQueryKey).hasSize("cache:threads:list:v2:".length() + 64);
        assertThat(hugeQueryKey).isNotEqualTo(RedisKeys.threadsList("x", "tag", 7L, 1, 10));
        assertThat(hugeQueryKey).isNotEqualTo("cache:threads:list:x:tag:7:1:10");
    }

    @Test
    void thread_list_invalidator_prefix_remains_unchanged() {
        assertThat(RedisKeys.threadsListPattern()).isEqualTo("cache:threads:list:*");
    }
}
