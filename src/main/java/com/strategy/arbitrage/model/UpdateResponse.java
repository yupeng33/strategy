package com.strategy.arbitrage.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateResponse {
    private boolean ok;
    private Update[] result;
}