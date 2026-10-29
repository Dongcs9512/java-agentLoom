package com.aia.http;

import java.util.Map;

public final class StaticHeaderAuthenticator implements Authenticator {

    private final Map<String, String> headers;

    public StaticHeaderAuthenticator(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("静态请求头不能为空");
        }
        this.headers = Map.copyOf(headers);
    }

    @Override
    public Map<String, String> applyHeaders(Map<String, Object> context) {
        return headers;
    }
}
