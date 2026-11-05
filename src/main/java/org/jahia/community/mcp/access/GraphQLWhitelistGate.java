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
 * <p>An EMPTY whitelist is the read-only default: every query is allowed, no mutation and no
 * subscription is. The {@code communityMcp} mutations are denied whatever the whitelist says: the
 * data plane must not rewrite its own guard. (Whitelisting broad mutation roots such as
 * {@code mutation:jcr} or {@code mutation:admin} is allowed but grants everything below them to
 * administrators — prefer exact paths.)</p>
 */
@Component(service = GraphQLWhitelistGate.class)
public class GraphQLWhitelistGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLWhitelistGate.class);
    private static final String ANONYMOUS_USER = "anonymous";
    private static final String QUERY_PREFIX = "query.";
    private static final String MUTATION_PREFIX = "mutation.";
    private static final String SUBSCRIPTION_PREFIX = "subscription.";
    private static final String DENIED = "Operation not allowed: ";

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
        final String who = username != null ? username : ANONYMOUS_USER;
        final WhitelistSnapshot snapshot = mcpConfigService.snapshot();

        // SEC-364: parse ONCE with the real GraphQL grammar; a document the gate cannot parse is
        // DENIED (the old hand-written scanner let `query,{...}` through).
        final Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException ex) {
            // The parser message can quote document content — keep it in the audit log only.
            LOGGER.warn("MCP operation blocked: GraphQL document could not be parsed, "
                    + "user='{}', ip='{}', reason='{}'", who, clientIp, ex.getMessage());
            return Optional.of(DENIED + "the GraphQL document could not be parsed");
        }

        // SEC-2: named fragment spreads cannot be resolved against whitelist entries without the
        // fragment definitions — fail closed. In read-only mode only the operation type matters
        // (a fragment cannot turn a query into a mutation), so spreads are harmless there.
        if (!snapshot.isReadOnlyDefault() && containsNamedFragmentSpread(document)) {
            LOGGER.warn("MCP operation blocked: named fragment spreads not permitted when "
                    + "whitelist is active, user='{}', ip='{}'", who, clientIp);
            return Optional.of(DENIED + "named fragment spreads are not permitted when a whitelist is active");
        }

        // An empty set after a SUCCESSFUL parse means the document selects only introspection.
        for (final String path : withoutIntrospectionPaths(extractOperationPaths(document, snapshot.maxDepth()))) {
            final Optional<String> denial = denialFor(path, snapshot);
            if (denial.isPresent()) {
                LOGGER.warn("MCP operation blocked: path='{}', reason='{}', user='{}', ip='{}'",
                        WhitelistEntries.display(path), denial.get(), who, clientIp);
                return denial.map(reason -> DENIED + reason);
            }
        }
        return Optional.empty();
    }

    /** Denial reason (without the common prefix) for one operation-qualified path, if any. */
    private static Optional<String> denialFor(final String path, final WhitelistSnapshot snapshot) {
        final String shown = WhitelistEntries.display(path);
        if (WhitelistEntries.isNeverAllowed(path)) {
            return Optional.of("'" + shown + "' can never be run through community.graphql.execute");
        }
        if (snapshot.isReadOnlyDefault()) {
            if (path.startsWith(QUERY_PREFIX)) {
                return Optional.empty();
            }
            if (path.startsWith(MUTATION_PREFIX)) {
                return Optional.of("mutations require an explicit mutation:<path> whitelist entry "
                        + "(the whitelist is empty, which allows queries only); '" + shown + "' is not allowed");
            }
            if (path.startsWith(SUBSCRIPTION_PREFIX)) {
                return Optional.of("subscriptions are not supported");
            }
            return Optional.of("'" + shown + "' is not allowed");
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
            }
            // FragmentSpread is deliberately NOT expanded — check() denies documents containing one.
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
