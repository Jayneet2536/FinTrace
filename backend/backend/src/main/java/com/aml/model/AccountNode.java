package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class AccountNode {
    @JsonProperty("account_id")
    private String accountId;

    private String name;

    @JsonProperty("total_sent")
    private Double totalSent;

    @JsonProperty("total_received")
    private Double totalReceived;

    @JsonProperty("tx_count_out")
    private Integer txCountOut;

    @JsonProperty("tx_count_in")
    private Integer txCountIn;

    @JsonProperty("unique_counterparts")
    private Integer uniqueCounterparts;

    @JsonProperty("is_new_account")
    private Boolean isNewAccount;

    @JsonProperty("dormancy_score")
    private Double dormancyScore;

    @JsonProperty("last_tx_timestamp")
    private String lastTxTimestamp;

    @JsonProperty("first_tx_timestamp")
    private String firstTxTimestamp;
}