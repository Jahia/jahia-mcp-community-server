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
 * module's only real-time visibility into blocked operations.
 */
class GraphQLWhitelistGateTest {

    private GraphQLWhitelistGate gate;
    private McpConfigService config;
    private Logger gateLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);

        gate = new GraphQLWhitelistGate(config);

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

    private void whitelist(final Set<String> entries) {
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(entries));
    }

    private List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("empty whitelist is read-only: a query is allowed and nothing is logged per call")
    void empty_whitelist_allows_queries_without_warning() {
        whitelist(Collections.emptySet());

        Optional<String> result = gate.check("{ admin { jahia } }", "alice", "1.2.3.4");

        assertThat(result).isEmpty();
        assertThat(warnMessages()).as("read-only mode is logged on config change, not per call").isEmpty();
    }

    @Test
    @DisplayName("empty whitelist: a mutation is refused with an actionable message")
    void empty_whitelist_refuses_mutations() {
        whitelist(Collections.emptySet());

        Optional<String> result = gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4");

        assertThat(result).isPresent();
        assertThat(result.get())
                .startsWith("Operation not allowed: mutations require an explicit mutation:<path> whitelist entry")
                .contains("mutation:jcr");
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("alice").contains("1.2.3.4"));
    }

    @Test
    @DisplayName("empty whitelist: a subscription is refused")
    void empty_whitelist_refuses_subscriptions() {
        whitelist(Collections.emptySet());

        assertThat(gate.check("subscription { jcr { x } }", "alice", "1.2.3.4").orElseThrow())
                .contains("subscriptions are not supported");
    }

    @Test
    @DisplayName("empty whitelist: a mutation hidden next to a query in one document is refused")
    void empty_whitelist_refuses_mixed_document() {
        whitelist(Collections.emptySet());

        assertThat(gate.check("query A { currentUser { name } } mutation B { jcr { deleteNode(pathOrId: \"/x\") } }",
                "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("empty whitelist: introspection and named fragment spreads in a query are fine")
    void empty_whitelist_allows_introspection_and_fragments() {
        whitelist(Collections.emptySet());

        assertThat(gate.check("{ __schema { types { name } } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("{ admin { ...F } } fragment F on AdminQueries { jahia { isAlive } }", "alice", "1.2.3.4"))
                .isEmpty();
    }

    @Test
    @DisplayName("empty whitelist: an unparseable or null document is refused")
    void empty_whitelist_refuses_unparseable() {
        whitelist(Collections.emptySet());

        assertThat(gate.check("{ jcr { ", "alice", "1.2.3.4")).isPresent();
        assertThat(gate.check(null, null, null)).isPresent();
    }

    @Test
    @DisplayName("mutation:communityMcp is denied even when whitelisted (the data plane cannot rewrite its guard)")
    void community_mcp_mutations_always_denied() {
        whitelist(Set.of("mutation:communityMcp", "mutation:communityMcp.saveSettings", "mutation:jcr"));

        Optional<String> result = gate.check(
                "mutation { communityMcp { saveSettings(whitelist: []) } }", "alice", "1.2.3.4");

        assertThat(result).isPresent();
        assertThat(result.get()).contains("mutation:communityMcp").contains("can never be run");
        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4"))
                .as("other whitelisted mutations still pass").isEmpty();
    }

    @Test
    @DisplayName("an alias or an inline fragment does not smuggle a communityMcp mutation past the deny rule")
    void community_mcp_mutation_alias_and_fragment_denied() {
        whitelist(Set.of("mutation:jcr", "mutation:communityMcp"));

        assertThat(gate.check("mutation { jcr: communityMcp { saveSkill(name: \"a\", content: \"b\") } }",
                "alice", "1.2.3.4")).isPresent();
        assertThat(gate.check("mutation { ... on Mutation { communityMcp { deleteSkill(name: \"a\") } } }",
                "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("the communityMcp QUERY namespace is not covered by the mutation deny rule")
    void community_mcp_query_not_denied() {
        whitelist(Set.of("query:communityMcp"));

        assertThat(gate.check("{ communityMcp { skills { name } } }", "alice", "1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("blocked op → a denial reason and a WARN carries path + user + client IP")
    void blocked_operation_warns_with_context() {
        whitelist(Set.of("currentUser"));

        Optional<String> result = gate.check("{ admin { jahia { isAlive } } }", "alice", "1.2.3.4");

        assertThat(result).as("a non-whitelisted op is blocked").isPresent();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg)
                        .contains("admin")
                        .contains("alice")
                        .contains("1.2.3.4")
                        .contains("is not in the whitelist"));
    }

    @Test
    @DisplayName("whitelisted op → allow (null) and no blocked-op WARN is emitted")
    void allowed_operation_is_permitted() {
        whitelist(Set.of("currentUser"));

        Optional<String> result = gate.check("{ currentUser { name } }", "alice", "1.2.3.4");

        assertThat(result).as("a whitelisted op is permitted").isEmpty();
        assertThat(warnMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("blocked"));
    }

    @Test
    @DisplayName("named fragment spread under an active whitelist → blocked, WARN mentions named fragment")
    void named_fragment_spread_blocked_and_warns() {
        whitelist(Set.of("admin"));

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
        whitelist(Set.of("currentUser"));

        Optional<String> result =
                gate.check("query,{ jcr { nodeByPath(path:\"/\") { name } } }", "alice", "1.2.3.4");

        assertThat(result).as("a comma is an ignored token, not an escape hatch").isPresent();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("jcr"));
    }

    @Test
    @DisplayName("SEC-364 comma before the operation keyword → blocked")
    void sec364_comma_before_keyword_blocked() {
        whitelist(Set.of("currentUser"));

        Optional<String> result =
                gate.check(",query { jcr { nodeByPath(path:\"/\") { name } } }", "alice", "1.2.3.4");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("SEC-364 leading byte-order mark → blocked")
    void sec364_leading_bom_blocked() {
        whitelist(Set.of("currentUser"));

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
        whitelist(Set.of("jcr.nodeByPath"));

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
        whitelist(Set.of("currentUser"));

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
        whitelist(Set.of("currentUser"));

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
        whitelist(Set.of("currentUser"));

        // The parser's own message quotes the offending source; that belongs in the audit log,
        // not in a response the caller can read back.
        Optional<String> result = gate.check("{ topSecretFieldName { ", "alice", "1.2.3.4");

        assertThat(result.orElseThrow()).doesNotContain("topSecretFieldName");
    }

    // ── false-positive guards: the fix must not start refusing legitimate documents ──

    @Test
    @DisplayName("commas inside a selection set remain legal separators, not a block reason")
    void commas_inside_selection_set_allowed() {
        whitelist(Set.of("currentUser"));

        assertThat(gate.check("{ currentUser { name, displayName } }", "alice", "1.2.3.4"))
                .as("commas are ordinary GraphQL punctuation").isEmpty();
    }

    @Test
    @DisplayName("introspection-only document is still permitted under an active whitelist")
    void introspection_only_document_allowed() {
        whitelist(Set.of("currentUser"));

        assertThat(gate.check("{ __schema { types { name } } }", "alice", "1.2.3.4"))
                .as("empty path set after a SUCCESSFUL parse still means 'nothing to police'").isEmpty();
    }

    @Test
    @DisplayName("'#' inside a string argument still does not fracture a whitelisted path")
    void hash_in_string_argument_still_allowed() {
        whitelist(Set.of("jcr"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/sites#main\") { name } } }", "alice", "1.2.3.4"))
                .isEmpty();
    }

    // ── Operation type (queries vs mutations) ───────────────────────────────────────────────

    @Test
    @DisplayName("a legacy entry allows the query but NOT the same-named mutation")
    void legacy_entry_does_not_allow_mutation() {
        whitelist(Set.of("jcr"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isEmpty();
        Optional<String> mutation = gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4");
        assertThat(mutation).isPresent();
        assertThat(mutation.get()).contains("mutation:jcr");
    }

    @Test
    @DisplayName("query:<path> allows the query only")
    void query_entry_allows_query_only() {
        whitelist(Set.of("query:jcr"));

        assertThat(gate.check("query { jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("mutation:<path> allows the mutation only, down to the exact nested field")
    void mutation_entry_allows_mutation_only() {
        whitelist(Set.of("mutation:jcr.deleteNode"));

        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { addNode(parentPathOrId: \"/\", name: \"x\", primaryNodeType: \"jnt:text\") { uuid } } }",
                "alice", "1.2.3.4")).as("sibling mutation").isPresent();
        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4"))
                .as("same root field as a query").isPresent();
    }

    @Test
    @DisplayName("a document mixing an allowed query and a non-allowed mutation is refused")
    void mixed_document_refused_on_mutation() {
        whitelist(Set.of("query:jcr"));

        Optional<String> result = gate.check(
                "query A { jcr { nodeByPath(path: \"/\") { name } } } mutation B { jcr { deleteNode(pathOrId: \"/x\") } }",
                "alice", "1.2.3.4");

        assertThat(result).isPresent();
        assertThat(result.get()).contains("mutation:jcr");
    }

    @Test
    @DisplayName("subscriptions are never allowed by query/mutation entries")
    void subscription_refused() {
        whitelist(Set.of("query:jcr", "mutation:jcr"));

        assertThat(gate.check("subscription { jcr { x } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("a whitelist containing only malformed entries denies everything (fail closed, not allow-all)")
    void only_malformed_entries_deny_all() {
        whitelist(Set.of("subscription:jcr", "foo:bar"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("the { } shorthand is a query: allowed by a query: entry, never by a mutation: entry")
    void shorthand_is_a_query() {
        whitelist(Set.of("mutation:currentUser"));
        assertThat(gate.check("{ currentUser { name } }", "alice", "1.2.3.4")).isPresent();

        whitelist(Set.of("query:currentUser"));
        assertThat(gate.check("{ currentUser { name } }", "alice", "1.2.3.4")).isEmpty();
    }

    @Test
    @DisplayName("an alias does not disguise a mutation field")
    void alias_on_mutation() {
        whitelist(Set.of("mutation:jcr.addNode"));

        assertThat(gate.check("mutation { jcr { addNode: deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4"))
                .isPresent();
    }

    @Test
    @DisplayName("an inline fragment inside a mutation is attributed to the mutation")
    void inline_fragment_in_mutation() {
        whitelist(Set.of("query:jcr"));

        assertThat(gate.check("mutation { ... on Mutation { jcr { deleteNode(pathOrId: \"/x\") } } }", "alice", "1.2.3.4"))
                .isPresent();
    }

    @Test
    @DisplayName("prefix case does not matter: MUTATION:jcr is a mutation entry")
    void prefix_case_insensitive() {
        whitelist(Set.of("MUTATION:jcr"));

        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { name } } }", "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("entries of different depths: a shallow query: entry and a deep mutation: entry coexist")
    void mixed_depth_entries() {
        whitelist(Set.of("query:jcr", "mutation:jcr.deleteNode"));

        assertThat(gate.check("{ jcr { nodeByPath(path: \"/\") { children { nodes { name } } } } }", "alice", "1.2.3.4"))
                .isEmpty();
        assertThat(gate.check("mutation { jcr { deleteNode(pathOrId: \"/x\") } }", "alice", "1.2.3.4")).isEmpty();
        assertThat(gate.check("mutation { jcr { addNode(parentPathOrId: \"/\", name: \"x\", primaryNodeType: \"jnt:text\") { uuid } } }",
                "alice", "1.2.3.4")).isPresent();
    }

    @Test
    @DisplayName("mutation { __typename } selects no real field and passes, like introspection")
    void mutation_typename_only() {
        whitelist(Set.of("query:jcr"));

        assertThat(gate.check("mutation { __typename }", "alice", "1.2.3.4")).isEmpty();
    }
}
