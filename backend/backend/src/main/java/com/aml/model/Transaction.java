package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("from_account")
    private String fromAccount;

    @JsonProperty("from_name")
    private String fromName;

    @JsonProperty("to_account")
    private String toAccount;

    @JsonProperty("to_name")
    private String toName;

    private Double amount;
    private String currency;

    @JsonProperty("payment_format")
    private String paymentFormat;

    private String timestamp;

    @JsonProperty("pattern_id")
    private String patternId;

    @JsonProperty("is_fraud_seed")
    private Boolean isFraudSeed;

    @JsonProperty("typology_hint")
    private String typologyHint;

    private String note;
}
