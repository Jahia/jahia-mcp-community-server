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
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Dot-path whitelist gate applied to every GraphQL document before it is executed by the
 * {@code community.graphql.execute} tool.
 *
 * <p>Entries are dot-separated path prefixes: {@code admin} covers every sub-operation of
 * {@code admin}, {@code admin.jahia.shutdown} covers only that nested path. Introspection
 * fields ({@code __schema}, {@code __type}, {@code __typename}) always pass.</p>
 */
@Component(service = GraphQLWhitelistGate.class)
public class GraphQLWhitelistGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLWhitelistGate.class);
    private static final String ANONYMOUS_USER = "anonymous";

    // volatile: written on the OSGi bind thread, read on MCP worker threads
    private volatile McpConfigService mcpConfigService;

    @Reference
    public void setMcpConfigService(McpConfigService mcpConfigService) {
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
        final Set<String> whitelist = mcpConfigService.getWhitelist();

        if (whitelist.isEmpty()) {
            // SECURITY POSTURE (intentional default — do NOT change the meaning here without
            // maintainer sign-off): an EMPTY whitelist means ALLOW-ALL so the module is usable out
            // of the box. Backstops: the community tools are admin-only (CommunityMcpAccess), and
            // every document still runs as the calling user, bounded by JCR ACLs. Flipping
            // empty→deny-all is a breaking change that needs a major version.
            LOGGER.warn("MCP GraphQL gate is running in ALLOW-ALL mode (whitelist is empty). "
                    + "All GraphQL operations are permitted for administrators. "
                    + "For least-privilege configure a whitelist in Administration -> MCP Server.");
            return Optional.empty();
        }

        // SEC-364: parse ONCE with the real GraphQL grammar; a document the gate cannot parse is
        // DENIED (the old hand-written scanner let `query,{...}` through).
        final Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException ex) {
            // The parser message can quote document content — keep it in the audit log only.
            LOGGER.warn("MCP operation blocked: GraphQL document could not be parsed while a whitelist "
                    + "is active, user='{}', ip='{}', reason='{}'", who, clientIp, ex.getMessage());
            return Optional.of("Operation not allowed: the GraphQL document could not be parsed");
        }

        // SEC-2: named fragment spreads cannot be resolved against the whitelist without the
        // fragment definitions — fail closed rather than allowing a bypass.
        if (containsNamedFragmentSpread(document)) {
            LOGGER.warn("MCP operation blocked: named fragment spreads not permitted when "
                    + "whitelist is active, user='{}', ip='{}'", who, clientIp);
            return Optional.of("Operation not allowed: "
                    + "named fragment spreads are not permitted when a whitelist is active");
        }

        int maxDepth = 1;
        for (final String e : whitelist) {
            maxDepth = Math.max(maxDepth, segmentCount(e));
        }

        // An empty set after a SUCCESSFUL parse means the document selects only introspection.
        for (final String path : collectNonIntrospectionPaths(document, maxDepth)) {
            if (!isPathAllowed(path, whitelist)) {
                LOGGER.warn("MCP operation blocked: path='{}', reason=not in whitelist, user='{}', ip='{}'",
                        path, who, clientIp);
                return Optional.of("Operation not allowed: '" + path + "' is not in the whitelist");
            }
        }
        return Optional.empty();
    }

    /** Extracts field paths from the query and removes introspection segments. */
    static Set<String> collectNonIntrospectionPaths(final String query, final int maxDepth) {
        return withoutIntrospectionPaths(extractFieldPaths(query, maxDepth));
    }

    /** AST form of {@link #collectNonIntrospectionPaths(String, int)}. */
    static Set<String> collectNonIntrospectionPaths(final Document doc, final int maxDepth) {
        return withoutIntrospectionPaths(extractFieldPaths(doc, maxDepth));
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

    private static int segmentCount(final String entry) {
        int n = 1;
        for (int k = 0; k < entry.length(); k++) {
            if (entry.charAt(k) == '.') {
                n++;
            }
        }
        return n;
    }

    /**
     * Returns {@code true} if the query contains a named fragment spread ({@code ...Name}).
     * Inline fragments ({@code ... on Type { ... }}) are allowed.
     *
     * @throws graphql.parser.InvalidSyntaxException if the document cannot be parsed
     */
    static boolean containsNamedFragmentSpread(final String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return containsNamedFragmentSpread(Parser.parse(query));
    }

    /** AST form of {@link #containsNamedFragmentSpread(String)}. */
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

    /**
     * Extracts all field paths up to {@code maxDepth}. Aliases resolve to the real field name,
     * inline fragments contribute to the enclosing prefix, and every operation is walked.
     *
     * @throws graphql.parser.InvalidSyntaxException if the document cannot be parsed
     */
    static Set<String> extractFieldPaths(final String query, final int maxDepth) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }
        return extractFieldPaths(Parser.parse(query), maxDepth);
    }

    /** AST form of {@link #extractFieldPaths(String, int)}. */
    static Set<String> extractFieldPaths(final Document doc, final int maxDepth) {
        final Set<String> paths = new LinkedHashSet<>();
        for (final Definition<?> def : doc.getDefinitions()) {
            if (def instanceof OperationDefinition) {
                collectPaths(((OperationDefinition) def).getSelectionSet(), "", 0, maxDepth, paths);
            }
        }
        return paths;
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
