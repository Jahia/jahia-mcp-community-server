package org.jahia.community.mcp;

import graphql.parser.InvalidSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for McpServlet's security-critical static helpers:
 * extractFieldPaths and containsNamedFragmentSpread.
 * checkAccess logic is tested indirectly through these primitives.
 */
class McpServletAccessTest {

    // ─────────────────────────────────────────────────────────────────────────
    // extractFieldPaths
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractFieldPaths")
    class ExtractFieldPathsTest {

        @Test
        @DisplayName("null query returns empty set")
        void null_query_returns_empty() {
            assertThat(McpServlet.extractFieldPaths((String) null, 3)).isEmpty();
        }

        @Test
        @DisplayName("blank query returns empty set")
        void blank_query_returns_empty() {
            assertThat(McpServlet.extractFieldPaths("   ", 3)).isEmpty();
        }

        @Test
        @DisplayName("simple query extracts top-level fields")
        void simple_query_extracts_top_level_fields() {
            String q = "{ jcr currentUser admin }";
            assertThat(McpServlet.extractFieldPaths(q, 1))
                    .containsExactlyInAnyOrder("jcr", "currentUser", "admin");
        }

        @Test
        @DisplayName("alias resolves to real field name not alias label")
        void alias_resolves_to_field_name() {
            String q = "{ myAlias: jcr { nodeByPath(path: \"/\") { name } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).contains("jcr").doesNotContain("myAlias");
        }

        @Test
        @DisplayName("inline fragment fields are collected under parent prefix")
        void inline_fragment_fields_collected() {
            String q = "{ admin { ... on AdminQueries { jahia { isAlive } } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).contains("admin", "admin.jahia", "admin.jahia.isAlive");
        }

        @Test
        @DisplayName("named fragment spread body is NOT expanded by extractFieldPaths")
        void named_fragment_spread_not_expanded() {
            // Fields inside fragment definition must not be resolved
            String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { jahia { isAlive } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).contains("admin").doesNotContain("admin.jahia", "admin.jahia.isAlive");
        }

        @Test
        @DisplayName("field directives are skipped, field name extracted correctly")
        void directives_skipped() {
            String q = "{ jcr @skip(if: false) { nodeByPath(path: \"/\") @include(if: true) { name } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).contains("jcr", "jcr.nodeByPath", "jcr.nodeByPath.name");
        }

        @Test
        @DisplayName("depth cap stops collection at maxDepth segments")
        void depth_cap_limits_collection() {
            String q = "{ a { b { c { d } } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 2);
            assertThat(paths).contains("a", "a.b").doesNotContain("a.b.c", "a.b.c.d");
        }

        @Test
        @DisplayName("line comments are stripped before parsing")
        void line_comments_stripped() {
            String q = "{\n  # this is a comment\n  jcr\n  currentUser\n}";
            assertThat(McpServlet.extractFieldPaths(q, 1))
                    .containsExactlyInAnyOrder("jcr", "currentUser");
        }

        @Test
        @DisplayName("hash character inside string argument value does not strip query content")
        void hash_in_string_arg_not_treated_as_comment() {
            // The '#' inside the string literal must not eat the rest of the line
            String q = "{ jcr { nodeByPath(path: \"/sites#main\") { name } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).contains("jcr", "jcr.nodeByPath", "jcr.nodeByPath.name");
        }

        @Test
        @DisplayName("mutation keyword is handled as operation header")
        void mutation_keyword_handled() {
            String q = "mutation { mcpSaveSettings(whitelist: []) }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 1);
            assertThat(paths).contains("mcpSaveSettings");
        }

        // F7(h) — whitespace-normalization guard. Irregular spaces / newlines / tabs must not
        // fracture field names or hide segments from the whitelist check.
        @Test
        @DisplayName("irregular whitespace, newlines and tabs do not hide field segments")
        void whitespace_does_not_fracture_paths() {
            String q = "{  \n admin \t { jahia { isAlive } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths).containsExactlyInAnyOrder("admin", "admin.jahia", "admin.jahia.isAlive");
        }

