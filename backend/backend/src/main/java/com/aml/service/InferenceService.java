package com.aml.service;

import com.aml.model.AccountNode;
import com.aml.model.EdgeData;
import com.aml.model.ScoreRequest;
import com.aml.model.ScoreResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InferenceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${inference.url}")
    private String inferenceUrl;

    public InferenceService(RestTemplate restTemplate,
                            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public ScoreResponse score(ScoreRequest request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<ScoreRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<ScoreResponse> response = restTemplate.exchange(
                    inferenceUrl + "/score",
                    HttpMethod.POST,
                    entity,
                    ScoreResponse.class
            );

            ScoreResponse body = response.getBody();
            if (body == null) {
                return buildFallbackScoreResponse();
            }

            return body;
        } catch (Exception e) {
            System.err.println("Inference service error: " + e.getMessage());
            return buildFallbackScoreResponse();
        }
    }

    public Map<String, Object> generateReport(
            ScoreResponse scoreResult,
            List<AccountNode> nodes,
            List<EdgeData> edges) {

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("score_result", scoreResult);
            requestBody.put("nodes", nodes);
            requestBody.put("edges", edges);
            requestBody.put("reporting_entity", "Demo Bank Ltd");
            requestBody.put("branch", "Main Branch");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    inferenceUrl + "/report",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            return response.getBody();
        } catch (Exception e) {
            System.err.println("Report generation error: " + e.getMessage());
            return Map.of("error", "Report generation failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> forwardReportRequest(
            String transactionId,
            Map<String, Object> requestBody) {
        try {
            Map<String, Object> payload = new HashMap<>(requestBody);
            payload.putIfAbsent("reporting_entity", "Demo Bank Ltd");
            payload.putIfAbsent("branch", "Main Branch");

            Object scoreResult = payload.get("score_result");
            if (scoreResult instanceof Map<?, ?> scoreMap) {
                Object triggerId = scoreMap.get("trigger_transaction_id");
                if (triggerId == null || String.valueOf(triggerId).isBlank()) {
                    ((Map<String, Object>) scoreMap).put("trigger_transaction_id", transactionId);
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity =
                    new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    inferenceUrl + "/report",
                    HttpMethod.POST,
                    entity,
                    Map.class
            );

            return response.getBody();
        } catch (Exception e) {
            System.err.println("Report forwarding error: " + e.getMessage());
            return Map.of("error", "Report generation failed: " + e.getMessage());
        }
    }

    private ScoreResponse buildFallbackScoreResponse() {
        ScoreResponse fallback = new ScoreResponse();
        fallback.setIsFraud(false);
        fallback.setTypology("Legitimate");
        fallback.setRiskLevel("LOW");
        fallback.setFraudScore(0.0);
        fallback.setRawGnnScore(0.0);
        fallback.setConfidence(1.0);
        fallback.setLatencyMs(0.0);
        fallback.setEvidenceChain(Collections.emptyList());
        fallback.setGraphData(Map.of("nodes", Collections.emptyList(), "edges", Collections.emptyList()));
        fallback.setRiskBreakdown(Map.of("reason", "Inference unavailable or empty response"));
        return fallback;
    }
}
