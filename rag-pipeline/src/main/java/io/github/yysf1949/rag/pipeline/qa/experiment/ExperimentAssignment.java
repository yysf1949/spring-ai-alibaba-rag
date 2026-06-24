package io.github.yysf1949.rag.pipeline.qa.experiment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic per-user traffic splitter. Same (experiment, subject) tuple
 * always lands on the same variant until the experiment's variant list
 * <em>changes</em> — at which point the assignment can shift, but only
 * because the salt moved. This is what production A/B frameworks call
 * "consistent hashing for experiment bucketing".
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>{@code hash = sha256(experimentName + ":" + subjectId)}</li>
 *   <li>Take the first 8 bytes of {@code hash} as a {@code long} bucket
 *       in {@code [0, 2^64)}.</li>
 *   <li>Normalise to {@code [0, 1)} via {@code bucket / 2^64}.</li>
 *   <li>Walk variants in declaration order, accumulating normalised
 *       weights; first variant whose cumulative band contains the bucket
 *       wins.</li>
 * </ol>
 *
 * <h2>Why SHA-256?</h2>
 * The hash is <em>not</em> for security — it is to spread hash collisions
 * evenly across buckets. Java's {@code String.hashCode()} is deterministic
 * but exhibits clustering on small alphabets; {@code Arrays.hashCode} is
 * even worse. SHA-256 is the cheapest standard hash that produces uniform
 * buckets on userId strings of length 1–64.
 *
 * <p>Phase 39 / R14.</p>
 */
public final class ExperimentAssignment {

    private ExperimentAssignment() {}

    /**
     * Assign a {@link ExperimentVariant} to {@code subjectId}. Never returns null
     * when {@code experiment} has at least one variant.
     *
     * @param experiment the experiment definition
     * @param subjectId  opaque subject identifier (userId, sessionId, etc.)
     * @return the assigned variant
     */
    public static ExperimentVariant assign(Experiment experiment, String subjectId) {
        Objects.requireNonNull(experiment, "experiment");
        Objects.requireNonNull(subjectId, "subjectId");
        List<ExperimentVariant> variants = experiment.variants();
        if (variants.size() == 1) {
            return variants.get(0);
        }
        double total = experiment.totalWeight();
        if (total <= 0.0) {
            // All-zero weights — degenerate but defined: first variant wins.
            return variants.get(0);
        }
        double bucket = normalisedBucket(experiment.name(), subjectId);
        double cumulative = 0.0;
        for (ExperimentVariant v : variants) {
            cumulative += v.weight() / total;
            if (bucket < cumulative) {
                return v;
            }
        }
        // Floating-point edge case (bucket == 1.0): fall through to last variant.
        return variants.get(variants.size() - 1);
    }

    /** Visible for testing — returns the bucket in {@code [0, 1)}. */
    static double normalisedBucket(String experimentName, String subjectId) {
        long raw = bucketLong(experimentName, subjectId);
        // Long → [0, 1): unsigned divide. Long.MIN_VALUE handled by unsigned shift.
        return (raw >>> 11) / (double) (1L << 53);
    }

    private static long bucketLong(String experimentName, String subjectId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(experimentName.getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update(subjectId.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            // Take the first 8 bytes as a long.
            long result = 0L;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (digest[i] & 0xffL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec — should be impossible on any JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Hex digest of the bucket key — exposed for log correlation only. */
    public static String bucketKey(String experimentName, String subjectId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(experimentName.getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update(subjectId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}