package org.jahia.community.mcp.access;

import org.jahia.community.mcp.tools.ToolContextFixture;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMcpAccessTest {

    private static void assertForbidden(final Optional<ToolResult> denied) {
        assertThat(denied).isPresent();
        assertThat(denied.get().error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
    }

    @Test
    @DisplayName("server administrator (admin permission on /) is allowed")
    void administrator_is_allowed() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            assertThat(CommunityMcpAccess.requireAdministrator(caller.ctx)).isEmpty();
        }
    }

    @Test
    @DisplayName("the session is opened for the bound JahiaUser itself, not for a name lookup")
    void session_is_opened_for_the_bound_user() throws RepositoryException {
        try (CallerFixture caller = CallerFixture.administrator()) {
            CommunityMcpAccess.requireAdministrator(caller.ctx);

            verify(caller.template).doExecute(org.mockito.ArgumentMatchers.eq(caller.user), org.mockito.ArgumentMatchers.eq("default"),
                    any(Locale.class), any(JCRCallback.class));
        }
    }

    @Test
    @DisplayName("caller without admin permission on / is refused with a forbidden error")
    void non_administrator_is_forbidden() {
        try (CallerFixture caller = CallerFixture.nonAdministrator()) {
            assertForbidden(CommunityMcpAccess.requireAdministrator(caller.ctx));
            verify(caller.root).hasPermission("admin");
        }
    }

    @Test
    @DisplayName("nobody bound on the thread → denied, whatever the context claims")
    void unbound_user_denied() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            when(caller.sessionFactory.getCurrentUser()).thenReturn(null);

            assertForbidden(CommunityMcpAccess.requireAdministrator(caller.ctx));
            assertThat(CommunityMcpAccess.boundCaller(caller.ctx)).isEmpty();
        }
    }

    @Test
    @DisplayName("bound user differing from ctx.username() → denied")
    void identity_mismatch_denied() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            JahiaUser other = mock(JahiaUser.class);
            when(other.getUsername()).thenReturn("mallory");
            when(caller.sessionFactory.getCurrentUser()).thenReturn(other);

            assertForbidden(CommunityMcpAccess.requireAdministrator(caller.ctx));
        }
    }

    @Test
    @DisplayName("boundCaller returns the bound user when it is the context user")
    void bound_caller_matches() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            assertThat(CommunityMcpAccess.boundCaller(caller.ctx)).containsSame(caller.user);
        }
    }

    @Test
    @DisplayName("a repository failure during the permission check fails closed")
    void repository_failure_fails_closed() throws RepositoryException {
        try (CallerFixture caller = CallerFixture.administrator()) {
            when(caller.template.doExecute(org.mockito.ArgumentMatchers.eq(caller.user), any(String.class),
                    any(Locale.class), any(JCRCallback.class))).thenThrow(new RepositoryException("boom"));

            assertForbidden(CommunityMcpAccess.requireAdministrator(caller.ctx));
        }
    }

    @Test
    @DisplayName("a runtime failure during the permission check fails closed")
    void runtime_failure_fails_closed() throws RepositoryException {
        try (CallerFixture caller = CallerFixture.administrator()) {
            when(caller.template.doExecute(org.mockito.ArgumentMatchers.eq(caller.user), any(String.class),
                    any(Locale.class), any(JCRCallback.class))).thenThrow(new IllegalStateException("boom"));

            assertForbidden(CommunityMcpAccess.requireAdministrator(caller.ctx));
        }
    }

    @Test
    @DisplayName("client IP comes from the TCP peer of the current request, 'unknown' without one")
    void client_ip_from_request() {
        assertThat(CommunityMcpAccess.clientIp(ToolContextFixture.administrator().ctx)).isEqualTo("10.0.0.7");

        ToolContext noRequest = mock(ToolContext.class);
        when(noRequest.request()).thenReturn(Optional.empty());
        assertThat(CommunityMcpAccess.clientIp(noRequest)).isEqualTo("unknown");
    }
}
