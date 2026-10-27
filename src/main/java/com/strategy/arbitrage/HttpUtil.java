package com.strategy.arbitrage;

import com.strategy.config.HttpProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HttpUtil {

    public static OkHttpClient client = new OkHttpClient();

    @Resource
    private HttpProperties httpProperties;

    @PostConstruct
    public void init() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(httpProperties.getTimeout().getConnectSeconds(), TimeUnit.SECONDS)
                .readTimeout(httpProperties.getTimeout().getReadSeconds(), TimeUnit.SECONDS)
                .writeTimeout(httpProperties.getTimeout().getWriteSeconds(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // Prefer IPv4 to avoid fake-ip / broken IPv6 paths
                .dns(hostname -> {
                    List<InetAddress> all = Arrays.asList(InetAddress.getAllByName(hostname));
                    List<InetAddress> v4 = all.stream().filter(a -> a.getAddress().length == 4).collect(Collectors.toList());
                    return v4.isEmpty() ? all : v4;
                });

        if (httpProperties.getProxy().isEnabled()) {
            Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(
                            httpProperties.getProxy().getHost(),
                            httpProperties.getProxy().getPort()
                    )
            );
            builder.proxy(proxy);
            log.info("HttpUtil using HTTP proxy {}:{}",
                    httpProperties.getProxy().getHost(),
                    httpProperties.getProxy().getPort());
        } else {
            // Critical: do NOT fall back to JVM/macOS system ProxySelector
            // (system proxy to 127.0.0.1:7890 while Clash is off => Connection refused)
            builder.proxy(Proxy.NO_PROXY);
            log.info("HttpUtil proxy disabled, using direct connection (NO_PROXY)");
        }

        client = builder.build();
        probeBinance();
    }

    private void probeBinance() {
        try {
            InetAddress[] addrs = InetAddress.getAllByName("fapi.binance.com");
            log.info("fapi.binance.com resolves to {}",
                    Arrays.stream(addrs).map(InetAddress::getHostAddress).collect(Collectors.joining(", ")));
            Request request = new Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/ping")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                log.info("binance ping => http {}", response.code());
            }
        } catch (UnknownHostException e) {
            log.error("binance DNS failed: {}", e.getMessage());
        } catch (Exception e) {
            log.error("binance ping failed: {} (if Connection refused: check macOS system proxy / Clash, or set http.proxy.enabled)",
                    e.getMessage());
        }
    }

    public static Response send(String method, String url, RequestBody body, Headers headers) throws Exception {
        RequestBody reqBody = body == null
                ? RequestBody.create("", MediaType.parse("application/json"))
                : body;
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .method(method, reqBody)
                .build();
        return client.newCall(request).execute();
    }
}
