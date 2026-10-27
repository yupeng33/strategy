package com.strategy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // 启用定时任务
public class StrategyApplication {
    public static void main(String[] args) {
        // Avoid leftover IDEA/JVM SOCKS/HTTP proxy pointing to a dead local port.
        // Real proxy should be configured via application.yml http.proxy.* instead.
        clearJvmProxyIfPresent();
        SpringApplication.run(StrategyApplication.class, args);
    }

    private static void clearJvmProxyIfPresent() {
        String socksHost = System.getProperty("socksProxyHost");
        String httpHost = System.getProperty("http.proxyHost");
        String httpsHost = System.getProperty("https.proxyHost");
        if (socksHost != null || httpHost != null || httpsHost != null) {
            System.out.println("[StrategyApplication] clearing JVM proxy props: "
                    + "socks=" + socksHost + ":" + System.getProperty("socksProxyPort")
                    + ", http=" + httpHost + ":" + System.getProperty("http.proxyPort")
                    + ", https=" + httpsHost + ":" + System.getProperty("https.proxyPort"));
            System.clearProperty("socksProxyHost");
            System.clearProperty("socksProxyPort");
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("java.net.useSystemProxies");
        }
    }
}
