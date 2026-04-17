package com.example.demo.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CognitoAuthFlowResponse {
    @JsonProperty("ChallengeName")
    private String challengeName;

    @JsonProperty("Session")
    private String session;

    @JsonProperty("ChallengeParameters")
    private Map<String, String> challengeParameters = new HashMap<>();

    @JsonProperty("AvailableChallenges")
    private List<String> availableChallenges;

    @JsonProperty("AuthenticationResult")
    private CognitoAuthenticationResult authenticationResult;

    public String getChallengeName() {
        return challengeName;
    }

    public void setChallengeName(String challengeName) {
        this.challengeName = challengeName;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public Map<String, String> getChallengeParameters() {
        return challengeParameters;
    }

    public void setChallengeParameters(Map<String, String> challengeParameters) {
        this.challengeParameters = challengeParameters == null ? new HashMap<>() : challengeParameters;
    }

    public List<String> getAvailableChallenges() {
        return availableChallenges;
    }

    public void setAvailableChallenges(List<String> availableChallenges) {
        this.availableChallenges = availableChallenges;
    }

    public CognitoAuthenticationResult getAuthenticationResult() {
        return authenticationResult;
    }

    public void setAuthenticationResult(CognitoAuthenticationResult authenticationResult) {
        this.authenticationResult = authenticationResult;
    }
}
