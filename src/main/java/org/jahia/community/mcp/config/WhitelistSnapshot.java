package org.jahia.community.mcp.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable view of the whitelist configuration, published atomically by
 * {@link McpConfigService}: the entries as configured ({@code raw}, shown in the admin UI), their
 * operation-qualified form ({@code normalized}, malformed entries dropped) and the deepest path
 * the gate has to extract to decide every entry ({@code maxDepth}).
 *
 * <p>An empty {@code raw} set is the read-only default: every query is allowed except those under {@code admin}, no mutation and
 * no subscription is.</p>
 */
public final class WhitelistSnapshot {

    /** The gate always extracts at least {@code <operation>.<field>}. */
    static final int MIN_DEPTH = 2;

    private final Set<String> raw;
    private final Set<String> normalized;
    private final int maxDepth;

    public WhitelistSnapshot(final Set<String> raw, final Set<String> normalized, final int maxDepth) {
        this.raw = Collections.unmodifiableSet(new LinkedHashSet<>(raw));
        this.normalized = Collections.unmodifiableSet(new LinkedHashSet<>(normalized));
        this.maxDepth = maxDepth;
    }

    /** Entries as configured (insertion order, unmodifiable). */
    public Set<String> raw() {
        return raw;
    }

    /** Operation-qualified well-formed entries ({@code query.jcr}, {@code mutation.jcr.deleteNode}). */
    public Set<String> normalized() {
        return normalized;
    }

    /** Deepest path (in segments, operation type included) the gate must extract. */
    public int maxDepth() {
        return maxDepth;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof WhitelistSnapshot
                && raw.equals(((WhitelistSnapshot) o).raw)
                && normalized.equals(((WhitelistSnapshot) o).normalized)
                && maxDepth == ((WhitelistSnapshot) o).maxDepth;
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, normalized, maxDepth);
    }

    /** Builds the snapshot of {@code rawEntries}, normalizing and measuring them once. */
    public static WhitelistSnapshot of(final Collection<String> rawEntries) {
        final Set<String> raw = new LinkedHashSet<>(rawEntries);
        final Set<String> normalized = WhitelistEntries.normalize(raw);
        int depth = MIN_DEPTH;
        for (final String entry : normalized) {
            depth = Math.max(depth, segmentCount(entry));
        }
        return new WhitelistSnapshot(raw, normalized, depth);
    }

    /** True when nothing is configured: queries only, never a mutation or a subscription. */
    public boolean isReadOnlyDefault() {
        return raw.isEmpty();
    }

    private static int segmentCount(final String entry) {
        int n = 1;
        for (int k = 0; k < entry.length(); k++) {
            if (entry.charAt(k) == '.') {
                n++;
            }
        }
        return n;
    }
}
