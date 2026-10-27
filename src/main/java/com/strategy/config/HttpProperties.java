package com.strategy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "http")
public class HttpProperties {

    private Proxy proxy = new Proxy();
    private Timeout timeout = new Timeout();

    @Data
    public static class Proxy {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 8013;
    }

    @Data
    public static class Timeout {
        private int connectSeconds = 30;
        private int readSeconds = 30;
        private int writeSeconds = 30;
    }
}
