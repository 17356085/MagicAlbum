package com.example.demo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {
    @Test
    void preserves_one_based_page_and_total_in_json() throws Exception {
        var response = PageResponse.from(new PageImpl<>(List.of("entry"), PageRequest.of(2, 10), 31));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertThat(json.get("page").intValue()).isEqualTo(3);
        assertThat(json.get("size").intValue()).isEqualTo(10);
        assertThat(json.get("total").longValue()).isEqualTo(31);
        assertThat(json.get("items").get(0).asText()).isEqualTo("entry");
    }

    @Test
    void response_does_not_retain_mutable_items_and_rejects_invalid_metadata() {
        var items = new ArrayList<>(List.of("entry"));
        var response = new PageResponse<>(items, 1, 10, 1);
        items.clear();
        assertThat(response.items()).containsExactly("entry");
        assertThatThrownBy(() -> new PageResponse<>(List.of(), 0, 10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
