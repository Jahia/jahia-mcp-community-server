package org.jahia.community.mcp.access;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hostile documents against the real gate: fragment spreads, always-denied roots, directives, aliases. */
class GraphQLWhitelistGateHostileTest {

    private static final String SPREAD_DENIAL = "Operation not allowed: named fragment spreads are not permitted "
            + "(use inline fragments '... on Type { ... }' instead)";
    private static final String DELETE = "jcr { deleteNode(pathOrId: \"/sites/x\") }";

    private Logger gateLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        gateLogger = (Logger) LoggerFactory.getLogger(GraphQLWhitelistGate.class);
        appender = new ListAppender<>();
        appender.start();
        gateLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        gateLogger.detachAppender(appender);
    }

    private static Optional<String> check(final String query, final String... entries) {
        final McpConfigService config = mock(McpConfigService.class);
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of(entries)));
        return new GraphQLWhitelistGate(config).check(query, "alice", "1.2.3.4");
    }

    // ── PRB-1: named fragment spreads ─────────────────────────────────────────

    @Test
    @DisplayName("PRB-1: the exact bypass, mutation { ...F } in read-only mode, is denied")
    void read_only_mutation_spread_denied() {
        assertThat(check("mutation { ...F } fragment F on Mutation { " + DELETE + " }")).contains(SPREAD_DENIAL);
    }

    @Test
    @DisplayName("PRB-1: communityMcp reached through a spread is denied, read-only and with a broad whitelist")
    void community_mcp_through_spread_denied() {
        final String q = "mutation { ...F } fragment F on Mutation { communityMcp { saveSettings(whitelist: [\"mutation:jcr\"]) } }";

        assertThat(check(q)).contains(SPREAD_DENIAL);
        assertThat(check(q, "mutation:communityMcp", "mutation:jcr")).contains(SPREAD_DENIAL);
    }

    @Test
    @DisplayName("PRB-1: a spread next to __typename or inside nested fields and inline fragments is denied")
    void nested_spreads_denied() {
        assertThat(check("mutation { __typename ...F } fragment F on Mutation { " + DELETE + " }")).contains(SPREAD_DENIAL);
        assertThat(check("mutation { jcr { mutateNode(pathOrId: \"/x\") { ...F } } } fragment F on M { delete }",
                "mutation:jcr")).contains(SPREAD_DENIAL);
        assertThat(check("{ a { b { c { ... on T { d { ...F } } } } } } fragment F on U { x }")).contains(SPREAD_DENIAL);
    }

    @Test
    @DisplayName("PRB-1: spreads are refused in queries too, in read-only mode and under a whitelist")
    void query_spreads_denied() {
        final String q = "{ admin { ...F } } fragment F on AdminQueries { jahia { isAlive } }";

        assertThat(check(q)).contains(SPREAD_DENIAL);
        assertThat(check(q, "query:admin")).contains(SPREAD_DENIAL);
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("fragment spreads"));
    }

    @Test
    @DisplayName("PRB-1: a subscription made only of a spread is denied")
    void subscription_spread_denied() {
        assertThat(check("subscription { ...S } fragment S on Subscription { x }")).contains(SPREAD_DENIAL);
    }

    @Test
    @DisplayName("PRB-1: aliases do not hide a spread, nor the field behind an alias")
    void aliases_and_spreads() {
        assertThat(check("mutation { safe: __typename x: ...F } fragment F on Mutation { " + DELETE + " }")).isPresent();
        assertThat(check("mutation { safe: ...F } fragment F on Mutation { " + DELETE + " }")).isPresent();
        assertThat(check("mutation { a: jcr { b: deleteNode(pathOrId: \"/x\") } }"))
                .as("the real field name is policed, not the alias").isPresent();
        assertThat(check("{ a: communityMcp { b: skills { name } } }", "query:communityMcp")).isEmpty();
        assertThat(check("mutation { communityMcp: jcr { saveSkill: addNode(parentPathOrId:\"/\", name:\"x\", "
                + "primaryNodeType:\"t\") { __typename } } }", "mutation:jcr")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "mutation { ...F @include(if: false) } fragment F on Mutation { jcr { deleteNode(pathOrId: \"/x\") } }",
        "mutation { ...F @skip(if: true) } fragment F on Mutation { jcr { deleteNode(pathOrId: \"/x\") } }",
        "mutation { ... @include(if: false) { ...F } } fragment F on Mutation { jcr { deleteNode(pathOrId: \"/x\") } }"})
    @DisplayName("PRB-1: directives never hide a spread")
    void directives_do_not_hide_spreads(final String q) {
        assertThat(check(q)).contains(SPREAD_DENIAL);
    }

    @Test
    @DisplayName("PRB-1: directives never hide a field either")
    void directives_do_not_hide_fields() {
        assertThat(check("mutation { jcr @include(if: false) { deleteNode(pathOrId: \"/x\") @skip(if: true) } }")).isPresent();
        assertThat(check("mutation { communityMcp @skip(if: true) { saveSettings(whitelist: []) } }",
                "mutation:communityMcp")).isPresent();
    }

    @Test
    @DisplayName("PRB-1: a fragment definition that is never used is harmless")
    void unused_fragment_definition_allowed() {
        assertThat(check("{ currentUser { name } } fragment F on User { name }")).isEmpty();
        assertThat(check("mutation { __typename } fragment F on Mutation { " + DELETE + " }")).isEmpty();
    }

    @Test
    @DisplayName("PRB-1: with several operations, one spread or one denied field denies the document")
    void multiple_operations() {
        assertThat(check("query A { currentUser { name } } query B { ...F } fragment F on Query { x }"))
                .contains(SPREAD_DENIAL);
        assertThat(check("query A { currentUser { name } } mutation B { " + DELETE + " }")).isPresent();
        assertThat(check("query A { currentUser { name } } query B { jcr { nodeByPath(path: \"/\") { name } } }")).isEmpty();
    }

    // ── PRB-2: admin.jahia.configuration ──────────────────────────────────────

    @Test
    @DisplayName("PRB-2: mutation:admin.jahia.configuration is denied whatever the whitelist says")
    void configuration_mutation_never_allowed() {
        final String q = "mutation { admin { jahia { configuration(pid: \"org.jahia.community.mcp\") "
                + "{ value(name: \"whitelist\", value: \"mutation:jcr\") } } } }";

        for (final String entry : new String[]{"mutation:admin", "mutation:admin.jahia",
            "mutation:admin.jahia.configuration", "mutation:admin.jahia.configuration.value"}) {
            assertThat(check(q, entry)).as(entry).isPresent().get().asString()
                    .contains("'mutation:admin.jahia.configuration' can never be run through community.graphql.execute");
        }
        assertThat(check(q)).isPresent();
    }

    @Test
    @DisplayName("PRB-2: a sibling admin mutation is not caught by the configuration deny rule")
    void sibling_admin_mutation_still_whitelistable() {
        assertThat(check("mutation { admin { jahia { project { __typename } } } }", "mutation:admin.jahia.project"))
                .isEmpty();
    }

    // ── fail closed / log hygiene ─────────────────────────────────────────────

    @Test
    @DisplayName("an unparseable document is logged by position only, never by quoted content")
    void parse_failure_does_not_log_document_text() {
        assertThat(check("mutation { createUser(password: \"s3cr3t-p4ss\" ")).isPresent();

        assertThat(appender.list).isNotEmpty()
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("s3cr3t"))
                .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("line 1"));
    }

    @Test
    @DisplayName("CR/LF in the user name cannot forge log lines")
    void username_is_cleaned_in_logs() {
        final McpConfigService config = mock(McpConfigService.class);
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of()));

        new GraphQLWhitelistGate(config).check("mutation { " + DELETE + " }", "eve\nINFO forged", "1.1.1.1\r\n");

        assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("\n").doesNotContain("\r"));
        assertThat(gateLogger.getLevel()).isNotEqualTo(Level.OFF);
    }
}
