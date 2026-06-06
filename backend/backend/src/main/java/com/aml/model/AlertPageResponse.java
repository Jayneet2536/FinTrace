package com.aml.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AlertPageResponse {
    private List<FraudAlert> items;
    private int page;
    private int limit;
    private long total;

    @JsonProperty("total_pages")
    private int totalPages;
}
