package io.github.yysf1949.rag.agent.api;

/**
 * 公开 API — 编排层的门面。HTTP / GraphQL / gRPC 层都通过这个接口调 Agent。
 */
public interface AgentService {
    AgentResponse execute(AgentRequest request);
}