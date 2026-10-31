package io.github.dongcs9512.agentloom.server.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关接入点的默认装配。接真实系统时在自定义配置类里定义同类型 Bean 即可覆盖。
 */
@Configuration
public class GatewayConfiguration {

    @Value("${agentloom.gateway.trust-operator-headers:false}")
    private boolean trustOperatorHeaders;

    @Value("${agentloom.gateway.operator-id-header:X-Operator-Id}")
    private String operatorIdHeader;

    @Value("${agentloom.gateway.operator-name-header:X-Operator-Name}")
    private String operatorNameHeader;

    @Value("${agentloom.gateway.require-operator:false}")
    private boolean requireOperator;

    @Bean
    @ConditionalOnMissingBean(OperatorResolver.class)
    public OperatorResolver operatorResolver() {
        return new DefaultOperatorResolver(trustOperatorHeaders, operatorIdHeader, operatorNameHeader);
    }

    /** 是否强制要求能解析出操作者身份；开启后未认证请求会被拒。 */
    @Bean
    public GatewaySettings gatewaySettings() {
        return new GatewaySettings(requireOperator);
    }
}
