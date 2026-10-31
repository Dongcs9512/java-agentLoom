package io.github.dongcs9512.agentloom.server.gateway;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOperatorResolverTest {

    private OperatorResolver resolver(boolean trustHeaders) {
        return new DefaultOperatorResolver(trustHeaders, "X-Operator-Id", "X-Operator-Name");
    }

    @Test
    void usesBodyOperatorWhenHeadersUntrusted() {
        Optional<OperatorIdentity> identity = resolver(false).resolve(Map.of(), "张三");

        assertTrue(identity.isPresent());
        assertEquals("张三", identity.get().name());
        assertEquals("human:张三", identity.get().sessionValue());
    }

    @Test
    void headerWinsOverBodyWhenTrusted() {
        Optional<OperatorIdentity> identity = resolver(true)
                .resolve(Map.of("X-Operator-Id", "u1001", "X-Operator-Name", "张三"), "李四");

        assertEquals("u1001", identity.get().id());
        assertEquals("张三", identity.get().name(), "有网关身份时不能信请求体自报的名字");
    }

    @Test
    void bodyIsIgnoredWhenHeadersTrusted() {
        assertTrue(resolver(true).resolve(Map.of(), "李四").isEmpty(),
                "信任 header 时不能再用请求体兜底，否则谁都能自报身份");
    }

    @Test
    void bodyIsUsedWhenHeadersUntrusted() {
        assertEquals("李四", resolver(false).resolve(Map.of(), "李四").orElseThrow().name());
    }

    @Test
    void headerNameFallsBackToId() {
        Optional<OperatorIdentity> identity = resolver(true).resolve(Map.of("x-operator-id", "u1001"), null);

        assertEquals("u1001", identity.get().name(), "没给姓名就用 id 当展示名");
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        Optional<OperatorIdentity> identity = resolver(true)
                .resolve(Map.of("x-operator-id", "u1", "x-operator-name", "王五"), null);

        assertEquals("王五", identity.get().name());
    }

    @Test
    void emptyWhenNothingProvided() {
        assertTrue(resolver(true).resolve(Map.of(), null).isEmpty());
        assertTrue(resolver(false).resolve(null, "  ").isEmpty());
    }
}