        // SEC-364 — EVERY operation definition is walked, not just the first. This test previously
        // pinned the opposite (op B's fields were never collected): back then op B was stopped only
        // because executeGraphQL forwards no operationName, so graphql-java rejected the whole
        // multi-op document. The gate now decides on op B directly rather than leaning on that.
        @Test
        @DisplayName("multi-operation document: every operation's paths are extracted")
        void multi_operation_all_ops_parsed() {
            String q = "query A { currentUser { name } } query B { admin { jahia { isAlive } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 3);
            assertThat(paths)
                    .contains("currentUser", "admin", "admin.jahia", "admin.jahia.isAlive");
        }

        // SEC-364 — commas are ignored tokens in the GraphQL grammar. The previous hand-written
        // scanner stopped on the first one it met before the selection set and returned an empty
        // path set, which checkAccess read as "nothing to police". They are now simply parsed.
        @Test
        @DisplayName("ignored tokens before the selection set do not empty the extracted paths")
        void ignored_tokens_do_not_empty_paths() {
            assertThat(McpServlet.extractFieldPaths("query,{ jcr { nodeByPath(path:\"/\") { name } } }", 2))
                    .contains("jcr", "jcr.nodeByPath");
            assertThat(McpServlet.extractFieldPaths(",query { jcr { nodeByPath(path:\"/\") { name } } }", 2))
                    .contains("jcr", "jcr.nodeByPath");
        }

        // SEC-364 — a '(' inside a string argument used to unbalance skipBalanced()'s counter, which
        // then ran to end-of-document and silently dropped every following sibling. The extracted set
        // stayed NON-EMPTY and passed the whitelist, so "treat an empty set as deny" would have
        // missed this entirely. Only real parsing closes it.
        @Test
        @DisplayName("unbalanced paren inside a string argument does not hide sibling fields")
        void unbalanced_paren_in_string_arg_does_not_hide_siblings() {
            String q = "{ jcr { nodeByPath(path: \"/x(\") { name } } admin { jahia { isAlive } } }";
            Set<String> paths = McpServlet.extractFieldPaths(q, 2);
            assertThat(paths).contains("jcr", "jcr.nodeByPath", "admin", "admin.jahia");
        }

