package com.example.demo.search.popular;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PopularSearchQueryPolicyTest {

    @Test
    void trimsCollapsesWhitespaceAndUsesCaseInsensitiveDeduplication() {
        Optional<PopularSearchQueryPolicy.NormalizedQuery> result =
                PopularSearchQueryPolicy.normalize("  Spring   Boot\t3  ");

        assertThat(result).contains(new PopularSearchQueryPolicy.NormalizedQuery(
                "Spring Boot 3", "spring boot 3"));
    }

    @Test
    void acceptsTechnicalUnicodeTermsAndCommonPunctuation() {
        assertThat(PopularSearchQueryPolicy.normalize("Java 21" )).isPresent();
        assertThat(PopularSearchQueryPolicy.normalize("C++" )).isPresent();
        assertThat(PopularSearchQueryPolicy.normalize("C#" )).isPresent();
        assertThat(PopularSearchQueryPolicy.normalize(".NET / Spring Boot" )).isPresent();
        assertThat(PopularSearchQueryPolicy.normalize("中文 数据库" )).isPresent();
    }

    @Test
    void rejectsPersonalOrUnreasonableTerms() {
        assertThat(PopularSearchQueryPolicy.normalize("alice@example.com")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("https://example.com/a?q=1")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("localhost:8080/api")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("127.0.0.1:8080")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("C:/Users/alice")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("1234567890123456")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("member1234567")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("term\u0000with-control")).isEmpty();

        String tooLong = IntStream.range(0, PopularSearchQueryPolicy.MAX_CODE_POINTS + 1)
                .mapToObj(ignored -> "x")
                .reduce("", String::concat);
        assertThat(PopularSearchQueryPolicy.normalize(tooLong)).isEmpty();
    }

    @Test
    void rejectsBlankAndUnsupportedSymbols() {
        assertThat(PopularSearchQueryPolicy.normalize("   ")).isEmpty();
        assertThat(PopularSearchQueryPolicy.normalize("password=$secret")).isEmpty();
    }
}
