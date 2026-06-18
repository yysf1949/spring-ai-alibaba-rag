package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感数据脱敏器测试 — 5 个用例对齐文章"脱敏 + 合规"要求。
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksIdCardInJsonField() {
        String input = "{\"name\":\"张三\",\"idCard\":\"330106199001011234\"}";
        String masked = masker.mask(input);
        assertThat(masked).contains("3301**********1234");
        assertThat(masked).doesNotContain("330106199001011234");
        assertThat(masked).contains("张三"); // 非敏感字段保留
    }

    @Test
    void masksBankCardInJsonField() {
        String input = "{\"orderId\":\"O1\",\"bankCard\":\"6222021234567890123\"}";
        String masked = masker.mask(input);
        assertThat(masked).contains("6222 **** **** 0123");
        assertThat(masked).doesNotContain("6222021234567890123");
    }

    @Test
    void masksMobileByFieldName() {
        String input = "{\"name\":\"李四\",\"phone\":\"13812345678\"}";
        String masked = masker.mask(input);
        assertThat(masked).contains("138****5678");
        assertThat(masked).doesNotContain("13812345678");
    }

    @Test
    void masksEmailByFieldName() {
        String input = "{\"userId\":\"u1\",\"email\":\"tester@example.com\"}";
        String masked = masker.mask(input);
        assertThat(masked).contains("t***@example.com");
        assertThat(masked).doesNotContain("tester@example.com");
    }

    @Test
    void masksValuePatternsInFreeText() {
        // 非 JSON / 备注字段：手机号出现在文本里也要脱敏
        String input = "{\"remark\":\"请致电 13812345678 处理\"}";
        String masked = masker.mask(input);
        assertThat(masked).contains("138****5678");
    }

    @Test
    void nonSensitiveFieldPassesThrough() {
        String input = "{\"orderId\":\"O123\",\"amount\":1000,\"status\":\"PAID\"}";
        String masked = masker.mask(input);
        assertThat(masked).isEqualTo(input);
    }

    @Test
    void nestedObjectRecursivelyMasked() {
        String input = "{\"user\":{\"name\":\"张三\",\"phone\":\"13987654321\"}}";
        String masked = masker.mask(input);
        assertThat(masked).contains("139****4321");
        assertThat(masked).contains("张三");
    }

    @Test
    void arrayElementsMasked() {
        String input = "{\"contacts\":[{\"phone\":\"13811112222\"},{\"phone\":\"13933334444\"}]}";
        String masked = masker.mask(input);
        assertThat(masked).contains("138****2222");
        assertThat(masked).contains("139****4444");
    }
}