package com.example.notification.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void logs_route_template_without_s3_token_or_query(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/uploads/s3/s3-secret-token");
        request.setQueryString("signature=query-secret");
        request.addHeader("X-Request-Id", "m1-filter-request");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/uploads/s3/{token}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, servletResponse) ->
                ((MockHttpServletResponse) servletResponse).setStatus(500));

        assertThat(output.toString()).contains("request_id=m1-filter-request")
                .contains("route=/uploads/s3/{token}")
                .contains("status=500")
                .contains("duration_ms=")
                .doesNotContain("s3-secret-token")
                .doesNotContain("query-secret");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("m1-filter-request");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void unmatched_exception_logs_fixed_route_and_cleans_mdc(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unmatched/s3/s3-secret-token");
        request.setQueryString("signature=query-secret");
        request.addHeader("X-Request-Id", "m1-filter-error");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new ServletException();
        })).isInstanceOf(ServletException.class);

        assertThat(output.toString()).contains("request_id=m1-filter-error")
                .contains("route=unmatched")
                .contains("status=500")
                .contains("duration_ms=")
                .doesNotContain("s3-secret-token")
                .doesNotContain("query-secret");
        assertThat(MDC.get("requestId")).isNull();
    }
}
