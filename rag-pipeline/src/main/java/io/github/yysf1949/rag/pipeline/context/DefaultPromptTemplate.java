package io.github.yysf1949.rag.pipeline.context;

import io.github.yysf1949.rag.core.model.Chunk;

/**
 * Default prompt template — matches spec §13.11 "Prompt 模板 + 来源引用指令".
 *
 * <p>Header shape: {@code [N] 《title》> sectionPath (sourceUri)\n}
 * — uses Chinese book brackets around the title to match the article's
 * Chinese-language origin and to make citation markers visually distinct
 * for both the LLM and the UI consumer.</p>
 *
 * <p>Prompt envelope:
 * <pre>
 *   你是企业知识库助手。基于以下参考资料回答问题。如果答案不在参考资料中，请回答"不知道"，不要编造。
 *   在答案中用 [N] 标记引用来源，N 对应下方参考资料的编号。
 *
 *   【参考资料】
 *   [1] 《退款规则》> 运费条款 (https://docs.example.com/refund)
 *   用户已支付运费、商品质量问题、退款时是否退运费：根据平台规则，运费一并退还……
 *
 *   【问题】
 *   用户付了运费但商品质量问题退款，运费退吗？
 * </pre>
 */
public final class DefaultPromptTemplate implements PromptTemplate {

    @Override
    public String header(int marker, Chunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(marker).append("] 《");
        sb.append(safe(chunk.title())).append("》> ");
        sb.append(safe(chunk.sectionPath())).append(" (");
        sb.append(safe(chunk.sourceUri())).append(")\n");
        return sb.toString();
    }

    @Override
    public String render(String queryText, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是企业知识库助手。基于以下参考资料回答问题。");
        sb.append("如果答案不在参考资料中，请回答\"不知道\"，不要编造。");
        sb.append("在答案中用 [N] 标记引用来源，N 对应下方参考资料的编号。\n\n");
        sb.append("【参考资料】\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append("\n【问题】\n");
        sb.append(safe(queryText)).append('\n');
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
