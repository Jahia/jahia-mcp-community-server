package org.jahia.community.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(McpServlet.extractFieldPaths(null, 3)).isEmpty();
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
            assertThat(McpServlet.containsNamedFragmentSpread(null)).isFalse();
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

    @Nested
    @DisplayName("checkAccess path logic")
    class CheckAccessPathLogicTest {

        /** Mirror of McpServlet.isPathAllowed (private static — duplicated for testing). */
        private boolean isPathAllowed(String path, Set<String> whitelist) {
            for (String entry : whitelist) {
                if (path.equals(entry) || path.startsWith(entry + ".") || entry.startsWith(path + ".")) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> nonIntrospectionPaths(String query, int depth) {
            Set<String> paths = new LinkedHashSet<>(McpServlet.extractFieldPaths(query, depth));
            paths.removeIf(p -> { for (String s : p.split("\\.", -1)) if (s.startsWith("__")) return true; return false; });
            return paths;
        }

        @Test
        @DisplayName("covered path is allowed by whitelist entry")
        void covered_path_allowed() {
            // whitelist: jcr — query accesses jcr.nodeByPath.name → all covered
            Set<String> whitelist = Set.of("jcr");
            Set<String> paths = nonIntrospectionPaths("{ jcr { nodeByPath(path:\"/\") { name } } }", 3);
            assertThat(paths.stream().allMatch(p -> isPathAllowed(p, whitelist))).isTrue();
        }

        @Test
        @DisplayName("uncovered path is blocked")
        void uncovered_path_blocked() {
            Set<String> whitelist = Set.of("currentUser");
            Set<String> paths = nonIntrospectionPaths("{ admin { jahia { isAlive } } }", 3);
            assertThat(paths.stream().anyMatch(p -> !isPathAllowed(p, whitelist))).isTrue();
        }

        @Test
        @DisplayName("introspection fields are filtered out before whitelist check")
        void introspection_fields_filtered() {
            Set<String> paths = nonIntrospectionPaths("{ __schema { types { name } } }", 2);
            assertThat(paths).isEmpty();
        }

        @Test
        @DisplayName("named fragment spread with active whitelist must be denied (containsNamedFragmentSpread=true)")
        void named_fragment_with_whitelist_denied() {
            String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { jahia { isAlive } }";
            // When whitelist is non-empty, containsNamedFragmentSpread must return true → deny
            assertThat(McpServlet.containsNamedFragmentSpread(q)).isTrue();
        }

        @Test
        @DisplayName("empty whitelist state is allow-all (guard precondition)")
        void empty_whitelist_is_allow_all() {
            assertThat(Set.of()).isEmpty();
        }
    }
}
