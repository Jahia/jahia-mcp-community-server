package org.jahia.community.mcp.tools;

import org.jahia.community.mcp.access.CallerFixture;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds a mocked {@link ToolContext} whose caller is (or is not) a server administrator. */
public final class ToolContextFixture {

    public final ToolContext ctx = mock(ToolContext.class);
    public final HttpServletRequest request = mock(HttpServletRequest.class);
    public final List<String> auditEvents = new ArrayList<>();
    public final List<Map<String, Object>> auditDetails = new ArrayList<>();

    private ToolContextFixture(final boolean administrator) {
        // The caller bound on the thread by mcp-server; released after each test.
        CallerFixture.bindForCurrentTest(administrator ? "root" : "editor", administrator);
        when(ctx.username()).thenReturn(administrator ? "root" : "editor");
        when(ctx.locale()).thenReturn(Locale.ENGLISH);
        when(ctx.request()).thenReturn(Optional.of(request));
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");
        when(request.getHeader("Authorization")).thenReturn("APIToken abc");
        when(ctx.audit()).thenReturn((event, details) -> {
            auditEvents.add(event);
            auditDetails.add(details);
        });

        final JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.hasPermission("admin")).thenReturn(administrator);
        final Session session = mock(Session.class);
        try {
            when(session.getRootNode()).thenReturn(root);
            when(ctx.withUserSession(any(ToolContext.JcrCallback.class)))
                    .thenAnswer(inv -> ((ToolContext.JcrCallback<?>) inv.getArgument(0)).doInJcr(session));
        } catch (RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ToolContextFixture administrator() {
        return new ToolContextFixture(true);
    }

    public static ToolContextFixture nonAdministrator() {
        return new ToolContextFixture(false);
    }
}
