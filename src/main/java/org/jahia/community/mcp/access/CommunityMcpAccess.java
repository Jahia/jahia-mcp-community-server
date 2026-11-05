package org.jahia.community.mcp.access;

import org.jahia.community.mcp.LogSupport;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.usermanager.JahiaUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Caller gate shared by every community tool.
 *
 * <p>mcp-server admits any authenticated non-guest user, so the community tools enforce their own
 * gate: the caller must hold the {@value #REQUIRED_PERMISSION} JCR permission on the repository
 * root (a server administrator). The check runs on a session of the {@link JahiaUser} bound on the
 * worker thread by mcp-server, which must be the context user. This is a JCR check on purpose —
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
     * The Jahia user mcp-server bound on the current thread for this call, provided it is the user
     * the context claims ({@code ctx.username()}). Empty when nobody is bound or the two disagree —
     * callers must then fail closed, never fall back to a username lookup.
     */
    public static Optional<JahiaUser> boundCaller(final ToolContext ctx) {
        final JahiaUser bound = JCRSessionFactory.getInstance().getCurrentUser();
        if (bound == null || ctx.username() == null || !ctx.username().equals(bound.getUsername())) {
            return Optional.empty();
        }
        return Optional.of(bound);
    }

    /**
     * @return empty when the caller may use the community tools, otherwise a {@code forbidden}
     *         result to return as-is. Fails closed on any repository error and when the bound
     *         thread user is missing or is not the context user.
     */
    public static Optional<ToolResult> requireAdministrator(final ToolContext ctx) {
        final String username = LogSupport.clean(ctx.username());
        boolean allowed = false;
        try {
            final Optional<JahiaUser> caller = boundCaller(ctx);
            if (caller.isPresent()) {
                // The session is opened for the bound JahiaUser itself (with its realm), not for a
                // user re-resolved from a bare name.
                allowed = JCRTemplate.getInstance().doExecute(caller.get(), ctx.workspace(), ctx.locale(),
                        session -> session.getRootNode().hasPermission(REQUIRED_PERMISSION));
            } else {
                LOGGER.warn("Community MCP caller identity is not bound for user='{}'; denying", username);
            }
        } catch (RepositoryException | RuntimeException ex) {
            LOGGER.warn("Community MCP permission check failed for user='{}'; denying", username, ex);
        }
        if (allowed) {
            return Optional.empty();
        }
        final String ip = clientIp(ctx);
        LOGGER.warn("Community MCP tool refused: user='{}' is not a server administrator, ip='{}'", username, ip);
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
