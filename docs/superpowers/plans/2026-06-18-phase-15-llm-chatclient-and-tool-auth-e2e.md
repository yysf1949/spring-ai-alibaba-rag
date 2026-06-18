# Phase 15 — LLM ChatClient 接入 + AuthorizationContext 端到端联调

**日期**: 2026-06-18
**作者**: 周礼攀 + Hermes Agent
**项目**: `spring-ai-alibaba-rag` (feature/agent-action-layer 分支)
**前置**: Phase 14 ship commit `12961d2`(209/209 PASS,P0 三个业务工具 + P1 动态授权)

---

## 1. 背景与目标

### 1.1 已 ship 的能力(Phase 14 终态)

| 模块 | 文件 | 状态 |
|---|---|---|
| 10 个 `@ToolSpec` Tool(订单/退款/优惠/票务/支付渠道/退款规则/退款计算/通知/会员权益...) | `rag-agent/.../action/builtin/*.java` | ✅ |
| `SpringAiAgentAdapter.getFunctionCallbacks(AuthorizationContext)` | `orchestration/SpringAiAgentAdapter.java` | ✅ |
| `StageAwareToolAuthorizer`(3 阶段授权:L1 / L1+L2 / L1-L3) | `governance/StageAwareToolAuthorizer.java` | ✅ |
| `AuthorizationContext`(record + 3 工厂:`permissive`/`awaitingConfirmation`/`confirmed`) | `governance/AuthorizationContext.java` | ✅ |
| `RiskGate`(runtime 风险门控) | `governance/RiskGate.java` | ✅ |
| 工具执行结果脱敏 `SensitiveDataMasker` | `governance/SensitiveDataMasker.java` | ✅ |

### 1.2 缺失(Phase 15 要补)

| 缺口 | 影响 |
|---|---|
| ❌ **Spring AI ChatClient 0 引用** — rag-agent/pom.xml 没引 `spring-ai-openai-spring-boot-starter` | 10 个工具注册好但**没有 LLM 在调** |
| ❌ **DeepSeek 集成 0 引用** — OpenAI-compatible 协议未配置 | 真实流量测试无入口 |
| ❌ **P1 `AuthorizationContext` 与 ChatClient 串通未验证** | Phase 14 ship 时只有 mock 测试,没真实 LLM 看到 ctx filter 效果 |
| ❌ **`AgentController` HTTP 入口未挂 ChatClient** | 真实用户无法通过 HTTP 触发 LLM 对话 |

### 1.3 Phase 15 目标(本计划范围)

**目标 1**: 接入 Spring AI 1.0.9 ChatClient,配置 OpenAI-compatible DeepSeek backend
**目标 2**: 实现 `ChatClientService`,**真实**用 ChatClient 调 DeepSeek + Function Calling
**目标 3**: 在 `ChatClientService` 内强制串通 Phase 14 的 `AuthorizationContext` 过滤
**目标 4**: 新增 1 个真实 E2E 集成测试(`@EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")`),让用户能跑真实流量验证 LLM 看到的 tool 数量受 ctx 控

**不在范围**(明确不做):
- ❌ **不**改 `AgentController` HTTP 入口(走 `agentService.execute(ar)` 链路,与 ChatClient 平行,**不改已 ship 链路**)
- ❌ **不**改 `DefaultAgentLoop` / `SpringAiAgentAdapter` 既有行为(只读)
- ❌ **不**新增 SSE / 流式输出(Phase 15 用 `call()` blocking 同步接口,后续 Phase 16 再加 stream)
- ❌ **不**接 RAG 检索(Phase 15 仅测工具调用,知识库问答推迟)

---

## 2. 技术方案

### 2.1 依赖:加 `spring-ai-openai-spring-boot-starter`

Spring AI 1.0.9 BOM 已引(`pom.xml:48`),OpenAI starter 在 BOM 内:

```xml
<!-- rag-agent/pom.xml 新增 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

**OpenAI starter 兼容 DeepSeek**:
- DeepSeek 走 OpenAI-compatible 协议(`/v1/chat/completions`)
- base-url 改 `https://api.deepseek.com` 即可
- model 改 `deepseek-chat`(实测会自动路由到 `deepseek-v4-flash`)

### 2.2 配置文件:`application-deepseek.yml`(新 profile)

```yaml
# rag-agent/src/main/resources/application-deepseek.yml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}            # 环境变量注入, 不进 git
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
```

**Key 安全契约**(参照既有 `SILICONFLOW_API_KEY` 模式):
- ❌ `application.yml` 不写 key
- ❌ 不进任何 commit
- ❌ 不进 `MEMORY.md` / `USER.md`
- ✅ 仅环境变量 `DEEPSEEK_API_KEY`(test 时 surefire `environmentVariables` 注入;prod 时 K8s secret)
- ✅ `.gitignore` 已隐含覆盖(\*.yml 本身不存 key,无需新增 ignore)

### 2.3 ChatClient Bean 配置:`DeepSeekChatClientConfig`

