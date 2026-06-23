# 构建与测试指南

## 本地构建

```bash
mvn clean compile
```

## 运行测试

```bash
mvn test -pl rag-agent
```

## 测试报告位置

```
rag-agent/target/surefire-reports/
```

Surefire 会在该目录下生成 XML 和 TXT 格式的测试报告，可被 GitLab CI、Jenkins 等工具解析展示。

## CI 配置说明

项目使用 `.gitlab-ci.yml` 进行持续集成，定义了以下阶段（stages）：

| Stage   | Job         | 说明                          |
|---------|-------------|-------------------------------|
| build   | `build`     | 编译项目（`mvn compile -q`）   |
| test    | `unit-test` | 运行 rag-agent 模块的单元测试  |

**CI 关键配置：**

- **JAVA_VERSION**: 构建使用的 Java 版本
- **MAVEN_OPTS**: 使用本地 `.m2/repository` 缓存加速构建
- **artifacts**: 测试报告保留 7 天供后续查阅

> 后续可扩展 `integration-test`、`e2e-test`、`coverage` 等 stage，配合 CI 自动化验证 Agent 行为是否符合预期。

## 项目结构概览

```
spring-ai-alibaba-rag/
├── pom.xml                  # 父 POM（Spring Boot 3.3 + Spring AI Alibaba）
├── .gitlab-ci.yml           # GitLab CI 配置
├── rag-agent/               # Agent 核心模块（tools、orchestration、governance）
├── rag-core/                # 核心抽象与共享模型
├── rag-embedding/           # Embedding 适配层
├── rag-redis/               # Redis Stack 集成（向量检索、版本管理）
├── rag-pipeline/            # 数据摄入管道（ingest pipeline）
├── rag-app/                 # 应用入口（REST API、Web 控制器）
└── rag-test/                # 测试辅助工具与共享 fixture
```

## 测试覆盖范围

### 按类型统计（rag-agent 模块）

| 测试类型       | 测试类数量 | 具体测试类 |
|---------------|-----------|-----------|
| 单元测试（Unit）| 14        | PriceProtectionToolTest, RefundToolTest, CouponToolTest, OrderToolTest, AllNewToolsTest, SatisfactionSurveyToolTest, AfterServiceToolTest, RefundToolRuleIntegrationTest, ConversationSummaryToolTest, AgentMetricsEnhancedTest, AuditLoggerTest, ConversationAwareToolPolicyTest, MembershipAwareToolPolicyTest, ConfirmationServiceTest |
| 治理测试（Governance）| 3 | StageAwareToolAuthorizerTest, DefaultRiskGateTest, IdempotencyStressTest |
| 服务层测试（Service）| 2 | OrderApplicationServiceTest, RefundApplicationServiceTest |
| Channel 测试 | 4 | EmailChannelAdapterTest, EmailMessageParserTest, WeChatChannelAdapterTest, WeChatMessageParserTest |
| 存储层测试（Store）| 4 | H2AfterServiceAuditRepositoryTest, H2SatisfactionSurveyRepositoryTest, H2InventoryRepositoryTest, H2ComplaintRepositoryTest |
| 编排测试（Orchestration）| 8 | DefaultAgentLoopTest, DefaultAgentLoopHandoffTest, SpringAiAgentAdapterTest, SpringAiAgentAdapterDynamicAuthTest, ChatClientServiceMockTest, ChatClientServiceMultiTurnMockTest, ChatClientServiceMultiTurnContextTest, KbSearchDeserializeRootCauseTest |
| 集成测试（Integration）| 2 | AgentToolStoreIntegrationTest, CustomerServiceScenarioTest |
| E2E 测试 | 4 | Phase10EndToEndTest, AgentEndToEndTest, ObservabilityE2ETest, DocumentVersionRollbackE2ETest |
| Web 测试 | 3 | AgentControllerSseStreamingTest, DocumentVersionControllerTest, ChatClientServiceE2ETest, ChatClientServiceStreamE2ETest, ChatClientServiceKbSearchE2ETest |
| **合计** | **482 个测试用例**（50+ 测试类），0 failures, 1 skipped | |

> 所有测试均为纯内存模式（InMemory store + Mockito mock），无需外部依赖即可运行。
