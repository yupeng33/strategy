package com.strategy.arbitrage.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.strategy.arbitrage.model.telegram.User;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {
    private Long message_id;
    private User from;
    private String text;
}