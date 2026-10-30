package io.github.dongcs9512.agentloom.http;

import java.util.Map;

public interface Authenticator {
    Map<String, String> applyHeaders(Map<String, Object> context);
}
