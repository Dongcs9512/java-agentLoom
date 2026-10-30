package io.github.dongcs9512.agentloom.core.session;

/**
 * 判断一次用户输入该走哪条路：固定话术、纯对话模型，还是带知识库检索。
 */
public interface RetrievalRouter {

    RouteResult route(String userMessage, Session session);
}
