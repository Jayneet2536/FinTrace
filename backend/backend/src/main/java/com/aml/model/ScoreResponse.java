package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ScoreResponse {
    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    @JsonProperty("is_fraud")
    private Boolean isFraud;

    private String typology;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("fraud_score")
    private Double fraudScore;

    @JsonProperty("raw_gnn_score")
    private Double rawGnnScore;

    private Double confidence;

    @JsonProperty("evidence_chain")
    private List<Map<String, Object>> evidenceChain;

    @JsonProperty("graph_data")
    private Map<String, Object> graphData;

    @JsonProperty("risk_breakdown")
    private Map<String, Object> riskBreakdown;

    @JsonProperty("latency_ms")
    private Double latencyMs;
}