package io.github.dongcs9512.agentloom.server.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * 默认实现：开 {@code trust-operator-headers} 时读网关注入的 header，否则回落到请求体。
 *
 * <p>默认**不信任 header**——公网直连时任何人都能伪造 {@code X-Operator-Id}，
 * 只有在前面确实有网关剥离并重写该头时才应该打开。
 */
public final class DefaultOperatorResolver implements OperatorResolver {

    private final boolean trustHeaders;
    private final String idHeader;
    private final String nameHeader;

    public DefaultOperatorResolver(boolean trustHeaders, String idHeader, String nameHeader) {
        this.trustHeaders = trustHeaders;
        this.idHeader = idHeader;
        this.nameHeader = nameHeader;
    }

    @Override
    public Optional<OperatorIdentity> resolve(Map<String, String> headers, String bodyOperator) {
        if (trustHeaders) {
            // 信任 header 时**只**认 header：再允许请求体兜底，等于给伪造留口子
            String id = headers == null ? null : header(headers, idHeader);
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            String name = header(headers, nameHeader);
            return Optional.of(OperatorIdentity.human(id.trim(),
                    name == null || name.isBlank() ? id.trim() : name.trim()));
        }
        if (bodyOperator != null && !bodyOperator.isBlank()) {
            return Optional.of(OperatorIdentity.human(bodyOperator.trim(), bodyOperator.trim()));
        }
        return Optional.empty();
    }

    /** header 名大小写不敏感。 */
    private static String header(Map<String, String> headers, String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
