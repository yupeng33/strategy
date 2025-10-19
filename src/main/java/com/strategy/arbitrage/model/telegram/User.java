package com.strategy.arbitrage.model.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class User {
    private Long id;
    private String first_name;
    private String username;
    private Boolean is_bot;
    private String language_code;
}