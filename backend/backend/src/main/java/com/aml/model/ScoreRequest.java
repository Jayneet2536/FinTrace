package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class ScoreRequest {
    @JsonProperty("trigger_transaction_id")
    private String triggerTransactionId;

    private List<AccountNode> nodes;
    private List<EdgeData> edges;
}