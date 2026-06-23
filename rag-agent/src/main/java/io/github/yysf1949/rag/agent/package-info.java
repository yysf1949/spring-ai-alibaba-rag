/**
 * rag-agent — Agent Action Layer (Phase 9).
 *
 * <p>把企业后端 Service 改造成 AI Agent 可安全调用的工具集。
 * 3 层架构（编排 / 动作 / 治理），对齐「路条编程」AI 客服文章方法论。</p>
 *
 * <h2>模块分层</h2>
 * <ul>
 *   <li><b>orchestration</b> — 意图理解 + 工具选择 + 调用循环，集成 Spring AI
 *       {@code FunctionCallingCallback}。未来升级 2.0 时只改
 *       {@code SpringAiAgentAdapter}。</li>
 *   <li><b>action</b> — 工具注册中心（{@code @ToolSpec} + {@code ToolRegistry}），
 *       4 级风险分级（{@code RiskLevel} L1-L4）。</li>
 *   <li><b>governance</b> — 身份传递 + 幂等键校验 + 风险门控 + 审计钩子。
 *       审计走 {@code rag-core} 已有的 {@code LlmAuditHook} 桥接。</li>
 *   <li><b>builtin</b> — 内置工具（demo 用）：{@code KbSearchTool}（包装
 *       {@code QAService}，L1 只读）+ {@code TicketTool}（创建提醒工单，L2）。</li>
 * </ul>
 *
 * <h2>Tool Calling 升级路径</h2>
 * <p>本模块刻意不直接使用 {@code @Tool} 注解（Spring AI 2.0），仅使用
 * {@code FunctionCallback} 接口（1.0.9），业务侧只依赖
 * {@code ToolDescriptor} 抽象层。升级 2.0 时改
 * {@code SpringAiAgentAdapter} 一个文件即可。</p>
 *
 * <h2>参考</h2>
 * <ul>
 *   <li>「路条编程」AI 客服文章（2026-06-17）</li>
 *   <li>本项目设计 spec §22 (Agent Action Layer)</li>
 *   <li>设计原则 12 条 §13（工具风险分级，Phase 9 新增）</li>
 * </ul>
 */
package io.github.yysf1949.rag.agent;
