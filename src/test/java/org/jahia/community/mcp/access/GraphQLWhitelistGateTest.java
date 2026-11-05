package org.jahia.community.mcp.access;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jahia.community.mcp.config.McpConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F10 — audit-logging + decision tests for the REAL {@link GraphQLWhitelistGate#check} method
 * (package-private after the visibility refactor). A mocked {@link McpConfigService} drives the
 * two branches; a logback {@link ListAppender} captures the WARN audit trail, which is the
 * module's only real-time visibility into ALLOW-ALL mode and blocked operations.
 */
class GraphQLWhitelistGateTest {

    private GraphQLWhitelistGate gate;
    private McpConfigService config;
    private Logger gateLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);

        gate = new GraphQLWhitelistGate();
        gate.setMcpConfigService(config);

        gateLogger = (Logger) LoggerFactory.getLogger(GraphQLWhitelistGate.class);
        gateLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        gateLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        gateLogger.detachAppender(appender);
    }

    private List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("empty whitelist → allow-all (null) and a WARN announces ALLOW-ALL mode")
    void empty_whitelist_allows_all_and_warns() {
        when(config.getWhitelist()).thenReturn(Collections.emptySet());

        Optional<String> result = gate.check("{ admin { jahia } }", "alice", "1.2.3.4");

        assertThat(result).as("allow-all returns empty (permit)").isEmpty();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg).contains("ALLOW-ALL"));
    }

    @Test
    @DisplayName("blocked op → a denial reason and a WARN carries path + user + client IP")
    void blocked_operation_warns_with_context() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        Optional<String> result = gate.check("{ admin { jahia { isAlive } } }", "alice", "1.2.3.4");

        assertThat(result).as("a non-whitelisted op is blocked").isPresent();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg)
                        .contains("admin")
                        .contains("alice")
                        .contains("1.2.3.4")
                        .contains("not in whitelist"));
    }

    @Test
    @DisplayName("whitelisted op → allow (null) and no blocked-op WARN is emitted")
    void allowed_operation_is_permitted() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        Optional<String> result = gate.check("{ currentUser { name } }", "alice", "1.2.3.4");

        assertThat(result).as("a whitelisted op is permitted").isEmpty();
        assertThat(warnMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("blocked"));
    }

    @Test
    @DisplayName("named fragment spread under an active whitelist → blocked, WARN mentions named fragment")
    void named_fragment_spread_blocked_and_warns() {
        when(config.getWhitelist()).thenReturn(Set.of("admin"));

        String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { jahia { isAlive } }";
        Optional<String> result = gate.check(q, "alice", "1.2.3.4");

        assertThat(result).as("fail-closed on named fragment spreads").isPresent();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg).contains("named fragment"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEC-364 / GHSA-9vrc-45qw-x759 — whitelist bypass via a document the gate could not parse
    //
    // checkAccess used to read an EMPTY extracted path set as "this document requests nothing I
    // need to police", but the hand-written scanner also produced an empty set for every document
    // it simply could not walk. Each arm below is VALID GraphQL that the old scanner mis-handled,
    // and every one of them executed the operation the whitelist had just refused.
    //
    // Each arm is paired with the positive control further down (a whitelisted op still passes) —
    // without that pairing a "blocked" assertion proves only that the gate blocks everything.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SEC-364 leading comma before the selection set → blocked (the reported PoC)")
    void sec364_leading_comma_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        Optional<String> result =
                gate.check("query,{ jcr { nodeByPath(path:\"/\") { name } } }", "alice", "1.2.3.4");

        assertThat(result).as("a comma is an ignored token, not an escape hatch").isPresent();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("jcr"));
    }

    @Test
    @DisplayName("SEC-364 comma before the operation keyword → blocked")
    void sec364_comma_before_keyword_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        Optional<String> result =
                gate.check(",query { jcr { nodeByPath(path:\"/\") { name } } }", "alice", "1.2.3.4");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("SEC-364 leading byte-order mark → blocked")
    void sec364_leading_bom_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        // U+FEFF is an ignored token in the GraphQL grammar but is NOT Character.isWhitespace, so
        // the old skipWS() stopped dead on it. Either branch may deny now (parsed-and-blocked, or
        // refused as unparseable) — what matters is that neither permits.
        Optional<String> result =
                gate.check("﻿{ jcr { nodeByPath(path:\"/\") { name } } }", "alice", "1.2.3.4");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("SEC-364 unbalanced paren in a string argument no longer hides a sibling field")
    void sec364_unbalanced_paren_in_string_arg_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("jcr.nodeByPath"));

        // This is the arm that "treat an empty path set as deny" would NOT have caught: the old
        // scanner extracted {jcr, jcr.nodeByPath} — non-empty, and both whitelisted — because the
        // '(' inside the path argument unbalanced skipBalanced()'s counter, which then ran to
        // end-of-document and never saw the `admin` sibling.
        String q = "{ jcr { nodeByPath(path: \"/x(\") { name } } admin { jahia { isAlive } } }";
        Optional<String> result = gate.check(q, "alice", "1.2.3.4");

        assertThat(result).as("the hidden sibling must be seen and refused").isPresent();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("admin"));
    }

    @Test
    @DisplayName("SEC-364 multi-operation document: the second operation is inspected too")
    void sec364_multi_operation_second_op_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        // Previously only op A was extracted; op B survived solely because executeGraphQL forwards
        // no operationName, so graphql-java rejected the whole document. The gate now refuses op B
        // on its own merits instead of relying on that.
        String q = "query A { currentUser { name } } query B { admin { jahia { isAlive } } }";
        Optional<String> result = gate.check(q, "alice", "1.2.3.4");

        assertThat(result).isPresent();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("admin"));
    }

    @Test
    @DisplayName("unparseable document under an active whitelist → blocked, WARN carries user + IP")
    void unparseable_document_blocked_and_warns() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        Optional<String> result = gate.check("{ jcr { ", "alice", "1.2.3.4");

        assertThat(result).as("fail closed when the document cannot be accounted for").isPresent();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg)
                        .contains("could not be parsed")
                        .contains("alice")
                        .contains("1.2.3.4"));
    }

    @Test
    @DisplayName("parse-failure text returned to the client does not echo the document back")
    void unparseable_result_does_not_echo_document() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        // The parser's own message quotes the offending source; that belongs in the audit log,
        // not in a response the caller can read back.
        Optional<String> result = gate.check("{ topSecretFieldName { ", "alice", "1.2.3.4");

        assertThat(result.orElseThrow()).doesNotContain("topSecretFieldName");
    }

    // ── false-positive guards: the fix must not start refusing legitimate documents ──

    @Test
    @DisplayName("commas inside a selection set remain legal separators, not a block reason")
    void commas_inside_selection_set_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        assertThat(gate.check("{ currentUser { name, displayName } }", "alice", "1.2.3.4"))
                .as("commas are ordinary GraphQL punctuation").isEmpty();
    }

    @Test
    @DisplayName("introspection-only document is still permitted under an active whitelist")
    void introspection_only_document_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        assertThat(gate.check("{ __schema { types { name } } }", "alice", "1.2.3.4"))
                .as("empty path set after a SUCCESSFUL parse still means 'nothing to police'").isEmpty();
    }

    @Test
    @DisplayName("'#' inside a string argument still does not fracture a whitelisted path")
    void hash_in_string_argument_still_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("jcr"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/sites#main\") { name } } }", "alice", "1.2.3.4"))
                .isEmpty();
    }

    // ── Operation type (queries vs mutations) ───────────────────────────────────────────────

    @Test
    @DisplayName("a legacy entry allows the query but NOT the same-named mutation")
    void legacy_entry_does_not_allow_mutation() {
        when(config.getWhitelist()).thenReturn(Set.of("jcr"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isEmpty();
        Optional<String> mutation = gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4");
        assertThat(mutation).isPresent();
        assertThat(mutation.get()).contains("mutation:jcr");
    }

    @Test
    @DisplayName("query:<path> allows the query only")
    void query_entry_allows_query_only() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr"));

        assertThat(gate.check("query { jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("mutation:<path> allows the mutation only, down to the exact nested field")
    void mutation_entry_allows_mutation_only() {
        when(config.getWhitelist()).thenReturn(Set.of("mutation:jcr.deleteNode"));

        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { addNode(parentPathOrId: \"/\", name: \"x\", primaryNodeType: \"jnt:text\") { uuid } } }",
                "alice", "1.2.3.4")).as("sibling mutation").isPresent();
        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4"))
                .as("same root field as a query").isPresent();
    }

    @Test
    @DisplayName("a document mixing an allowed query and a non-allowed mutation is refused")
    void mixed_document_refused_on_mutation() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr"));

        Optional<String> result = gate.check(
                "query A { jcr { nodeByPath(path: \"/\") { name } } } mutation B { jcr { deleteNode(pathOrId: \"/x\") } }",
                "alice", "1.2.3.4");

        assertThat(result).isPresent();
        assertThat(result.get()).contains("mutation:jcr");
    }

    @Test
    @DisplayName("subscriptions are never allowed by query/mutation entries")
    void subscription_refused() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr", "mutation:jcr"));

        assertThat(gate.check("subscription { jcr { x } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("a whitelist containing only malformed entries denies everything (fail closed, not allow-all)")
    void only_malformed_entries_deny_all() {
        when(config.getWhitelist()).thenReturn(Set.of("subscription:jcr", "foo:bar"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("the { } shorthand is a query: allowed by a query: entry, never by a mutation: entry")
    void shorthand_is_a_query() {
        when(config.getWhitelist()).thenReturn(Set.of("mutation:currentUser"));
        assertThat(gate.check("{ currentUser { name } }", "alice", "1.2.3.4")).isPresent();

        when(config.getWhitelist()).thenReturn(Set.of("query:currentUser"));
        assertThat(gate.check("{ currentUser { name } }", "alice", "1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("an alias does not disguise a mutation field")
    void alias_on_mutation() {
        when(config.getWhitelist()).thenReturn(Set.of("mutation:jcr.addNode"));

        assertThat(gate.check("mutation { jcr { addNode: deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4"))
                .isPresent();
    }

    @Test
    @DisplayName("an inline fragment inside a mutation is attributed to the mutation")
    void inline_fragment_in_mutation() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr"));

        assertThat(gate.check("mutation { ... on Mutation { jcr { deleteNode(pathOrId: \"/x\") } } }", "alice", "1.2.3.4"))
                .isPresent();
    }

    @Test
    @DisplayName("prefix case does not matter: MUTATION:jcr is a mutation entry")
    void prefix_case_insensitive() {
        when(config.getWhitelist()).thenReturn(Set.of("MUTATION:jcr"));

        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("entries of different depths: a shallow query: entry and a deep mutation: entry coexist")
    void mixed_depth_entries() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr", "mutation:jcr.deleteNode"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { children { nodes { name } } } } }", "alice", "1.2.3.4"))
                .isEmpty();
        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { addNode(parentPathOrId: \"/\", name: \"x\", primaryNodeType: \"jnt:text\") { uuid } } }",
                "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("mutation { __typename } selects no real field and passes, like introspection")
    void mutation_typename_only() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr"));

        assertThat(gate.check("mutation { __typename }", "alice", "1.2.3.4")).isEmpty();
    }
}
