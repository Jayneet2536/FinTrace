package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class FraudAlert {
    private String id;
    private String timestamp;
    private String typology;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("fraud_score")
    private Double fraudScore;

    @JsonProperty("raw_gnn_score")
    private Double rawGnnScore;

    private Double confidence;

    @JsonProperty("latency_ms")
    private Double latencyMs;

    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    @JsonProperty("graph_data")
    private Map<String, Object> graphData;

    private List<AccountNode> nodes;

    private List<EdgeData> edges;

    @JsonProperty("evidence_chain")
    private List<Map<String, Object>> evidenceChain;

    @JsonProperty("risk_breakdown")
    private Map<String, Object> riskBreakdown;

    @JsonProperty("total_amount")
    private Double totalAmount;

    @JsonProperty("accounts_involved")
    private List<String> accountsInvolved;
}
