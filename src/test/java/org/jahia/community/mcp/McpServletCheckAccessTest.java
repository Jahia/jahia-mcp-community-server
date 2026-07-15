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
}
