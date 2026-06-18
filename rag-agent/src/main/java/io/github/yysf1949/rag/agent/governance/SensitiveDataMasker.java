package io.github.yysf1949.rag.agent.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏器 — 在 {@link ToolAuditBridge} 写 audit 前对请求/响应 JSON 做字段级 + 值级脱敏。
 *
 * <h2>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"</h2>
 * <p>Agent 在 audit/日志链路里会写入业务字段（含身份证/银行卡/手机号）。脱敏让审计
 * 仍可追溯行为，但不再含明文 PII，符合合规要求（GDPR / 个保法）。</p>
 *
 * <h2>两种脱敏路径</h2>
 * <ul>
 *   <li><b>字段名匹配</b> — 递归扫描 JSON 节点，遇到 key 命中敏感字段集合 → 对 value 做
 *       {@link #maskValue(String)}。</li>
 *   <li><b>值匹配</b> — value 是长字符串（如备注）时，正则匹配身份证/银行卡/手机号 → 替换。</li>
 * </ul>
 *
 * <h2>字段名集合</h2>
 * <ul>
 *   <li>身份证：{@code idCard} / {@code id_card} / {@code identityCard}</li>
 *   <li>银行卡：{@code bankCard} / {@code bank_card} / {@code cardNo} / {@code card_no}</li>
 *   <li>手机号：{@code phone} / {@code mobile}</li>
 *   <li>邮箱：{@code email}</li>
 *   <li>地址：{@code address} / {@code shippingAddress} / {@code shipping_address}</li>
 * </ul>
 *
 * <h2>不可变 + 线程安全</h2>
 * <p>本类无状态，单例注入即可。多线程并发安全。</p>
 */
public class SensitiveDataMasker {

    /** 字段名（lowercase 命中），命中即视为敏感。 */
    private static final List<String> SENSITIVE_KEYS = List.of(
            "idcard", "identitycard",
            "bankcard", "cardno",
            "phone", "mobile",
            "email",
            "address", "shippingaddress"
    );

    // 18 位身份证（最后一位可能 X）
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");
    // 16-19 位银行卡（按 4-4-4-4 段输出）
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{16,19}\\b");
    // 11 位手机号
    private static final Pattern MOBILE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    // 邮箱
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b");

    private final ObjectMapper mapper;

    public SensitiveDataMasker() {
        this(new ObjectMapper());
    }

    public SensitiveDataMasker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 脱敏 JSON 字符串 — 不可识别为 JSON 时，原样返回 + 仅做值级正则脱敏。
     */
    public String mask(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode masked = maskNode(root);
            return mapper.writeValueAsString(masked);
        } catch (JsonProcessingException e) {
            // 非 JSON，按纯文本做值级脱敏（fallback）
            return maskValue(json);
        }
    }

    /** 仅做值级脱敏（适用于"已经脱敏过但仍含值模式"的场景或非 JSON 文本）。 */
    public String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String s = value;
        s = MOBILE.matcher(s).replaceAll(m -> mobileMask(m.group()));
        s = ID_CARD.matcher(s).replaceAll(m -> idCardMask(m.group()));
        s = BANK_CARD.matcher(s).replaceAll(m -> bankCardMask(m.group()));
        s = EMAIL.matcher(s).replaceAll(m -> emailMask(m.group()));
        return s;
    }

    // ---- 内部：递归 JSON 节点 ----

    private JsonNode maskNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            // 先复制所有 entries
            var fields = obj.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (isSensitiveKey(key)) {
                    String text = value.isTextual() ? value.asText() : value.toString();
                    obj.set(key, TextNode.valueOf(maskValue(text)));
                } else {
                    obj.set(key, maskNode(value));
                }
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, maskNode(arr.get(i)));
            }
            return arr;
        }
        if (node.isTextual()) {
            String text = node.asText();
            String masked = maskValue(text);
            return masked.equals(text) ? node : TextNode.valueOf(masked);
        }
        return node;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        return SENSITIVE_KEYS.contains(key.toLowerCase());
    }

    // ---- 各类脱敏 ----

    /** 手机号 11 位 → 138****1234 */
    static String mobileMask(String s) {
        if (s == null || s.length() < 7) return s;
        return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
    }

    /** 身份证 18 位 → 3301**********1234 */
    static String idCardMask(String s) {
        if (s == null || s.length() < 8) return s;
        return s.substring(0, 4) + "**********" + s.substring(s.length() - 4);
    }

    /** 银行卡 16-19 位 → 6228 **** **** 1234 */
    static String bankCardMask(String s) {
        if (s == null || s.length() < 8) return s;
        String tail = s.substring(s.length() - 4);
        return s.substring(0, 4) + " **** **** " + tail;
    }

    /** 邮箱 → t***@example.com */
    static String emailMask(String s) {
        if (s == null) return s;
        int at = s.indexOf('@');
        if (at <= 1) return s;
        return s.charAt(0) + "***" + s.substring(at);
    }
}