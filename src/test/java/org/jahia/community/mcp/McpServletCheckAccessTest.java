package org.jahia.community.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.spec.McpSchema;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F10 — audit-logging + decision tests for the REAL {@link McpServlet#checkAccess} method
 * (package-private after the visibility refactor). A mocked {@link McpConfigService} drives the
 * two branches; a logback {@link ListAppender} captures the WARN audit trail, which is the
 * module's only real-time visibility into ALLOW-ALL mode and blocked operations.
 */
class McpServletCheckAccessTest {

    private McpServlet servlet;
    private McpConfigService config;
    private JahiaUser user;
    private Logger servletLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        user = mock(JahiaUser.class);
        when(user.getName()).thenReturn("alice");

        servlet = new McpServlet();
        servlet.setMcpConfigService(config);

        servletLogger = (Logger) LoggerFactory.getLogger(McpServlet.class);
        servletLogger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        servletLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        servletLogger.detachAppender(appender);
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

        McpSchema.CallToolResult result = servlet.checkAccess("{ admin { jahia } }", user, "1.2.3.4");

        assertThat(result).as("allow-all returns null (permit)").isNull();
        assertThat(warnMessages())
                .anySatisfy(msg -> assertThat(msg).contains("ALLOW-ALL"));
    }

    @Test
    @DisplayName("blocked op → non-null error result and a WARN carries path + user + client IP")
    void blocked_operation_warns_with_context() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        McpSchema.CallToolResult result = servlet.checkAccess("{ admin { jahia { isAlive } } }", user, "1.2.3.4");

        assertThat(result).as("a non-whitelisted op is blocked").isNotNull();
        assertThat(result.isError()).isTrue();
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

        McpSchema.CallToolResult result = servlet.checkAccess("{ currentUser { name } }", user, "1.2.3.4");

        assertThat(result).as("a whitelisted op is permitted").isNull();
        assertThat(warnMessages())
                .noneSatisfy(msg -> assertThat(msg).contains("blocked"));
    }

    @Test
    @DisplayName("named fragment spread under an active whitelist → blocked, WARN mentions named fragment")
    void named_fragment_spread_blocked_and_warns() {
        when(config.getWhitelist()).thenReturn(Set.of("admin"));

        String q = "{ admin { ...AdminFields } } fragment AdminFields on AdminQueries { jahia { isAlive } }";
        McpSchema.CallToolResult result = servlet.checkAccess(q, user, "1.2.3.4");

        assertThat(result).as("fail-closed on named fragment spreads").isNotNull();
        assertThat(result.isError()).isTrue();
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

        McpSchema.CallToolResult result =
                servlet.checkAccess("query,{ jcr { nodeByPath(path:\"/\") { name } } }", user, "1.2.3.4");

        assertThat(result).as("a comma is an ignored token, not an escape hatch").isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("jcr"));
    }

    @Test
    @DisplayName("SEC-364 comma before the operation keyword → blocked")
    void sec364_comma_before_keyword_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        McpSchema.CallToolResult result =
                servlet.checkAccess(",query { jcr { nodeByPath(path:\"/\") { name } } }", user, "1.2.3.4");

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("SEC-364 leading byte-order mark → blocked")
    void sec364_leading_bom_blocked() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        // U+FEFF is an ignored token in the GraphQL grammar but is NOT Character.isWhitespace, so
        // the old skipWS() stopped dead on it. Either branch may deny now (parsed-and-blocked, or
        // refused as unparseable) — what matters is that neither permits.
        McpSchema.CallToolResult result =
                servlet.checkAccess("﻿{ jcr { nodeByPath(path:\"/\") { name } } }", user, "1.2.3.4");

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
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
        McpSchema.CallToolResult result = servlet.checkAccess(q, user, "1.2.3.4");

        assertThat(result).as("the hidden sibling must be seen and refused").isNotNull();
        assertThat(result.isError()).isTrue();
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
        McpSchema.CallToolResult result = servlet.checkAccess(q, user, "1.2.3.4");

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(warnMessages()).anySatisfy(msg -> assertThat(msg).contains("admin"));
    }

    @Test
    @DisplayName("unparseable document under an active whitelist → blocked, WARN carries user + IP")
    void unparseable_document_blocked_and_warns() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        McpSchema.CallToolResult result = servlet.checkAccess("{ jcr { ", user, "1.2.3.4");

        assertThat(result).as("fail closed when the document cannot be accounted for").isNotNull();
        assertThat(result.isError()).isTrue();
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
        McpSchema.CallToolResult result = servlet.checkAccess("{ topSecretFieldName { ", user, "1.2.3.4");

        assertThat(String.valueOf(result.content())).doesNotContain("topSecretFieldName");
    }

    // ── false-positive guards: the fix must not start refusing legitimate documents ──

    @Test
    @DisplayName("commas inside a selection set remain legal separators, not a block reason")
    void commas_inside_selection_set_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        assertThat(servlet.checkAccess("{ currentUser { name, displayName } }", user, "1.2.3.4"))
                .as("commas are ordinary GraphQL punctuation").isNull();
    }

    @Test
    @DisplayName("introspection-only document is still permitted under an active whitelist")
    void introspection_only_document_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("currentUser"));

        assertThat(servlet.checkAccess("{ __schema { types { name } } }", user, "1.2.3.4"))
                .as("empty path set after a SUCCESSFUL parse still means 'nothing to police'").isNull();
    }

    @Test
    @DisplayName("'#' inside a string argument still does not fracture a whitelisted path")
    void hash_in_string_argument_still_allowed() {
        when(config.getWhitelist()).thenReturn(Set.of("jcr"));

        assertThat(servlet.checkAccess("{ jcr { nodeByPath(path: \"/sites#main\") { name } } }", user, "1.2.3.4"))
                .isNull();
    }
}
