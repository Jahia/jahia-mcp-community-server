package org.jahia.community.mcp.access;

import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.usermanager.JahiaUser;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Test support: binds a caller the way mcp-server does for a tool call. Opens thread-local static
 * mocks of {@link JCRSessionFactory} (the bound current user) and {@link JCRTemplate} (the session
 * opened for that user, whose root node answers {@code hasPermission("admin")}). Use in
 * try-with-resources; the {@link #ctx} mock reports the same user name.
 */
public final class CallerFixture implements AutoCloseable {

    private static final List<CallerFixture> OPEN = new ArrayList<>();

    public final ToolContext ctx = mock(ToolContext.class);
    public final JahiaUser user = mock(JahiaUser.class);
    public final JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
    public final JCRTemplate template = mock(JCRTemplate.class);
    public final JCRNodeWrapper root = mock(JCRNodeWrapper.class);
    private final MockedStatic<JCRSessionFactory> factoryStatic;
    private final MockedStatic<JCRTemplate> templateStatic;
    private boolean closed;

    private CallerFixture(final String username, final boolean administrator) {
        when(ctx.username()).thenReturn(username);
        when(ctx.workspace()).thenReturn("default");
        when(ctx.locale()).thenReturn(Locale.ENGLISH);
        when(ctx.request()).thenReturn(Optional.empty());
        when(user.getUsername()).thenReturn(username);
        when(root.hasPermission("admin")).thenReturn(administrator);
        try {
            final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
            when(session.getRootNode()).thenReturn(root);
            when(template.doExecute(eq(user), any(), any(), any(JCRCallback.class)))
                    .thenAnswer(inv -> ((JCRCallback<?>) inv.getArgument(3)).doInJCR(session));
        } catch (RepositoryException e) {
            throw new IllegalStateException(e);
        }
        when(sessionFactory.getCurrentUser()).thenReturn(user);
        factoryStatic = mockStatic(JCRSessionFactory.class);
        factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
        templateStatic = mockStatic(JCRTemplate.class);
        templateStatic.when(JCRTemplate::getInstance).thenReturn(template);
    }

    /** A bound server administrator named {@code root}. */
    public static CallerFixture administrator() {
        releaseLeaked();
        return new CallerFixture("root", true);
    }

    /** A bound user named {@code editor} without the admin permission. */
    public static CallerFixture nonAdministrator() {
        releaseLeaked();
        return new CallerFixture("editor", false);
    }

    /**
     * Like {@link #administrator()}/{@link #nonAdministrator()} for tests that cannot use
     * try-with-resources (e.g. shared fixtures). Mockito static mocks are per-thread and must be
     * released: every factory method first releases whatever an earlier test left registered, and
     * tests that open their own static mocks of these classes call {@link #releaseLeaked()} first.
     */
    public static CallerFixture bindForCurrentTest(final String username, final boolean administrator) {
        releaseLeaked();
        final CallerFixture fixture = new CallerFixture(username, administrator);
        OPEN.add(fixture);
        return fixture;
    }

    /** Releases the static mocks of fixtures created by {@link #bindForCurrentTest} and not yet closed. */
    public static void releaseLeaked() {
        for (final CallerFixture fixture : new ArrayList<>(OPEN)) {
            fixture.close();
        }
        OPEN.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        templateStatic.close();
        factoryStatic.close();
    }
}
