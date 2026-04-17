package com.example.demo.auth.service;

import com.example.demo.auth.dto.CognitoAuthFlowResponse;

import java.util.Map;

public interface CognitoOtpClient {
    CognitoAuthFlowResponse initiateUserAuth(String username, String preferredChallenge);

    CognitoAuthFlowResponse respondToChallenge(String challengeName, String username, Map<String, String> challengeResponses, String session);
}
