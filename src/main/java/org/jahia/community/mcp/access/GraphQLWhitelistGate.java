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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * explicit {@code query:admin...} entry re-enables it), and no mutation and no subscription is allowed.
 * The {@code communityMcp} mutations are denied whatever the whitelist says: the
 * data plane must not rewrite its own guard. (Whitelisting broad mutation roots such as
 * {@code mutation:jcr} or {@code mutation:admin} is allowed but grants everything below them to
 * administrators — prefer exact paths.)</p>
 *
 * <p>Also never allowed: {@code mutation:admin.jahia.configuration} (OSGi configuration writes
 * could rewrite this module's own whitelist PID), {@code mutation:admin.personalApiTokens}
 * (credential creation), every mutation path with a {@code grantRoles} or {@code revokeRoles}
 * segment at any depth (privilege escalation), and every named fragment spread ({@code ...Name}),
 * in every mode: spreads are not expanded, so the fields behind one would escape the path checks.
 * Use inline fragments instead. Directives ({@code @include}, {@code @skip}) never hide a field:
 * the gate walks every selection whatever its condition.</p>
 *
 * <p>Container rule: a path is allowed when it is covered by an
 * entry OR leads towards one. An entry such as {@code mutation:jcr.addNode.mutateProperty}
 * therefore also lets the intermediate field {@code jcr.addNode} run, and an intermediate mutation
 * field may act by itself (create a node, for instance) even when only a field below it was meant.
 * Whitelist a mutation entry only if every segment of its path is acceptable to run on its own.</p>
 *
 * <p>Known limits (documented, not closed): graphql-dxm-provider's {@code fieldFilter},
 * {@code fieldSorter} and {@code fieldGrouping} arguments name fields that need not appear in the
 * document and are resolved without the permission instrumentation, so they can probe fields that a
 * narrow query entry did not list (query side only). And a mutation container field with only
 * {@code __typename} selected beneath it runs on its own under the container rule above.</p>
 */
@Component(service = GraphQLWhitelistGate.class)
public class GraphQLWhitelistGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLWhitelistGate.class);
    private static final String ANONYMOUS_USER = "anonymous";
    private static final String QUERY_PREFIX = "query.";
    private static final String MUTATION_PREFIX = "mutation.";
    private static final String SUBSCRIPTION_PREFIX = "subscription.";
    private static final String DENIED = "Operation not allowed: ";
    private static final String UNANALYSABLE_REASON = "the GraphQL document could not be analysed "
            + "(too deeply nested or unsupported)";
    /** End of every per-path denial reason. */
    private static final String NOT_ALLOWED_SUFFIX = "' is not allowed";
    /** Start of the denial for the admin query namespace in the read-only default. */
    public static final String PROTECTED_QUERY_REASON_PREFIX = "queries under 'admin' require an explicit "
            + "query:admin<.path> whitelist entry (the read-only default excludes them: they expose OSGi "
            + "configuration values)";
    /** Start of the client-facing reason for a document that is not valid GraphQL. */
    static final String PARSE_REASON = "The GraphQL document could not be parsed";
    /** Client-facing reason for a named fragment spread. */
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
        return evaluate(query, username, clientIp).map(Denial::message);
    }

    /**
     * Decides whether {@code query} may run.
     *
     * @return empty when the document is allowed, otherwise the typed, client-safe denial
     */
    public Optional<Denial> evaluate(final String query, final String username, final String clientIp) {
        final String who = LogSupport.clean(username != null ? username : ANONYMOUS_USER);
        final String ip = LogSupport.clean(clientIp);
        final WhitelistSnapshot snapshot = mcpConfigService.snapshot();
        try {
            return evaluate(query, snapshot, who, ip);
        } catch (StackOverflowError ex) {
            // A pathologically nested document: refuse it, never let the Error escape.
            LOGGER.warn("MCP operation blocked: document nesting exhausted the stack, user='{}', ip='{}'", who, ip);
            return Optional.of(new Denial(Denial.Kind.UNANALYSABLE, DENIED + UNANALYSABLE_REASON));
        }
    }

    private static Optional<Denial> evaluate(final String query, final WhitelistSnapshot snapshot,
            final String who, final String ip) {
        // Parse ONCE with the real GraphQL grammar; a document the gate cannot parse is DENIED.
        final Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException ex) {
            // The parser message can quote document content (inline secrets): only the position is
            // exposed, to the log and to the client.
            final String position = position(ex);
            LOGGER.warn("MCP operation blocked: GraphQL document could not be parsed, "
                    + "user='{}', ip='{}', at={}", who, ip, position);
            return Optional.of(new Denial(Denial.Kind.PARSE, PARSE_REASON
                    + ("unknown".equals(position) ? "" : " (syntax error at " + position + ")")));
        }

        // Named fragment spreads are never expanded, so the fields behind one would escape every
        // path check (a mutation reduced to `mutation { ...F }` has no path at all). Refused in EVERY
        // mode, queries included: inline fragments (... on Type { ... }) cover the same need.
        if (containsNamedFragmentSpread(document)) {
            LOGGER.warn("MCP operation blocked: named fragment spreads are not permitted, "
                    + "user='{}', ip='{}'", who, ip);
            return Optional.of(new Denial(Denial.Kind.NAMED_SPREAD, DENIED + NAMED_SPREAD_REASON));
        }

        final Set<String> extracted;
        try {
            // No depth cut-off: a never-allowed segment (grantRoles...) can sit at any depth.
            extracted = extractOperationPaths(document, Integer.MAX_VALUE);
        } catch (RuntimeException ex) {
            // A selection the gate cannot account for must never be allowed.
            LOGGER.warn("MCP operation blocked: selection could not be analysed, user='{}', ip='{}'", who, ip);
            return Optional.of(new Denial(Denial.Kind.UNANALYSABLE, DENIED + UNANALYSABLE_REASON));
        }
        // An empty set after a SUCCESSFUL parse means the document selects only introspection.
        // Never-allowed paths are reported first, so the client learns that no whitelist entry can
        // ever help rather than being told to ask for one.
        final Set<String> paths = withoutIntrospectionPaths(extracted);
        final List<String> ordered = new ArrayList<>(paths.size());
        paths.stream().filter(WhitelistEntries::isNeverAllowed).forEach(ordered::add);
        paths.stream().filter(p -> !WhitelistEntries.isNeverAllowed(p)).forEach(ordered::add);
        for (final String path : ordered) {
            final Optional<Denial> denial = denialFor(path, snapshot);
            if (denial.isPresent()) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("MCP operation blocked: path='{}', reason='{}', user='{}', ip='{}'",
                            WhitelistEntries.display(path), denial.get().message(), who, ip);
                }
                return denial.map(d -> new Denial(d.kind(), DENIED + d.message()));
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

    /** Denial (message without the common prefix) for one operation-qualified path, if any. */
    private static Optional<Denial> denialFor(final String path, final WhitelistSnapshot snapshot) {
        final String shown = WhitelistEntries.display(path);
        if (WhitelistEntries.isNeverAllowed(path)) {
            return denial(Denial.Kind.NEVER_ALLOWED, "'" + shown + "' can never be run through community.graphql.execute");
        }
        if (snapshot.isReadOnlyDefault()) {
            if (path.startsWith(QUERY_PREFIX)) {
                return WhitelistEntries.isReadOnlyProtected(path)
                        ? denial(Denial.Kind.PROTECTED_QUERY,
                                PROTECTED_QUERY_REASON_PREFIX + "; '" + shown + NOT_ALLOWED_SUFFIX)
                        : Optional.empty();
            }
            if (path.startsWith(MUTATION_PREFIX)) {
                return denial(Denial.Kind.MUTATION_NOT_ALLOWED, "mutations require an explicit mutation:<path> "
                        + "whitelist entry (the whitelist is empty, which allows queries only); '" + shown
                        + NOT_ALLOWED_SUFFIX);
            }
            if (path.startsWith(SUBSCRIPTION_PREFIX)) {
                return denial(Denial.Kind.SUBSCRIPTION, "subscriptions are not supported");
            }
            return denial(Denial.Kind.NOT_WHITELISTED, "'" + shown + NOT_ALLOWED_SUFFIX);
        }
        return isPathAllowed(path, snapshot.normalized())
                ? Optional.empty()
                : denial(kindOfUnlisted(path), "'" + shown + "' is not in the whitelist");
    }

    private static Denial.Kind kindOfUnlisted(final String path) {
        if (path.startsWith(SUBSCRIPTION_PREFIX)) {
            return Denial.Kind.SUBSCRIPTION;
        }
        return path.startsWith(MUTATION_PREFIX) ? Denial.Kind.MUTATION_NOT_ALLOWED : Denial.Kind.NOT_WHITELISTED;
    }

    private static Optional<Denial> denial(final Denial.Kind kind, final String message) {
        return Optional.of(new Denial(kind, message));
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
