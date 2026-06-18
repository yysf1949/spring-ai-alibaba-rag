package io.github.yysf1949.rag.agent.builtin;

import java.util.List;

/**
 * Phase 18 P0 — {@link KbSearchTool} 的入参 record, 拆成顶层类.
 *
 * <h2>为什么是顶层类 (而非内部 record)</h2>
 * <p>Phase 17 ship 后, {@code KbSearchTool} 内部 record {@code Request} 作为 kb_search 入参,
 * Spring AI 1.0.9 {@code FunctionToolCallback.call(json)} 内部用 {@code JsonParser.fromJson(json, Type)}
 * 反序列化时, 对内部 record (生成的 class 文件路径是 {@code KbSearchTool$Request}) 处理有 bug,
 * 把 JSON 反序列化成 {@code String} 而非 record, 后续强转抛 {@code ClassCastException}.</p>
 *
 * <p>修法: 把 Request/Response/Chunk 全部拆成 {@code io.github.yysf1949.rag.agent.builtin} 包下的
 * 顶层 record — Spring AI 1.0.9 JsonParser 对顶层 record 处理正常 (Phase 18 P0 验证).</p>
 *
 * <h2>字段说明 (Plan §2.3 不变)</h2>
 * <ul>
 *   <li>{@code tenantId} — 必填, 多租户硬墙 (LLM 从 ctx 注入)</li>
 *   <li>{@code kbId} — 必填, 知识库 ID</li>
 *   <li>{@code kbVersion} — 必填, -1 = 用最新版本 (tool 内部转 0)</li>
 *   <li>{@code query} — 必填, 原始查询文本</li>
 *   <li>{@code topK} — 选填, 默认 5, 范围 [1, 20]</li>
 *   <li>{@code userPermissionTags} — 选填, 默认 [], Phase 19 推 ctx → tag 注入</li>
 * </ul>
 */
public record KbSearchRequest(
        String tenantId,
        String kbId,
        long kbVersion,                  // -1 = latest (tool 内部转 0)
        String query,
        int topK,
        List<String> userPermissionTags
) {
    public KbSearchRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (userPermissionTags == null) {
            userPermissionTags = List.of();
        }
        if (topK <= 0) {
            topK = 5;
        } else if (topK > 20) {
            topK = 20;
        }
    }
}