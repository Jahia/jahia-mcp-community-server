package org.jahia.community.mcp.access;

import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Privilege-granting mutations are refused whatever the whitelist says, at any depth and under any syntax. */
@DisplayName("gate: never-allowed privilege mutations and hostile documents")
class GraphQLWhitelistGateNeverAllowedTest {

    private static Optional<Denial> evaluate(final String query, final String... entries) {
        final McpConfigService config = mock(McpConfigService.class);
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of(entries)));
        return new GraphQLWhitelistGate(config).evaluate(query, "alice", "1.2.3.4");
    }

    private static void assertNeverAllowed(final String query, final String... entries) {
        final Optional<Denial> denial = evaluate(query, entries);
        assertThat(denial).as(query).isPresent();
        assertThat(denial.get().kind()).as(query).isEqualTo(Denial.Kind.NEVER_ALLOWED);
        assertThat(denial.get().message()).contains("can never be run through community.graphql.execute");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mutation { jcr { mutateNode(pathOrId: \"/\") { grantRoles(principalType: USER, principalName: \"a\", roleNames: [\"x\"]) } } }",
        "mutation { jcr { mutateNode(pathOrId: \"/\") { revokeRoles(principalType: USER, principalName: \"a\", roleNames: [\"x\"]) } } }",
        "mutation { jcr { mutateNode(pathOrId: \"/\") { mutateDescendant(relPath: \"a\") { mutateChildren { grantRoles } } } } }",
        "mutation { a: jcr { b: mutateNode(pathOrId: \"/\") { c: grantRoles } } }",
        "mutation { jcr { mutateNode(pathOrId: \"/\") { ... on GqlJcrNodeMutation { ... { grantRoles } } } } }",
        "mutation { admin { personalApiTokens { createToken(name: \"t\") } } }",
        "mutation { x: admin { y: personalApiTokens { deleteToken(key: \"k\") } } }",
        "mutation { admin { ... on AdminMutation { personalApiTokens { updateToken(key: \"k\") } } } }"})
    @DisplayName("denied in the read-only default and under broad mutation entries")
    void privilege_mutations_are_denied_everywhere(final String query) {
        assertNeverAllowed(query);
        assertNeverAllowed(query, "mutation:jcr", "mutation:admin");
        assertNeverAllowed(query, "mutation:jcr.mutateNode", "query:jcr");
    }

    @Test
    @DisplayName("a role grant hidden behind many levels of nesting is still found")
    void deeply_nested_role_grant_is_found() {
        final StringBuilder doc = new StringBuilder("mutation { jcr { mutateNode(pathOrId: \"/\") { ");
        for (int i = 0; i < 40; i++) {
            doc.append("mutateDescendant(relPath: \"a\") { ");
        }
        doc.append("grantRoles");
        doc.append(" }".repeat(40)).append(" } } }");

        assertNeverAllowed(doc.toString(), "mutation:jcr");
    }

    @Test
    @DisplayName("a second operation or an unrelated allowed query cannot shield a role grant")
    void grant_in_a_second_operation_is_found() {
        assertNeverAllowed("query A { jcr { nodeByPath(path: \"/\") { name } } } "
                + "mutation B { jcr { mutateNode(pathOrId: \"/\") { grantRoles } } }", "query:jcr", "mutation:jcr");
    }

    @Test
    @DisplayName("look-alike fields are not mistaken for privilege mutations")
    void lookalikes_are_not_never_allowed() {
        final Optional<Denial> denial = evaluate(
                "mutation { jcr { mutateNode(pathOrId: \"/\") { grantRolesX } } }", "mutation:jcr");
        assertThat(denial).isEmpty();
        assertThat(evaluate("{ jcr { nodeByPath(path: \"/\") { grantRoles } } }")).isEmpty();
    }

    @Test
    @DisplayName("a syntax error is reported as a PARSE denial with its position, not as a refusal")
    void parse_error_reports_the_position() {
        final Denial denial = evaluate("{ jcr {\n  nodeByPath(path: ) }").get();

        assertThat(denial.kind()).isEqualTo(Denial.Kind.PARSE);
        assertThat(denial.message()).startsWith("The GraphQL document could not be parsed")
                .contains("syntax error at line 2, column ").doesNotStartWith("Operation not allowed");
    }

    @Test
    @DisplayName("a document that exhausts the stack is denied, the Error never escapes")
    void stack_overflow_is_denied() {
        final String doc = "{ " + "a { ".repeat(60_000) + "b" + " }".repeat(60_000) + " }";

        final Optional<Denial> denial = evaluate(doc);

        assertThat(denial).isPresent();
        assertThat(denial.get().kind()).isIn(Denial.Kind.PARSE, Denial.Kind.UNANALYSABLE);
    }

    @Test
    @DisplayName("field names are case-sensitive: Admin is not the protected admin namespace, nor covered by query:admin")
    void field_name_case_is_significant() {
        assertThat(evaluate("{ Admin { jahia } }")).isEmpty();
        assertThat(evaluate("{ ADMIN { jahia } }")).isEmpty();
        assertThat(evaluate("{ Admin { jahia } }", "query:admin")).isPresent();
        assertThat(evaluate("{ admin { jahia } }").get().kind()).isEqualTo(Denial.Kind.PROTECTED_QUERY);
    }

    @Test
    @DisplayName("alias spoofing: the field name decides, never the alias")
    void alias_spoofing_at_check_level() {
        // an alias named admin on an allowed field passes ...
        assertThat(evaluate("{ admin: jcr { nodeByPath(path: \"/\") { name } } }")).isEmpty();
        // ... and an alias hides nothing: jcr aliased over admin is still query:admin
        assertThat(evaluate("{ jcr: admin { jahia { configuration { id } } } }").get().kind())
                .isEqualTo(Denial.Kind.PROTECTED_QUERY);
        assertThat(evaluate("mutation { jcr: communityMcp { saveSettings(whitelist: []) } }").get().kind())
                .isEqualTo(Denial.Kind.NEVER_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{ adm​in { jahia } }",
        "{ аdmin { jahia } }",
        "mutation { jcr { mutateNode(pathOrId: \"/\") { grant​Roles } } }",
        "mutation { ｇrantRoles }"})
    @DisplayName("zero-width and homoglyph characters never form a legal field name: denied")
    void unicode_tricks_are_denied(final String query) {
        assertThat(evaluate(query, "mutation:jcr", "query:jcr")).isPresent();
        assertThat(evaluate(query)).isPresent();
    }

    @Test
    @DisplayName("an inline fragment with a type condition does not hide the configuration mutation")
    void inline_fragment_cannot_hide_configuration_mutation() {
        assertNeverAllowed("mutation { jcr { addNode(parentPathOrId: \"/\", name: \"n\", primaryNodeType: \"t\") "
                + "{ uuid } } ... on Mutation { admin { jahia { configuration(pid: \"x\") { remove } } } } }",
                "mutation:jcr", "mutation:admin");
    }

    @Test
    @DisplayName("subscription entries never make a subscription runnable")
    void subscription_stays_refused() {
        assertThat(evaluate("subscription { x }", "subscription:x").get().kind()).isEqualTo(Denial.Kind.SUBSCRIPTION);
        assertThat(evaluate("subscription { x }", "mutation:x").get().kind()).isEqualTo(Denial.Kind.SUBSCRIPTION);
    }

    @Test
    @DisplayName("typed denial kinds for the common causes")
    void typed_kinds() {
        assertThat(evaluate("mutation { jcr { deleteNode } }").get().kind()).isEqualTo(Denial.Kind.MUTATION_NOT_ALLOWED);
        assertThat(evaluate("mutation { jcr { deleteNode } }", "query:jcr").get().kind())
                .isEqualTo(Denial.Kind.MUTATION_NOT_ALLOWED);
        assertThat(evaluate("{ jcr { x } }", "query:other").get().kind()).isEqualTo(Denial.Kind.NOT_WHITELISTED);
        assertThat(evaluate("{ ...F } fragment F on Query { x }").get().kind()).isEqualTo(Denial.Kind.NAMED_SPREAD);
    }
}
