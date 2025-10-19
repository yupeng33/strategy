package com.strategy.arbitrage.model.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.strategy.arbitrage.model.Message;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Update {
    private Long update_id;
    private Message message;
}