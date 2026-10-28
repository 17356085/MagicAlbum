package com.example.demo.auth.service.otp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestCognitoOtpClientTest {
    private static final String BASE_URL = "http://cognito.test/";

    @ParameterizedTest
    @ValueSource(strings = {
            "com.amazonaws.cognitoidentityprovider.model#CodeMismatchException",
            "com.amazonaws.cognitoidentityprovider.model#ExpiredCodeException",
            "CodeMismatchException"
    })
    void http400BodyTypeRecognizesOnlyTheExactAwsErrorSuffix(String awsType) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"__type\":\"" + awsType + "\",\"message\":\"provider-secret\"}"));

        assertInvalidCode(client);
        server.verify();
    }

    @Test
    void http400HeaderTypeIsRecognizedEvenWhenBodyIsNotJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .header("x-amzn-errortype", "CodeMismatchException:provider-metadata")
                        .body("not-json provider-secret"));

        assertInvalidCode(client);
        server.verify();
    }

    @Test
    void http400BodyTypeDoesNotUseArbitraryMessageText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"CodeMismatchException provider-secret\"}"));

        assertUnavailable(client);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.amazonaws.cognitoidentityprovider.model#CodeMismatchExceptionExtra:provider-metadata",
            "not-CodeMismatchException:provider-metadata",
            "com.amazonaws.cognitoidentityprovider.model#NotAuthorizedException:provider-metadata"
    })
    void http400RequiresAnExactKnownErrorType(String awsType) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"__type\":\"" + awsType + "\",\"message\":\"provider-secret\"}"));

        assertUnavailable(client);
        server.verify();
    }

    @Test
    void emptyBodyAndMalformedBodyAreUnavailable() {
        for (String body : new String[]{"", "not-json provider-secret"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestCognitoOtpClient client = client(builder);
            server.expect(requestTo(BASE_URL))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(body));

            assertUnavailable(client);
            server.verify();
        }
    }

    @Test
    void transportIoFailureIsUnavailableWithoutRetainingTheFailure() {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory((uri, method) -> {
                    throw new IOException("transport-secret");
                });
        RestCognitoOtpClient client = client(builder);

        assertUnavailable(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503})
    void non400HttpStatusesAreUnavailableEvenWithKnownErrorBody(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .header("x-amzn-errortype", "CodeMismatchException")
                        .body("{\"__type\":\"CodeMismatchException\",\"message\":\"provider-secret\"}"));

        assertUnavailable(client);
        server.verify();
    }

    @Test
    void successfulResponseIsStillReturned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestCognitoOtpClient client = client(builder);
        server.expect(requestTo(BASE_URL))
                .andRespond(withSuccess(
                        "{\"AuthenticationResult\":{\"AccessToken\":\"access-token\"}}",
                        MediaType.APPLICATION_JSON));

        var response = client.respondToChallenge("EMAIL_OTP", "demo@example.com",
                Map.of("EMAIL_OTP_CODE", "123456"), "session");

        assertThat(response.getAuthenticationResult().getAccessToken()).isEqualTo("access-token");
        server.verify();
    }

    private static RestCognitoOtpClient client(RestClient.Builder builder) {
        return new RestCognitoOtpClient(builder, BASE_URL, "client-id", "client-secret");
    }

    private static void assertInvalidCode(RestCognitoOtpClient client) {
        assertThatThrownBy(() -> client.respondToChallenge("EMAIL_OTP", "demo@example.com",
                Map.of("EMAIL_OTP_CODE", "123456"), "session-secret"))
                .isInstanceOfSatisfying(CognitoOtpProviderException.class, exception -> {
                    assertThat(exception.isInvalidCode()).isTrue();
                    assertThat(exception).hasMessage("OTP_CODE_INVALID").hasNoCause();
                });
    }

    private static void assertUnavailable(RestCognitoOtpClient client) {
        assertThatThrownBy(() -> client.respondToChallenge("EMAIL_OTP", "demo@example.com",
                Map.of("EMAIL_OTP_CODE", "123456"), "session-secret"))
                .isInstanceOfSatisfying(CognitoOtpProviderException.class, exception -> {
                    assertThat(exception.isInvalidCode()).isFalse();
                    assertThat(exception).hasMessage("OTP_PROVIDER_UNAVAILABLE").hasNoCause();
                });
    }
}