```java
// rag-agent/.../orchestration/DeepSeekChatClientConfig.java
@Configuration
@Profile("deepseek")
@ConditionalOnProperty(prefix = "spring.ai.openai", name = "api-key")
public class DeepSeekChatClientConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
```

**设计要点**:
- `@Profile("deepseek")` — 默认 profile 不激活(避免 dev/test 启动报缺 key)
- `@ConditionalOnProperty(api-key)` — 即使 profile 激活,缺 key 也不创建 Bean(防御)
- **`@Bean ChatClient` 复用 Spring AI 自动配置的 `ChatClient.Builder`**(`spring-ai-openai-spring-boot-starter` 注入)

### 2.4 `ChatClientService`:核心服务

**位置**: `rag-agent/.../orchestration/ChatClientService.java`

**职责**:接收用户消息 + AuthorizationContext,**用 ctx 过滤后的 Function Callbacks**调 DeepSeek,返回 LLM 文本响应。

```java
@Service
@ConditionalOnBean(ChatClient.class)   // 没 ChatClient Bean 时不创建 (test 友好)
public class ChatClientService {

    private final ChatClient chatClient;
    private final SpringAiAgentAdapter adapter;
    private final ToolAuthorizer toolAuthorizer;

    public ChatClientService(ChatClient chatClient,
                             SpringAiAgentAdapter adapter,
                             ToolAuthorizer toolAuthorizer) {
        this.chatClient = chatClient;
        this.adapter = adapter;
        this.toolAuthorizer = toolAuthorizer;
    }

    /**
     * 对话入口 — 让 LLM 看到 ctx 授权范围内的工具, 自然调用.
     */
    public String chat(String userMessage, AuthorizationContext ctx) {
        AuthorizationContext effective = (ctx != null) ? ctx : AuthorizationContext.permissive();

        FunctionToolCallback[] callbacks = adapter.getFunctionCallbacks(effective);

        ChatResponse response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .toolCallbacks(callbacks)
                .call()
                .chatResponse();

        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AssistantMessage::getText)
                .orElse("");
    }

    private static final String SYSTEM_PROMPT = """
            你是企业 AI 客服助手。根据用户问题, 选择最合适的工具调用.
            工具按风险级分层: L1=只读, L2=可撤回, L3=业务态变更.
            只调用当前阶段授权范围内的工具.
            """;
}
```

**关键决策**:
- `@ConditionalOnBean(ChatClient.class)` — 测试可注入 mock ChatClient,生产需激活 `deepseek` profile 才有真实 Bean
- 系统 prompt 故意简短 — 让 LLM 自然选 tool,严格风控交给 `AuthorizationContext` 过滤
- **不**走 SSE 流式(`call()` blocking)— Phase 16 再加 `stream()`

### 2.5 真实 E2E 测试:`ChatClientServiceE2ETest`

```java
// rag-agent/.../orchestration/ChatClientServiceE2ETest.java
@SpringBootTest(
    classes = AgentApplication.class,
    properties = {
        "spring.profiles.active=deepseek",
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ChatClientServiceE2ETest {
    // 单测试: 让 LLM 自然选 tool, 验证 ctx 过滤生效 (1 个用例, 用户跑真实流量)
}
```

**为什么只 1 个用例 + @EnabledIfEnvironmentVariable**:
- 真实 LLM 不可重复(同一 prompt 不同 LLM 可能选不同 tool)
- CI 上无 key 时 `@EnabledIf` 自动 skip(0 fail)
- 用户本地有 key 时跑 1 次即可看效果

### 2.6 Mock 测试:`ChatClientServiceMockTest`(3 个用例)

| # | 用例 | 验证点 |
|---|---|---|
| 1 | `chat_withConfirmedCtx_callsLLMWithFilteredCallbacks` | ctx=confirmed → ChatClient 收到的 callbacks 全为 L1-L3(无 L4) |
| 2 | `chat_withAwaitingCtx_filtersOutL3Tools` | ctx=awaitingConfirmation → callbacks 只有 L1+L2(无 L3 工具) |
| 3 | `chat_withNullCtx_usesPermissiveFallback` | ctx=null → 退化到 `AuthorizationContext.permissive()`,callbacks 含 L1-L3 |

---

## 3. 范围裁剪(明确不动)

| 既有模块 | 为什么不动 |
|---|---|
| `AgentController` | 走 `agentService.execute(ar)` 旧链路,与 ChatClient 是**平行入口**,不混 |
| `DefaultAgentLoop` | Phase 13a/13b 已 ship 改过,Phase 15 不再叠加改动 |
| `SpringAiAgentAdapter.getFunctionCallbacks(AuthorizationContext)` | Phase 14 ship,**只读**,被 ChatClientService **复用** |
| `RiskGate` / `SensitiveDataMasker` / `StageAwareToolAuthorizer` | Phase 13a/14 ship,**只读**,由 ChatClientService 间接通过 adapter 串通 |

**理由**:Phase 15 是**并行新入口**(LLM 对话入口),不是改造既有链路。surgical 原则。

---

## 4. 4 Task 分布

