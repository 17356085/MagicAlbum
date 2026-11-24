package com.example.demo.search.popular;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PopularSearchControllerTest {

    private final PopularSearchService service = mock(PopularSearchService.class);
    private final PopularSearchController controller = new PopularSearchController(service);

    @Test
    void getReturnsOnlyPublicKeywordStrings() {
        when(service.popular("threads")).thenReturn(List.of("java", "spring"));

        assertThat(controller.get("threads")).containsExactly("java", "spring");
        verify(service).popular("threads");
    }

    @Test
    void postUsesTheAuthenticatedPrincipalAndReturnsNoContent() {
        var authentication = new UsernamePasswordAuthenticationToken(73L, null, List.of());

        assertThat(controller.record(new PopularSearchRequest("users", "Java"), authentication)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).record(73L, "users", "Java");
    }

    @Test
    void postRejectsMissingOrNonNumericAuthenticatedPrincipals() {
        assertThatThrownBy(() -> controller.record(
                new PopularSearchRequest("users", "Java"), null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401");

        var nonNumeric = new UsernamePasswordAuthenticationToken("member-73", null, List.of());
        assertThatThrownBy(() -> controller.record(
                new PopularSearchRequest("users", "Java"), nonNumeric))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401");
    }
}
