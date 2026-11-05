package org.jahia.community.mcp.access;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import org.jahia.community.mcp.LogSupport;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistEntries;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Dot-path whitelist gate applied to every GraphQL document before it is executed by the
 * {@code community.graphql.execute} tool.
 *
 * <p>Entries name an operation type and a dot-separated path prefix: {@code query:admin} covers
 * every query under {@code admin}, {@code mutation:jcr.deleteNode} covers only that mutation. A
 * legacy entry without a prefix is query-only (see {@link WhitelistEntries}). Introspection fields
 * ({@code __schema}, {@code __type}, {@code __typename}) always pass.</p>
 *
 * <p>An EMPTY whitelist is the read-only default: every query is allowed EXCEPT those under the
 * privileged {@code admin} namespace (it exposes OSGi configuration values, secrets included; an
 * explicit {@code query:admin...} entry re-enables it), no mutation and no subscription is. The {@code communityMcp} mutations are denied whatever the whitelist says: the
 * data plane must not rewrite its own guard. (Whitelisting broad mutation roots such as
 * {@code mutation:jcr} or {@code mutation:admin} is allowed but grants everything below them to
 * administrators — prefer exact paths.)</p>
 *
 * <p>Also never allowed: {@code mutation:admin.jahia.configuration} (OSGi configuration writes
 * could rewrite this module's own whitelist PID) and every named fragment spread ({@code ...Name}),
 * in every mode: spreads are not expanded, so the fields behind one would escape the path checks.
 * Use inline fragments instead. Directives ({@code @include}, {@code @skip}) never hide a field:
 * the gate walks every selection whatever its condition.</p>
 *
 * <p>Container rule (inherent, no behaviour change): a path is allowed when it is covered by an
 * entry OR leads towards one. An entry such as {@code mutation:jcr.addNode.mutateProperty}
 * therefore also lets the intermediate field {@code jcr.addNode} run, and an intermediate mutation
 * field may act by itself (create a node, for instance) even when only a field below it was meant.
 * Whitelist a mutation entry only if every segment of its path is acceptable to run on its own.</p>
 */
@Component(service = GraphQLWhitelistGate.class)
public class GraphQLWhitelistGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLWhitelistGate.class);
    private static final String ANONYMOUS_USER = "anonymous";
    private static final String QUERY_PREFIX = "query.";
    private static final String MUTATION_PREFIX = "mutation.";
    private static final String SUBSCRIPTION_PREFIX = "subscription.";
    private static final String DENIED = "Operation not allowed: ";
    /** End of every per-path denial reason. */
    private static final String NOT_ALLOWED_SUFFIX = "' is not allowed";
    /** Start of the denial for the admin query namespace in the read-only default. */
    public static final String PROTECTED_QUERY_REASON_PREFIX = "queries under 'admin' require an explicit "
            + "query:admin<.path> whitelist entry (the read-only default excludes them: they expose OSGi "
            + "configuration values)";
    /** Client-facing reason for a named fragment spread; the execute tool maps it to a hint. */
    static final String NAMED_SPREAD_REASON =
            "named fragment spreads are not permitted (use inline fragments '... on Type { ... }' instead)";

    private final McpConfigService mcpConfigService;

    @Activate
    public GraphQLWhitelistGate(@Reference final McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    /**
     * Decides whether {@code query} may run.
     *
     * @return empty when the document is allowed, otherwise a client-safe denial reason
     *         (never echoes document content back)
     */
    public Optional<String> check(final String query, final String username, final String clientIp) {
        final String who = LogSupport.clean(username != null ? username : ANONYMOUS_USER);
        final String ip = LogSupport.clean(clientIp);
        final WhitelistSnapshot snapshot = mcpConfigService.snapshot();

        // SEC-364: parse ONCE with the real GraphQL grammar; a document the gate cannot parse is
        // DENIED (the old hand-written scanner let `query,{...}` through).
        final Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException ex) {
            // The parser message can quote document content (inline secrets): log the position only.
            LOGGER.warn("MCP operation blocked: GraphQL document could not be parsed, "
                    + "user='{}', ip='{}', at={}", who, ip, position(ex));
            return Optional.of(DENIED + "the GraphQL document could not be parsed");
        }

        // PRB-1: named fragment spreads are never expanded, so the fields behind one would escape every
        // path check (a mutation reduced to `mutation { ...F }` has no path at all). Refused in EVERY
        // mode, queries included: inline fragments (... on Type { ... }) cover the same need.
        if (containsNamedFragmentSpread(document)) {
            LOGGER.warn("MCP operation blocked: named fragment spreads are not permitted, "
                    + "user='{}', ip='{}'", who, ip);
            return Optional.of(DENIED + NAMED_SPREAD_REASON);
        }

        final Set<String> extracted;
        try {
            extracted = extractOperationPaths(document,
                    Math.max(snapshot.maxDepth(), WhitelistEntries.NEVER_ALLOWED_DEPTH));
        } catch (RuntimeException ex) {
            // A selection the gate cannot account for must never be allowed.
            LOGGER.warn("MCP operation blocked: selection could not be analysed, user='{}', ip='{}'", who, ip);
            return Optional.of(DENIED + "the GraphQL document could not be analysed");
        }
        // An empty set after a SUCCESSFUL parse means the document selects only introspection.
        for (final String path : withoutIntrospectionPaths(extracted)) {
            final Optional<String> denial = denialFor(path, snapshot);
            if (denial.isPresent()) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("MCP operation blocked: path='{}', reason='{}', user='{}', ip='{}'",
                            WhitelistEntries.display(path), denial.get(), who, ip);
                }
                return denial.map(reason -> DENIED + reason);
            }
        }
        return Optional.empty();
    }

    private static String position(final RuntimeException ex) {
        if (ex instanceof graphql.parser.InvalidSyntaxException) {
            final graphql.language.SourceLocation loc = ((graphql.parser.InvalidSyntaxException) ex).getLocation();
            return loc == null ? "unknown" : "line " + loc.getLine() + ", column " + loc.getColumn();
        }
        return "unknown";
    }

    /** Denial reason (without the common prefix) for one operation-qualified path, if any. */
    private static Optional<String> denialFor(final String path, final WhitelistSnapshot snapshot) {
        final String shown = WhitelistEntries.display(path);
        if (WhitelistEntries.isNeverAllowed(path)) {
            return Optional.of("'" + shown + "' can never be run through community.graphql.execute");
        }
        if (snapshot.isReadOnlyDefault()) {
            if (path.startsWith(QUERY_PREFIX)) {
                return WhitelistEntries.isReadOnlyProtected(path)
                        ? Optional.of(PROTECTED_QUERY_REASON_PREFIX + "; '" + shown + NOT_ALLOWED_SUFFIX)
                        : Optional.empty();
            }
            if (path.startsWith(MUTATION_PREFIX)) {
                return Optional.of("mutations require an explicit mutation:<path> whitelist entry "
                        + "(the whitelist is empty, which allows queries only); '" + shown + NOT_ALLOWED_SUFFIX);
            }
            if (path.startsWith(SUBSCRIPTION_PREFIX)) {
                return Optional.of("subscriptions are not supported");
            }
            return Optional.of("'" + shown + NOT_ALLOWED_SUFFIX);
        }
        return isPathAllowed(path, snapshot.normalized())
                ? Optional.empty()
                : Optional.of("'" + shown + "' is not in the whitelist");
    }

    /**
     * Field paths qualified by their operation type as the first segment ({@code query.jcr.nodeByPath},
     * {@code mutation.jcr.deleteNode}), up to {@code maxDepth} segments including that prefix. Every
     * operation of the document is walked, so a mutation cannot hide behind an allowed query.
     */
    static Set<String> extractOperationPaths(final Document doc, final int maxDepth) {
        final Set<String> paths = new LinkedHashSet<>();
        for (final Definition<?> def : doc.getDefinitions()) {
            if (def instanceof OperationDefinition) {
                final OperationDefinition op = (OperationDefinition) def;
                final String type = op.getOperation().name().toLowerCase(java.util.Locale.ROOT);
                collectPaths(op.getSelectionSet(), type, 1, maxDepth, paths);
            }
        }
        return paths;
    }

    private static Set<String> withoutIntrospectionPaths(final Set<String> extracted) {
        final Set<String> paths = new LinkedHashSet<>(extracted);
        paths.removeIf(p -> {
            for (final String seg : p.split("\\.", -1)) {
                if (seg.startsWith("__")) {
                    return true;
                }
            }
            return false;
        });
        return paths;
    }

    static boolean isPathAllowed(final String path, final Set<String> whitelist) {
        for (final String entry : whitelist) {
            if (pathCoveredBy(path, entry) || pathIsContainerOf(path, entry)) {
                return true;
            }
        }
        return false;
    }

    /** True if entry is a dot-segment prefix of (or equal to) path. */
    private static boolean pathCoveredBy(final String path, final String entry) {
        return path.equals(entry) || path.startsWith(entry + ".");
    }

    /** True if path is an intermediate container leading toward a more-specific entry. */
    private static boolean pathIsContainerOf(final String path, final String entry) {
        return entry.startsWith(path + ".");
    }

    /**
     * Returns {@code true} if the document contains a named fragment spread ({@code ...Name}).
     * Inline fragments ({@code ... on Type { ... }}) are allowed.
     */
    static boolean containsNamedFragmentSpread(final Document doc) {
        for (final Definition<?> def : doc.getDefinitions()) {
            if (def instanceof OperationDefinition
                    && hasNamedSpread(((OperationDefinition) def).getSelectionSet())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNamedSpread(final SelectionSet selectionSet) {
        if (selectionSet == null) {
            return false;
        }
        for (final Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof FragmentSpread) {
                return true;
            }
            if (selection instanceof Field && hasNamedSpread(((Field) selection).getSelectionSet())) {
                return true;
            }
            if (selection instanceof InlineFragment
                    && hasNamedSpread(((InlineFragment) selection).getSelectionSet())) {
                return true;
            }
        }
        return false;
    }

    private static void collectPaths(final SelectionSet selectionSet, final String prefix,
            final int depth, final int maxDepth, final Set<String> paths) {
        if (selectionSet == null) {
            return;
        }
        for (final Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field) {
                collectFieldPath((Field) selection, prefix, depth, maxDepth, paths);
            } else if (selection instanceof InlineFragment) {
                // A type condition, not a field: same prefix and depth as the parent.
                collectPaths(((InlineFragment) selection).getSelectionSet(), prefix, depth, maxDepth, paths);
            } else {
                // A FragmentSpread is refused by check() before this point; anything else is unknown.
                throw new IllegalArgumentException("Unsupported selection " + selection.getClass().getSimpleName());
            }
        }
    }

    private static void collectFieldPath(final Field field, final String prefix,
            final int depth, final int maxDepth, final Set<String> paths) {
        final String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
        paths.add(path);
        // Deeper paths are decided by the same entry that covers this one (see pathCoveredBy).
        if (depth + 1 < maxDepth) {
            collectPaths(field.getSelectionSet(), path, depth + 1, maxDepth, paths);
        }
    }
}
