package org.jahia.community.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jahia.community.mcp.access.CallerFixture;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;

import javax.jcr.InvalidItemStateException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Activation removes 1.x local ACL grants below the skills container. */
class McpSkillServiceAclPurgeTest {

    private static final String ROOT = McpSkillService.SKILLS_PATH;

    private CallerFixture caller;
    private JCRSessionWrapper session;
    private JCRNodeWrapper container;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final McpSkillService service = new McpSkillService();

    @BeforeEach
    void setUp() throws RepositoryException {
        caller = CallerFixture.bindForCurrentTest("alice", true);
        session = mock(JCRSessionWrapper.class);
        container = node(ROOT);
        when(session.nodeExists(ROOT)).thenReturn(true);
        when(session.getNode(ROOT)).thenReturn(container);
        when(container.getAclInheritanceBreak()).thenReturn(true);
        when(caller.template.doExecuteWithSystemSession(any(JCRCallback.class)))
                .thenAnswer(inv -> ((JCRCallback<?>) inv.getArgument(0)).doInJCR(session));
        logger = (Logger) LoggerFactory.getLogger(McpSkillService.class);
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        CallerFixture.releaseLeaked();
    }

    private static JCRNodeWrapper node(final String path) {
        final JCRNodeWrapper n = mock(JCRNodeWrapper.class);
        when(n.getPath()).thenReturn(path);
        when(n.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
        return n;
    }

    private static JCRNodeIteratorWrapper iterator(final List<? extends Node> nodes) {
        final Iterator<? extends Node> it = nodes.iterator();
        final JCRNodeIteratorWrapper result = mock(JCRNodeIteratorWrapper.class);
        when(result.hasNext()).thenAnswer(inv -> it.hasNext());
        when(result.nextNode()).thenAnswer(inv -> it.next());
        return result;
    }

    private static Node ace(final String principal, final String... roles) throws RepositoryException {
        final Node ace = mock(Node.class);
        when(ace.isNodeType("jnt:ace")).thenReturn(true);
        final Property principalProp = mock(Property.class);
        when(principalProp.getString()).thenReturn(principal);
        when(ace.hasProperty("j:principal")).thenReturn(true);
        when(ace.getProperty("j:principal")).thenReturn(principalProp);
        final Value[] values = new Value[roles.length];
        for (int i = 0; i < roles.length; i++) {
            values[i] = mock(Value.class);
            when(values[i].getString()).thenReturn(roles[i]);
        }
        final Property rolesProp = mock(Property.class);
        when(rolesProp.getValues()).thenReturn(values);
        when(ace.hasProperty("j:roles")).thenReturn(true);
        when(ace.getProperty("j:roles")).thenReturn(rolesProp);
        return ace;
    }

    /** Gives {@code owner} a j:acl child holding the given ACEs; returns the j:acl node. */
    private static JCRNodeWrapper acl(final JCRNodeWrapper owner, final Node... aces) throws RepositoryException {
        final JCRNodeWrapper acl = node(owner.getPath() + "/j:acl");
        when(owner.hasNode("j:acl")).thenReturn(true);
        when(owner.getNode("j:acl")).thenReturn(acl);
        when(acl.getNodes()).thenAnswer(inv -> iterator(List.of(aces)));
        return acl;
    }

    private static void children(final JCRNodeWrapper parent, final Node... kids) throws RepositoryException {
        when(parent.getNodes()).thenAnswer(inv -> iterator(List.of(kids)));
    }

    private BundleContext bundleContext() {
        final BundleContext context = mock(BundleContext.class);
        final Bundle bundle = mock(Bundle.class);
        when(context.getBundle()).thenReturn(bundle);
        when(bundle.findEntries("skills", "*.md", true)).thenReturn(null);
        return context;
    }

    private List<String> messages(final Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    @DisplayName("1.x upgrade: ACEs on the container, a sub-folder and a skill are removed, the j:acl nodes stay")
    void removes_local_aces_on_container_and_descendants() throws Exception {
        final Node containerAce = ace("g:editors", "editor");
        final Node folderAce = ace("u:bob", "contributor", "editor");
        final Node skillAce = ace("g:authors", "owner");
        final JCRNodeWrapper folder = node(ROOT + "/team");
        final JCRNodeWrapper skill = node(ROOT + "/team/s1");
        final JCRNodeWrapper containerAcl = acl(container, containerAce);
        final JCRNodeWrapper folderAcl = acl(folder, folderAce);
        final JCRNodeWrapper skillAcl = acl(skill, skillAce);
        children(container, containerAcl, folder);
        children(folder, folderAcl, skill);
        children(skill, skillAcl);

        service.activate(bundleContext());

        verify(containerAce).remove();
        verify(folderAce).remove();
        verify(skillAce).remove();
        verify(containerAcl, never()).remove();
        verify(folderAcl, never()).remove();
        verify(container, never()).setAclInheritanceBreak(false);
        verify(session).save();
        assertThat(messages(Level.INFO))
                .anySatisfy(m -> assertThat(m).contains("g:editors").contains("[editor]").contains(ROOT))
                .anySatisfy(m -> assertThat(m).contains("u:bob").contains("[contributor,editor]")
                        .contains(ROOT + "/team"))
                .anySatisfy(m -> assertThat(m).contains("g:authors").contains(ROOT + "/team/s1"))
                .anySatisfy(m -> assertThat(m).contains("Removed 3 local ACL grant(s)"));
    }

    @Test
    @DisplayName("an upgraded container with open inheritance ends with the break set and no grants")
    void upgrade_breaks_inheritance_and_removes_grants() throws Exception {
        when(container.getAclInheritanceBreak()).thenReturn(false);
        final Node containerAce = ace("g:editors", "editor");
        final JCRNodeWrapper containerAcl = acl(container, containerAce);
        children(container, containerAcl);

        service.activate(bundleContext());

        verify(container).setAclInheritanceBreak(true);
        verify(containerAce).remove();
    }

    @Test
    @DisplayName("idempotent: a clean tree is left untouched, nothing is saved or logged as removed")
    void clean_tree_is_left_alone() throws Exception {
        final JCRNodeWrapper folder = node(ROOT + "/default");
        final JCRNodeWrapper containerAcl = acl(container);
        children(container, containerAcl, folder);
        children(folder);

        service.activate(bundleContext());

        verify(session, never()).save();
        assertThat(messages(Level.INFO)).noneSatisfy(m -> assertThat(m).contains("Removed"));
    }

    @Test
    @DisplayName("a non-ACE child of j:acl is not touched")
    void non_ace_children_are_kept() throws Exception {
        final Node other = mock(Node.class);
        when(other.isNodeType("jnt:ace")).thenReturn(false);
        final JCRNodeWrapper containerAcl = acl(container, other);
        children(container, containerAcl);

        service.activate(bundleContext());

        verify(other, never()).remove();
    }

    @Test
    @DisplayName("an ACE without principal or roles is still removed and logged with placeholders")
    void incomplete_ace_removed() throws Exception {
        final Node bare = mock(Node.class);
        when(bare.isNodeType("jnt:ace")).thenReturn(true);
        final JCRNodeWrapper containerAcl = acl(container, bare);
        children(container, containerAcl);

        service.activate(bundleContext());

        verify(bare).remove();
        assertThat(messages(Level.INFO)).anySatisfy(m -> assertThat(m).contains("'?'").contains("[]"));
    }

    @Test
    @DisplayName("a failing save of the purge is logged at ERROR, rolled back and does not abort activation")
    void purge_failure_is_logged_not_fatal() throws Exception {
        final Node containerAce = ace("g:editors", "editor");
        final JCRNodeWrapper containerAcl = acl(container, containerAce);
        children(container, containerAcl);
        doThrow(new InvalidItemStateException("race")).when(session).save();

        assertThatCode(() -> service.activate(bundleContext())).doesNotThrowAnyException();

        verify(session).refresh(false);
        assertThat(messages(Level.ERROR)).anySatisfy(m -> assertThat(m).contains("local ACL grants"));
    }

    @Test
    @DisplayName("a failing refresh after a failed purge is tolerated")
    void refresh_failure_tolerated() throws Exception {
        final Node containerAce = ace("g:editors", "editor");
        final JCRNodeWrapper containerAcl = acl(container, containerAce);
        children(container, containerAcl);
        doThrow(new InvalidItemStateException("race")).when(session).save();
        doThrow(new RepositoryException("down")).when(session).refresh(false);

        assertThatCode(() -> service.activate(bundleContext())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("hostile principal and path text cannot forge log lines")
    void hostile_principal_is_cleaned() throws Exception {
        final Node containerAce = ace("g:evil\nINFO forged", "editor");
        final JCRNodeWrapper containerAcl = acl(container, containerAce);
        children(container, containerAcl);

        service.activate(bundleContext());

        assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("\n"));
    }

    @Test
    @DisplayName("a failed ACL break is an ERROR, never a mere WARN")
    void acl_break_failure_is_error() throws Exception {
        when(container.getAclInheritanceBreak()).thenReturn(false);
        doThrow(new InvalidItemStateException("constraint")).when(container).setAclInheritanceBreak(true);

        service.activate(bundleContext());

        assertThat(messages(Level.ERROR)).anySatisfy(m -> assertThat(m).contains("Could not break ACL inheritance"));
        inOrder(container);
    }
}
