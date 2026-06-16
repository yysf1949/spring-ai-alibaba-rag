package io.github.yysf1949.rag.core.model;

/**
 * How to combine a user's permission tags with a chunk's permission tags.
 *
 * <p>Design spec §8.2 — default is {@code AND} (user must hold every tag on
 * the chunk). Override to {@code OR} for collaborative / wiki-style KBs where
 * any matching tag grants visibility.</p>
 */
public enum PermissionMode {
    /** User must hold every permission tag of the chunk (default, safer). */
    AND,
    /** User only needs at least one matching tag. */
    OR
}