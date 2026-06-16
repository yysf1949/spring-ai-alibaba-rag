package io.github.yysf1949.rag.pipeline.context;

import java.util.regex.Pattern;

/**
 * Spec §15.3 regex redactor.
 *
 * <p><b>Pattern order matters.</b> We run the patterns in this sequence:</p>
 * <ol>
 *   <li><b>Bank card</b> ({@code \d{16,19}}) — must run FIRST. The
 *       15-18-digit ID card pattern would otherwise eat the leading 18
 *       digits of any bank card number and leave the trailing 1-2 digits
 *       in the output. Bank-card lookahead/lookbehind keep phone-shaped
 *       numbers out of this pattern.</li>
 *   <li><b>ID card</b> ({@code \d{15}} OR {@code \d{17}[0-9Xx]}) — second.
 *       Chinese ID has either 15 digits (legacy) or 18 chars with the
 *       last being a digit or X. We use lookarounds so it doesn't
 *       double-match what's left after the bank-card sweep.</li>
 *   <li><b>Mobile phone</b> ({@code 1[3-9]\d{9}}) — last. The
 *       11-digit mobile pattern won't trigger on 16-19-digit bank cards
 *       because of the negative lookbehind (no digit immediately before),
 *       and won't trigger on 15-18-digit IDs that survive the ID pass
 *       for the same reason.</li>
 * </ol>
 *
 * <p>Replacement tags are stable strings so downstream log / cache code
 * can detect a redaction without parsing the original shape.</p>
 */
public final class DefaultSensitiveDataRedactor implements SensitiveDataRedactor {

    /** Chinese ID: 15 digits (legacy) or 17 digits + [0-9Xx] checksum (modern).
     *  MUST run first — its 18-char shape is a strict superset of any
     *  bank-card pattern that also accepts 18 digits, so we'd lose the
     *  X/x checksum if the bank pass ran before it. */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(?:\\d{15}|\\d{17}[0-9Xx])(?!\\d)");
    /** 16-19 digits not flanked by more digits — runs after ID so phone-shaped
     *  numbers don't trigger, and the ID checksum character (X/x) won't break it. */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    /** Mobile 1[3-9]\d{9}, not flanked by digits. */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final String ID_TAG = "***ID-REDACTED***";
    private static final String PHONE_TAG = "***PHONE-REDACTED***";
    private static final String BANK_TAG = "***BANK-REDACTED***";

    @Override
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        // Order: ID (handles X checksum) -> BANK (16-19 pure digits) -> MOBILE.
        // ID's strict 15 or 17+X shape means it won't accidentally eat a
        // 16-digit bank card (different length pattern), and the bank pass's
        // lookarounds keep it out of phone-shaped numbers.
        String s = ID_CARD.matcher(text).replaceAll(ID_TAG);
        s = BANK_CARD.matcher(s).replaceAll(BANK_TAG);
        s = MOBILE.matcher(s).replaceAll(PHONE_TAG);
        return s;
    }
}
