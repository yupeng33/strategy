package com.strategy.arbitrage;

import okhttp3.*;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public class HttpUtil {
    /**
     * 启动参数里的 socksProxyHost 会让普通 Socket 走 SOCKS。
     * 这里用 {@link Socket#Socket(Proxy) NO_PROXY} 直连，避免本机 7890 未启动时 Connection refused。
     */
    public static final OkHttpClient client = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(new DirectSocketFactory())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final class DirectSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket(Proxy.NO_PROXY);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = createSocket();
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = createSocket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }

    public static Response send(String method, String url, RequestBody body, Headers headers) throws Exception {
        body = body == null ? RequestBody.create("", MediaType.parse("application/json")) : body;
        Request request = new Request.Builder()
                .url(url)
                .headers(headers)
                .method(method, body)
                .build();
        return client.newCall(request).execute();
    }
}