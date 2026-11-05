package org.jahia.community.mcp.access;

import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.services.content.JCRNodeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Caller gate shared by every community tool.
 *
 * <p>mcp-server admits any authenticated non-guest user, so the community tools enforce their own
 * gate: the caller must hold the {@value #REQUIRED_PERMISSION} JCR permission on the repository
 * root (a server administrator). This is a JCR check on the caller's own session on purpose —
 * {@code PermissionService.hasPermission(...)} cannot be used here, because tools run on MCP
 * worker threads where no security-filter scope context is bound, and in that state it
 * returns {@code true} for everything.</p>
 */
public final class CommunityMcpAccess {

    static final String REQUIRED_PERMISSION = "admin";
    private static final Logger LOGGER = LoggerFactory.getLogger(CommunityMcpAccess.class);
    private static final String UNKNOWN_IP = "unknown";

    private CommunityMcpAccess() {
    }

    /**
     * @return empty when the caller may use the community tools, otherwise a {@code forbidden}
     *         result to return as-is. Fails closed on any repository error.
     */
    public static Optional<ToolResult> requireAdministrator(final ToolContext ctx) {
        boolean allowed;
        try {
            allowed = ctx.withUserSession(session -> {
                final Node root = session.getRootNode();
                return root instanceof JCRNodeWrapper
                        && ((JCRNodeWrapper) root).hasPermission(REQUIRED_PERMISSION);
            });
        } catch (RepositoryException | RuntimeException ex) {
            LOGGER.warn("Community MCP permission check failed for user='{}'; denying", ctx.username(), ex);
            allowed = false;
        }
        if (allowed) {
            return Optional.empty();
        }
        LOGGER.warn("Community MCP tool refused: user='{}' is not a server administrator, ip='{}'",
                ctx.username(), clientIp(ctx));
        return Optional.of(ToolResult.error(ToolError.CODE_FORBIDDEN,
                "The community tools are restricted to server administrators.",
                "Use the built-in mcp-server tools instead, or ask an administrator to run this operation."));
    }

    /**
     * TCP peer address of the current request. X-Forwarded-For is intentionally not trusted;
     * configure Tomcat's RemoteIpValve when running behind a reverse proxy.
     */
    public static String clientIp(final ToolContext ctx) {
        return ctx.request().map(HttpServletRequest::getRemoteAddr).orElse(UNKNOWN_IP);
    }
}
