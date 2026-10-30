package com.aia.core.session;

import java.util.Optional;

/**
 * 场景路由：根据用户说的内容（以及已收集的槽位）判断该交给哪个专业 agent。
 * 返回空表示判断不出，由调用方决定是追问还是走默认 agent。
 */
public interface ScenarioRouter {

    Optional<String> route(String text, Session session);
}
