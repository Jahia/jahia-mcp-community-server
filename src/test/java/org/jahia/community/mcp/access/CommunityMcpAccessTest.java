package org.jahia.community.mcp.access;

import org.jahia.community.mcp.tools.ToolContextFixture;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityMcpAccessTest {

    @Test
    @DisplayName("server administrator (admin permission on /) is allowed")
    void administrator_is_allowed() {
        assertThat(CommunityMcpAccess.requireAdministrator(ToolContextFixture.administrator().ctx)).isEmpty();
    }

    @Test
    @DisplayName("caller without admin permission on / is refused with a forbidden error")
    void non_administrator_is_forbidden() {
        Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ToolContextFixture.nonAdministrator().ctx);

        assertThat(denied).isPresent();
        assertThat(denied.get().error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
    }

    @Test
    @DisplayName("a repository failure during the permission check fails closed")
    void repository_failure_fails_closed() throws RepositoryException {
        ToolContext ctx = mock(ToolContext.class);
        when(ctx.username()).thenReturn("alice");
        when(ctx.withUserSession(any(ToolContext.JcrCallback.class))).thenThrow(new RepositoryException("boom"));

        Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);

        assertThat(denied).isPresent();
        assertThat(denied.get().error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
    }

    @Test
    @DisplayName("a root node that is not a Jahia JCRNodeWrapper cannot be checked → denied")
    void non_wrapper_root_denied() throws RepositoryException {
        ToolContext ctx = mock(ToolContext.class);
        javax.jcr.Session session = mock(javax.jcr.Session.class);
        when(session.getRootNode()).thenReturn(mock(javax.jcr.Node.class));
        when(ctx.withUserSession(any(ToolContext.JcrCallback.class)))
                .thenAnswer(inv -> ((ToolContext.JcrCallback<?>) inv.getArgument(0)).doInJcr(session));

        assertThat(CommunityMcpAccess.requireAdministrator(ctx)).isPresent();
    }

    @Test
    @DisplayName("the gate checks exactly the 'admin' permission")
    void checks_admin_permission() throws RepositoryException {
        ToolContext ctx = mock(ToolContext.class);
        org.jahia.services.content.JCRNodeWrapper root = mock(org.jahia.services.content.JCRNodeWrapper.class);
        when(root.hasPermission("jcr:read")).thenReturn(true);
        javax.jcr.Session session = mock(javax.jcr.Session.class);
        when(session.getRootNode()).thenReturn(root);
        when(ctx.withUserSession(any(ToolContext.JcrCallback.class)))
                .thenAnswer(inv -> ((ToolContext.JcrCallback<?>) inv.getArgument(0)).doInJcr(session));

        assertThat(CommunityMcpAccess.requireAdministrator(ctx)).as("read access is not enough").isPresent();
        org.mockito.Mockito.verify(root).hasPermission("admin");
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
