package org.jahia.community.mcp.access;

import graphql.language.Document;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security-critical helpers of {@link GraphQLWhitelistGate} that production {@code check()} really
 * uses: {@code extractOperationPaths}, {@code containsNamedFragmentSpread(Document)} and
 * {@code isPathAllowed}, plus end-to-end {@code check()} cases for the gate's security properties.
 */
class GraphQLWhitelistGateHelpersTest {

    private static Set<String> paths(final String query, final int depth) {
        return GraphQLWhitelistGate.extractOperationPaths(Parser.parse(query), depth);
    }

    private static GraphQLWhitelistGate gate(final String... entries) {
        final McpConfigService config = mock(McpConfigService.class);
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of(entries)));
        return new GraphQLWhitelistGate(config);
    }

    @Test
    @DisplayName("paths are qualified by the operation type, the { } shorthand being a query")
    void paths_are_operation_qualified() {
        assertThat(paths("{ jcr currentUser }", 2)).containsExactlyInAnyOrder("query.jcr", "query.currentUser");
        assertThat(paths("mutation { jcr { deleteNode } }", 3)).contains("mutation.jcr", "mutation.jcr.deleteNode");
        assertThat(paths("subscription { x }", 2)).containsExactly("subscription.x");
    }

    @Test
    @DisplayName("alias resolves to the real field name")
    void alias_resolves_to_field_name() {
        assertThat(paths("{ myAlias: jcr { nodeByPath(path: \"/\") { name } } }", 3))
                .contains("query.jcr").doesNotContain("query.myAlias");
    }

    @Test
    @DisplayName("inline fragment fields are collected under the parent prefix")
    void inline_fragment_fields_collected() {
        assertThat(paths("{ admin { ... on AdminQueries { jahia { isAlive } } } }", 4))
                .contains("query.admin", "query.admin.jahia", "query.admin.jahia.isAlive");
    }

    @Test
    @DisplayName("named fragment spread body is not expanded")
    void named_fragment_spread_not_expanded() {
        assertThat(paths("{ admin { ...F } } fragment F on AdminQueries { jahia { isAlive } }", 4))
                .contains("query.admin").doesNotContain("query.admin.jahia");
    }

    @Test
    @DisplayName("directives are skipped and the depth cap stops collection")
    void directives_and_depth_cap() {
        String q = "{ jcr @skip(if: false) { nodeByPath(path: \"/\") @include(if: true) { name } } }";
        assertThat(paths(q, 3)).contains("query.jcr", "query.jcr.nodeByPath").doesNotContain("query.jcr.nodeByPath.name");
    }

    @Test
    @DisplayName("comments, '#' in strings, odd whitespace and parens in strings do not hide fields")
    void lexical_edge_cases() {
        assertThat(paths("{ # note\n jcr { nodeByPath(path: \"/a#b(\") { name } }\n\t admin { x } }", 3))
                .contains("query.jcr", "query.admin");
        assertThat(paths("query,{ jcr }", 2)).contains("query.jcr");
    }

    @Test
    @DisplayName("every operation of a multi-operation document is walked")
    void multi_operation() {
        assertThat(paths("query A { a } mutation B { b }", 2)).containsExactlyInAnyOrder("query.a", "mutation.b");
    }

    @Test
    @DisplayName("an unparseable document raises instead of returning an empty set")
    void unparseable_raises() {
        assertThatThrownBy(() -> Parser.parse("{ jcr { ")).isInstanceOf(InvalidSyntaxException.class);
    }

    @Test
    @DisplayName("named fragment spreads are detected anywhere, inline fragments are not")
    void fragment_spread_detection() {
        Document plain = Parser.parse("{ a { b } }");
        Document inline = Parser.parse("{ a { ... on T { b } } }");
        Document spread = Parser.parse("{ a { ...F } } fragment F on T { b }");
        Document nestedInInline = Parser.parse("{ a { ... on T { b { ...F } } } } fragment F on U { c }");

        assertThat(GraphQLWhitelistGate.containsNamedFragmentSpread(plain)).isFalse();
        assertThat(GraphQLWhitelistGate.containsNamedFragmentSpread(inline)).isFalse();
        assertThat(GraphQLWhitelistGate.containsNamedFragmentSpread(spread)).isTrue();
        assertThat(GraphQLWhitelistGate.containsNamedFragmentSpread(nestedInInline)).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: prefix coverage, container coverage, case sensitivity")
    void is_path_allowed() {
        Set<String> entries = Set.of("query.jcr", "mutation.jcr.deleteNode");

        assertThat(GraphQLWhitelistGate.isPathAllowed("query.jcr.nodeByPath", entries)).isTrue();
        assertThat(GraphQLWhitelistGate.isPathAllowed("mutation.jcr", entries)).as("container of an entry").isTrue();
        assertThat(GraphQLWhitelistGate.isPathAllowed("mutation.jcr.addNode", entries)).isFalse();
        assertThat(GraphQLWhitelistGate.isPathAllowed("query.JCR", entries)).isFalse();
        assertThat(GraphQLWhitelistGate.isPathAllowed("query.jcrx", entries)).as("not a segment prefix").isFalse();
    }

    @Test
    @DisplayName("check(): introspection-only passes, a real field next to __schema is still policed")
    void introspection_filter() {
        GraphQLWhitelistGate g = gate("query:currentUser");

        assertThat(g.check("{ __schema { types { name } } }", "u", "ip")).isEmpty();
        assertThat(g.check("{ __schema { types { name } } admin { x } }", "u", "ip")).isPresent();
    }

    @Test
    @DisplayName("check(): a named fragment spread under an active whitelist is denied")
    void spread_denied_under_whitelist() {
        assertThat(gate("query:admin").check("{ admin { ...F } } fragment F on A { x }", "u", "ip")).isPresent();
    }

    @Test
    @DisplayName("check(): entries deeper than the default depth are decided at their own depth")
    void deep_entries() {
        GraphQLWhitelistGate g = gate("query:admin.jahia.isAlive");

        assertThat(g.check("{ admin { jahia { isAlive } } }", "u", "ip")).isEmpty();
        assertThat(g.check("{ admin { jahia { version } } }", "u", "ip")).isPresent();
    }
}
