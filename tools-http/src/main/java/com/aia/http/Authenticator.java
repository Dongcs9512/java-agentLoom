package com.aia.http;

import java.util.Map;

public interface Authenticator {
    Map<String, String> applyHeaders(Map<String, Object> context);
}