        // SEC-364 regression guard — a document the parser rejects must raise, NOT return an empty
        // set. The empty set is what checkAccess used to read as "permit".
        @Test
        @DisplayName("unparseable document raises rather than returning an empty set")
        void unparseable_document_raises() {
            assertThatThrownBy(() -> McpServlet.extractFieldPaths("{ jcr { ", 2))
                    .isInstanceOf(InvalidSyntaxException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // containsNamedFragmentSpread
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("containsNamedFragmentSpread")
    class ContainsNamedFragmentSpreadTest {

        @Test
        @DisplayName("null returns false")
        void null_returns_false() {
            assertThat(McpServlet.containsNamedFragmentSpread((String) null)).isFalse();
        }

        @Test
        @DisplayName("blank returns false")
        void blank_returns_false() {
            assertThat(McpServlet.containsNamedFragmentSpread("  ")).isFalse();
        }

        @Test
        @DisplayName("plain query with no spreads returns false")
        void plain_query_no_spreads() {
            assertThat(McpServlet.containsNamedFragmentSpread("{ jcr { nodeByPath(path:\"/\") { name } } }")).isFalse();
        }

        @Test
        @DisplayName("inline fragment does not trigger detection")
        void inline_fragment_not_detected() {
            String q = "{ admin { ... on AdminQueries { jahia { isAlive } } } }";
            assertThat(McpServlet.containsNamedFragmentSpread(q)).isFalse();
        }

        @Test
        @DisplayName("named fragment spread is detected")
        void named_fragment_spread_detected() {
            String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { isAlive }";
            assertThat(McpServlet.containsNamedFragmentSpread(q)).isTrue();
        }

        @Test
        @DisplayName("spread in a line comment is not detected")
        void commented_spread_not_detected() {
            String q = "{ admin { isAlive } }\n# ...SomeFragment";
            assertThat(McpServlet.containsNamedFragmentSpread(q)).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAccess logic (via path helpers)
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // isPathAllowed + collectNonIntrospectionPaths — the REAL production methods
    // (package-private after the visibility refactor). These are the security core:
    // each assertion fails loudly if the corresponding whitelist defense regresses.
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkAccess path logic (real production methods)")
    class CheckAccessPathLogicTest {

        @Test
        @DisplayName("covered path is allowed by whitelist entry")
        void covered_path_allowed() {
            // whitelist: jcr — query accesses jcr.nodeByPath.name → all covered
            Set<String> whitelist = Set.of("jcr");
            Set<String> paths = McpServlet.collectNonIntrospectionPaths("{ jcr { nodeByPath(path:\"/\") { name } } }", 3);
            assertThat(paths).isNotEmpty();
            assertThat(paths.stream().allMatch(p -> McpServlet.isPathAllowed(p, whitelist))).isTrue();
        }

        @Test
        @DisplayName("uncovered path is blocked")
        void uncovered_path_blocked() {
            Set<String> whitelist = Set.of("currentUser");
            Set<String> paths = McpServlet.collectNonIntrospectionPaths("{ admin { jahia { isAlive } } }", 3);
            assertThat(paths.stream().anyMatch(p -> !McpServlet.isPathAllowed(p, whitelist))).isTrue();
        }

        // F7(d) — __-introspection filter regression guard, against the REAL production
        // collectNonIntrospectionPaths (previously asserted against a test-local mirror).
        @Test
        @DisplayName("introspection-only doc: all __-segment paths stripped, result is empty")
        void introspection_fields_filtered() {
            Set<String> paths = McpServlet.collectNonIntrospectionPaths("{ __schema { types { name } } }", 2);
            assertThat(paths).isEmpty();
        }

        // F7(d) — mixed doc: a real field alongside __schema must SURVIVE the introspection
        // filter and remain subject to the whitelist. Guards against a regression that dropped
        // the whole document on seeing any __ segment (which would smuggle real fields past the gate).
        @Test
        @DisplayName("mixed introspection + real field: __ paths stripped but real field survives")
        void mixed_doc_real_field_survives_filter() {
            Set<String> paths = McpServlet.collectNonIntrospectionPaths(
                    "{ __schema { types { name } } admin { jahia { isAlive } } }", 3);
            assertThat(paths)
                    .contains("admin", "admin.jahia", "admin.jahia.isAlive")
                    .noneMatch(p -> p.contains("__"));
        }

        // F7(g) — case-sensitivity guard. Matching MUST be case-sensitive (GraphQL field names
        // are case-sensitive); a regression to case-insensitive matching would broaden the allow-list.
        @Test
        @DisplayName("path matching is case-sensitive: case-mismatch is NOT allowed")
        void path_matching_is_case_sensitive() {
            Set<String> whitelist = Set.of("currentUser");
            assertThat(McpServlet.isPathAllowed("currentuser", whitelist)).isFalse();
            assertThat(McpServlet.isPathAllowed("CURRENTUSER", whitelist)).isFalse();
            // exact case still matches (positive control)
            assertThat(McpServlet.isPathAllowed("currentUser", whitelist)).isTrue();
        }

        @Test
        @DisplayName("dot-path container coverage: ancestor entry covers descendant, and vice-versa")
        void dot_path_container_coverage() {
            // entry is an ancestor of the path → covered
            assertThat(McpServlet.isPathAllowed("admin.jahia.isAlive", Set.of("admin"))).isTrue();
            // path is an intermediate container leading toward a deeper entry → traversal allowed
            assertThat(McpServlet.isPathAllowed("admin", Set.of("admin.jahia.isAlive"))).isTrue();
            // sibling is not covered
            assertThat(McpServlet.isPathAllowed("currentUser", Set.of("admin.jahia"))).isFalse();
        }

        @Test
        @DisplayName("named fragment spread with active whitelist must be denied (containsNamedFragmentSpread=true)")
        void named_fragment_with_whitelist_denied() {
            String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { jahia { isAlive } }";
            // When whitelist is non-empty, containsNamedFragmentSpread must return true → deny
            assertThat(McpServlet.containsNamedFragmentSpread(q)).isTrue();
        }
    }
}
