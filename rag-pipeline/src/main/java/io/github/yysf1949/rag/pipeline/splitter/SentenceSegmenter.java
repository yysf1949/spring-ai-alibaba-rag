package io.github.yysf1949.rag.pipeline.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal helper that breaks a block of text into atomic, splitter-respected
 * segments.
 *
 * <p>Three segment classes are recognised (in order):</p>
 * <ol>
 *   <li><b>Code blocks</b> — fenced with triple backticks ({@code ```}) or wrapped
 *       in {@code <pre>...</pre>} HTML. These are returned as a SINGLE
 *       segment, regardless of length, per spec §6.2
 *       ("大表格/代码块整体保留不切").</li>
 *   <li><b>Sentence groups</b> — runs of text terminated by Chinese or
 *       English sentence-ending punctuation ({@code 。！？!?.\n}). Each match
 *       is one segment, and the trailing punctuation is preserved.</li>
 *   <li><b>Trailing residue</b> — any characters after the last
 *       sentence terminator. Returned as a final (possibly empty) segment.</li>
 * </ol>
 *
 * <p>This class is package-private — callers should depend on
 * {@code ChunkSplitter} instead. Kept here so the splitter's main loop
 * stays readable.</p>
 */
final class SentenceSegmenter {

    /**
     * Code block pattern. Group 1 = triple-backtick fenced (may include a
     * language tag); group 2 = {@code <pre>...</pre>} HTML. We can't merge
     * them into a single alternation because the language-tag capture would
     * pollute group numbering — easier to use two alternation branches and
     * trim the leading/trailing fences via the loop below.
     */
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```[\\s\\S]*?```" +   // triple-backtick fence (multi-line, lazy)
            "|<pre>\\n?[\\s\\S]*?\\n?</pre>",  // HTML pre block
            Pattern.MULTILINE);

    /**
     * Sentence terminator — keeps the punctuation as part of the segment.
     * Covers CJK full stop, exclamation, question, and ASCII . ! ? plus
     * a line break (so that line-broken sentences stay glued).
     */
    private static final Pattern SENTENCE_BREAK = Pattern.compile(
            "[^。！？!?\\n]*[。！？!?\\n]+|[^。！？!?\\n]+$");

    private SentenceSegmenter() {
    }

    /**
     * @return segments in source order, with each code block preserved intact.
     *         Empty / whitespace-only input yields an empty list.
     */
    static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        // First pass: walk through code blocks, splitting the rest into sentences.
        int cursor = 0;
        Matcher code = CODE_BLOCK.matcher(text);
        while (code.find()) {
            int codeStart = code.start();
            int codeEnd = code.end();
            if (codeStart > cursor) {
                splitSentences(text, cursor, codeStart, out);
            }
            out.add(text.substring(codeStart, codeEnd));
            cursor = codeEnd;
        }
        if (cursor < text.length()) {
            splitSentences(text, cursor, text.length(), out);
        }
        return out;
    }

    private static void splitSentences(String text, int from, int to, List<String> sink) {
        String region = text.substring(from, to);
        Matcher m = SENTENCE_BREAK.matcher(region);
        while (m.find()) {
            String seg = m.group();
            if (seg != null && !seg.isBlank()) {
                sink.add(seg);
            }
        }
    }
}
