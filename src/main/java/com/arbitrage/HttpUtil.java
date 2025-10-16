package com.arbitrage;

import okhttp3.*;

public class HttpUtil {
    public static final OkHttpClient client = new OkHttpClient();

    public static Response send(String method, String url, RequestBody body, Headers headers) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .method(method, body)
                .build();
        return client.newCall(request).execute();
    }
}