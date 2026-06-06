package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class EdgeData {
    @JsonProperty("edge_id")
    private String edgeId;

    @JsonProperty("from_account")
    private String fromAccount;

    @JsonProperty("to_account")
    private String toAccount;

    private Double amount;
    private String currency;

    @JsonProperty("payment_format")
    private String paymentFormat;

    private String timestamp;

    @JsonProperty("is_trigger")
    private Boolean isTrigger;
}