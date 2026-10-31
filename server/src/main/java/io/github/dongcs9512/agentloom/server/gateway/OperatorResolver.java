package io.github.dongcs9512.agentloom.server.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * 操作者身份解析 SPI —— 接入真实坐席系统/统一认证网关的扩展点。
 *
 * <p>框架默认只信任请求体里传的 operator（方便联调），生产上应实现本接口，
 * 从网关透传的可信 header 或一次鉴权调用来确定身份，而不是让调用方自己声明。
 *
 * <p>示例：接入公司网关后，网关校验 JWT 并注入 {@code X-Operator-Id} / {@code X-Operator-Name}，
 * 实现方在这里读取即可；框架不关心 token 怎么验。
 */
public interface OperatorResolver {

    /**
     * @param headers          请求头（网关注入的可信信息）
     * @param bodyOperator     请求体里声明的 operator，联调时用；生产可为 null
     */
    Optional<OperatorIdentity> resolve(Map<String, String> headers, String bodyOperator);
}
