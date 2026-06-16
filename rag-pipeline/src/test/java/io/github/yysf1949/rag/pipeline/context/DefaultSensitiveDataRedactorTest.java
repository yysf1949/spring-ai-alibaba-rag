package io.github.yysf1949.rag.pipeline.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSensitiveDataRedactorTest {

    private final SensitiveDataRedactor redactor = new DefaultSensitiveDataRedactor();

    @Test
    void nullAndEmpty() {
        assertEquals("", redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }

    @Test
    void redactsChineseIdCard15() {
        // 15 digits — classic older ID format
        assertEquals("用户 ***ID-REDACTED*** 已注册",
                redactor.redact("用户 110101900000001 已注册"));
    }

    @Test
    void redactsChineseIdCard18() {
        // 18 digits — modern format with checksum
        assertEquals("身份证 ***ID-REDACTED***",
                redactor.redact("身份证 11010119900101001X"));
    }

    @Test
    void redactsMobilePhone() {
        assertEquals("联系 ***PHONE-REDACTED*** 处理",
                redactor.redact("联系 13800138000 处理"));
    }

    @Test
    void preservesNumbersTooShortForPhone() {
        // 1234567890 is 10 digits, doesn't match 1[3-9]\d{9}
        assertEquals("编号 1234567890",
                redactor.redact("编号 1234567890"));
    }

    @Test
    void preservesPhoneWithoutValidPrefix() {
        // 12000000000 — starts with 12, not 13-19, shouldn't match
        // But 11-digit sequence starting with 1 — actually regex is 1[3-9]\d{9},
        // so "12345678901" would match (12 is the prefix, but 12 doesn't match
        // [3-9], so no match).
        assertEquals("电话 12345678901",
                redactor.redact("电话 12345678901"));
    }

    @Test
    void redactsBankCard16() {
        // 16 digits — VISA/Mastercard length
        assertEquals("卡号 ***BANK-REDACTED***",
                redactor.redact("卡号 6222021234567890"));
    }

    @Test
    void redactsBankCard19() {
        // 19 digits — UnionPay length
        assertEquals("卡号 ***BANK-REDACTED***",
                redactor.redact("卡号 6222021234567890123"));
    }

    @Test
    void doesNotDoubleRedactPhoneAsBankCard() {
        // Mobile 13800138000 — 11 digits, won't trigger bank card (16-19).
        // The negative lookarounds in BANK_CARD also keep it clean.
        assertEquals("电话 ***PHONE-REDACTED***",
                redactor.redact("电话 13800138000"));
    }

    @Test
    void mixedContentRedactsAll() {
        String input = "用户 13800138000，身份证 11010119900101001X，卡号 6222021234567890";
        String output = redactor.redact(input);
        assertEquals("用户 ***PHONE-REDACTED***，身份证 ***ID-REDACTED***，卡号 ***BANK-REDACTED***",
                output);
    }

    @Test
    void plainTextUntouched() {
        String plain = "退款规则第七条：商品质量问题全额退款，运费一并退还。";
        assertEquals(plain, redactor.redact(plain));
    }
}