| # | 任务 | 文件 | 测试增量 | commit |
|---|---|---|---|---|
| T1 | `spring-ai-openai` 依赖 + `application-deepseek.yml` + `DeepSeekChatClientConfig` | pom.xml + yml + 1 java | +0 | `feat(agent): Phase 15 Task 1 DeepSeek ChatClient config` |
| T2 | `ChatClientService` + `ChatClientServiceMockTest` (3 用例) | 1 service + 1 test | +3 | `feat(agent): Phase 15 Task 2 ChatClientService with ctx filter` |
| T3 | `ChatClientServiceE2ETest` (1 真实 API 用例) | 1 test | +1 | `test(agent): Phase 15 Task 3 real DeepSeek E2E` |
| T4 | docs/evolution.md 同步 Phase 15 章节 + push + git ls-remote 验证 | docs | +0 | `docs: Phase 15 增量` |

**测试基线**: 209 → 213(计划 +4,实际增量)

---

## 5. 不做边界(Phase 15 不做)

| 不做项 | 推迟到 | 原因 |
|---|---|---|
| SSE 流式响应 | Phase 16 | 阻塞 `call()` 已够验证,流式是 UX 增强 |
| AgentController 挂 ChatClient | Phase 16 | Phase 15 是**新平行入口**,HTTP 串通有架构决策 |
| 多轮对话历史(session memory) | Phase 16 | `ChatClient` 1.0.9 `prompt()` 是单次,多轮需 `MessageChatMemoryAdvisor` |
| RAG 检索问答 | Phase 16 | rag-pipeline 模块已有,但与工具调用混搭是设计决策 |
| ChatClient OpenAI 默认 profile 切换(纯 OpenAI key) | Phase 17 | Phase 15 仅 DeepSeek,后续可加多 backend |

---

## 6. 风险点

| 风险 | 缓解 |
|---|---|
| DeepSeek 模型路由不稳定(实测返回 `deepseek-v4-flash`,文档说 `deepseek-chat`) | E2E 测试不强依赖具体 model,**仅验证 chat 返回非空** |
| API key 泄露进 git | ✅ yml 只写 `${DEEPSEEK_API_KEY}` env 占位符 |
| ChatClient 启动缺 key 报错 | ✅ `@ConditionalOnBean(ChatClient.class)` + `@Profile("deepseek")` 隔离 |
| 真实 API 测试 CI 0 fail 风险 | ✅ `@EnabledIfEnvironmentVariable` 无 key 自动 skip |
| `ChatClientService` mock 测试的 callback 数量断言 | 强绑 ToolRegistry mock 数据(L1=3 个,L2=2 个,L3=2 个,总数 7),断言具体数量 |

---

## 7. 验证清单(每 Task 完成必跑)

| 检查 | 命令 |
|---|---|
| Memory ritual: HEAD = 远端 MATCH | `git ls-remote origin feature/agent-action-layer` |
| 单 Task 编译过 | `mvn -pl rag-agent test-compile -q -DskipTests` |
| 单 Task 测试过 | `mvn -pl rag-agent test -q -Dtest='<NewTest>'` |
| 全仓库测试过 | `mvn clean test -q -pl rag-agent -am` |
| API key 没进 git | `git diff --cached` + `git status --porcelain` 全 grep `sk-` |
| 真实 API 跑通 | `DEEPSEEK_API_KEY=*** mvn -pl rag-agent test -q -Dtest='ChatClientServiceE2ETest'` |
| 远端 push + MATCH | `git push origin feature/agent-action-layer && git ls-remote origin feature/agent-action-layer` |

---

## 8. 拍板决策记录(执行前填)

| 决策点 | 我的方案 | 你 veto? |
|---|---|---|
| **API key 注入** | 环境变量 `${DEEPSEEK_API_KEY}` 注入 yml,沿用 `SILICONFLOW_API_KEY` 模式 | ☐ |
| **默认 profile** | `@Profile("deepseek")` 隔离,默认 dev/test 不启 ChatClient | ☐ |
| **不做范围** | 不改 Controller/Loop/Adapter,只新增 ChatClientService 并行入口 | ☐ |
| **测试粒度** | 3 mock + 1 真实 E2E(无 key 时自动 skip) | ☐ |
| **流式 / 多轮 / RAG** | 推 Phase 16,Phase 15 只做基础 call | ☐ |

---

## 9. 实施顺序(等用户拍板后执行)

1. 写 todo(T1-T4 + 验证)
2. T1: pom.xml + yml + ChatClient config + mvn test-compile 验证
3. T2: ChatClientService + 3 mock tests + 全仓库回归 + commit
4. T3: E2E test + 真实 DeepSeek key 跑通 + commit
5. T4: docs/evolution.md 同步 + push + git ls-remote MATCH
6. Obsidian 归档 plan + evolution.md

**禁止**:
- ❌ 不进 git 的文件:`application-deepseek.yml` 不写 key(只占位符)
- ❌ subagent 派单 — Phase 14 验证过本项目 subagent false green 率高
- ❌ 任何"已完成"声明前必跑 `git ls-remote` + `mvn test`