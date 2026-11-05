package org.jahia.community.mcp.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Syntax of whitelist entries.
 *
 * <p>User-facing form: {@code query:<path>} or {@code mutation:<path>}, where {@code <path>} is a
 * dot-separated GraphQL field path (e.g. {@code mutation:jcr.deleteNode}). A legacy entry without
 * a prefix (e.g. {@code jcr}) is accepted and treated as <b>query-only</b>: mutations must always be
 * allowed explicitly.</p>
 *
 * <p>Internal form: the operation type is the first path segment ({@code mutation.jcr.deleteNode}),
 * matching the paths {@code org.jahia.community.mcp.access.GraphQLWhitelistGate} extracts from a document. Malformed entries and
 * other operation types (e.g. {@code subscription:}) normalize to nothing — they grant no access.</p>
 */
public final class WhitelistEntries {

    static final String QUERY = "query";
    static final String MUTATION = "mutation";
    private static final String PREFIX_SEPARATOR = ":";
    /** Internal path of the namespace that must never be reachable through the data plane. */
    public static final String NEVER_ALLOWED = "mutation.communityMcp";
    // Possessive quantifiers: no backtracking, linear on any input (S5998).
    private static final String GRAPHQL_NAME = "[_A-Za-z]\\w*+";
    private static final Pattern FIELD_PATH = Pattern.compile(GRAPHQL_NAME + "(?:\\." + GRAPHQL_NAME + ")*+");

    private WhitelistEntries() {
    }

    /**
     * True if {@code entry} is well-formed but could never take effect because the gate always
     * denies it ({@code mutation:communityMcp...}); such an entry is refused when saving.
     */
    public static boolean isNeverAllowedEntry(final String entry) {
        return isNeverAllowed(toInternal(entry));
    }

    /** True if {@code entry} is a well-formed prefixed or legacy entry. */
    public static boolean isValid(final String entry) {
        return toInternal(entry) != null;
    }

    /** Internal (operation-qualified) paths of every well-formed entry; malformed ones are dropped. */
    public static Set<String> normalize(final Set<String> entries) {
        final Set<String> normalized = new LinkedHashSet<>();
        for (final String entry : entries) {
            final String internal = toInternal(entry);
            if (internal != null) {
                normalized.add(internal);
            }
        }
        return normalized;
    }

    /** Well-formed entries written without an operation prefix (treated as query-only). */
    public static Set<String> legacyEntries(final Set<String> entries) {
        final Set<String> legacy = new LinkedHashSet<>();
        for (final String entry : entries) {
            if (entry != null && !entry.contains(PREFIX_SEPARATOR) && FIELD_PATH.matcher(entry).matches()) {
                legacy.add(entry);
            }
        }
        return legacy;
    }

    /**
     * True if the internal {@code path} is, or lies under, the {@code communityMcp} mutation
     * namespace: the data plane must not rewrite its own guard, whatever the whitelist says.
     */
    public static boolean isNeverAllowed(final String path) {
        return path != null && (path.equals(NEVER_ALLOWED) || path.startsWith(NEVER_ALLOWED + "."));
    }

    /** User-facing form of an internal path: {@code mutation.jcr.x} → {@code mutation:jcr.x}. */
    public static String display(final String internalPath) {
        final int dot = internalPath.indexOf('.');
        return dot < 0 ? internalPath : internalPath.substring(0, dot) + PREFIX_SEPARATOR + internalPath.substring(dot + 1);
    }

    private static String toInternal(final String entry) {
        if (entry == null) {
            return null;
        }
        final int colon = entry.indexOf(PREFIX_SEPARATOR);
        final String operation = colon < 0 ? QUERY : entry.substring(0, colon).toLowerCase(Locale.ROOT);
        final String path = colon < 0 ? entry : entry.substring(colon + 1);
        if (!(QUERY.equals(operation) || MUTATION.equals(operation)) || !FIELD_PATH.matcher(path).matches()) {
            return null;
        }
        return operation + "." + path;
    }
}
