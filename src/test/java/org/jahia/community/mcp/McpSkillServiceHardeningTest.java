package org.jahia.community.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jahia.community.mcp.access.CallerFixture;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;

import javax.jcr.InvalidItemStateException;
import javax.jcr.RepositoryException;
import java.util.stream.Collectors;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit trail, log injection and cluster race behaviour of {@link McpSkillService}. */
class McpSkillServiceHardeningTest {

    private static final String ROOT = McpSkillService.SKILLS_PATH;

    private CallerFixture caller;
    private JCRSessionWrapper session;
    private JCRNodeWrapper container;
    private JCRNodeWrapper skill;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final McpSkillService service = new McpSkillService();

    @BeforeEach
    void setUp() throws RepositoryException {
        caller = CallerFixture.bindForCurrentTest("alice", true);
        session = mock(JCRSessionWrapper.class);
        container = mock(JCRNodeWrapper.class);
        skill = mock(JCRNodeWrapper.class);
        when(session.nodeExists(ROOT)).thenReturn(true);
        when(session.getNode(ROOT)).thenReturn(container);
        when(session.nodeExists(ROOT + "/my-skill")).thenReturn(true);
        when(session.getNode(ROOT + "/my-skill")).thenReturn(skill);
        when(skill.isNodeType("mcp:skill")).thenReturn(true);
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

    private List<String> messages(final Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    @Test
    @DisplayName("saveSkill logs the acting administrator at INFO (JCR only records 'system')")
    void save_is_audited() {
        assertThat(service.saveSkill("my-skill", "n", "d", "c")).isTrue();

        assertThat(messages(Level.INFO)).anySatisfy(m -> assertThat(m).contains("my-skill").contains("'alice'"));
    }

    @Test
    @DisplayName("deleteSkill logs the acting administrator at INFO")
    void delete_is_audited() {
        assertThat(service.deleteSkill("my-skill")).isTrue();

        assertThat(messages(Level.INFO)).anySatisfy(m -> assertThat(m).contains("deleted").contains("'alice'"));
    }

    @Test
    @DisplayName("a refused save or delete is not logged as a success")
    void refused_writes_not_audited_as_success() {
        assertThat(service.saveSkill("bad name", "n", "d", "c")).isFalse();
        assertThat(service.deleteSkill("missing")).isFalse();

        assertThat(messages(Level.INFO)).noneSatisfy(m -> assertThat(m).containsAnyOf("saved by", "deleted by"));
    }

    @Test
    @DisplayName("CR/LF and control characters in a caller-supplied name cannot forge log lines")
    void names_are_cleaned_before_logging() {
        final String hostile = "x\nINFO forged line\r\u0007y";

        assertThat(service.getSkill(hostile)).isNull();
        assertThat(service.saveSkill(hostile, "n", "d", "c")).isFalse();
        assertThat(service.deleteSkill(hostile)).isFalse();
        assertThat(service.getSkill("a//b\n")).isNull();

        assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("\n").doesNotContain("\r").doesNotContain("\u0007"));
        assertThat(messages(Level.WARN)).anySatisfy(m -> assertThat(m).contains("forged line"));
    }

    @Test
    @DisplayName("an unreadable skill name in a repository error is cleaned too")
    void repository_error_name_cleaned() throws Exception {
        when(session.nodeExists(ROOT + "/bad")).thenThrow(new RepositoryException("down"));

        assertThat(service.getSkill("bad")).isNull();
        assertThat(service.saveSkill("bad", "n", "d", "c")).isFalse();
        assertThat(service.deleteSkill("bad")).isFalse();
        assertThat(messages(Level.ERROR)).hasSize(3);
    }

    @Test
    @DisplayName("failed ACL break (cluster race or not) is logged at ERROR and tolerated, the bundled import goes on, retry next time")
    void acl_break_race_tolerated() throws Exception {
        when(container.getAclInheritanceBreak()).thenReturn(false);
        doThrow(new InvalidItemStateException("another node saved first")).when(session).save();
        final BundleContext context = mock(BundleContext.class);
        final Bundle bundle = mock(Bundle.class);
        when(context.getBundle()).thenReturn(bundle);
        when(bundle.findEntries("skills", "*.md", true)).thenReturn(null);

        assertThatCode(() -> service.activate(context)).doesNotThrowAnyException();

        verify(container).setAclInheritanceBreak(true);
        verify(session).refresh(false);
        assertThat(messages(Level.ERROR)).anySatisfy(m -> assertThat(m).contains("retried at the next activation"));
    }

    @Test
    @DisplayName("a failing setAclInheritanceBreak is tolerated the same way")
    void acl_break_set_failure_tolerated() throws Exception {
        when(container.getAclInheritanceBreak()).thenReturn(false);
        doThrow(new InvalidItemStateException("race")).when(container).setAclInheritanceBreak(true);

        assertThat(service.saveSkill("my-skill", "n", "d", "c")).isTrue();
        verify(session).refresh(false);
    }

    @Test
    @DisplayName("LogSupport.actingUser falls back to 'unknown' without a bound user")
    void acting_user_unknown() {
        when(caller.sessionFactory.getCurrentUser()).thenReturn(null);
        assertThat(LogSupport.actingUser()).isEqualTo("unknown");
        when(caller.sessionFactory.getCurrentUser()).thenThrow(new IllegalStateException("no factory"));
        assertThat(LogSupport.actingUser()).isEqualTo("unknown");
        assertThat(LogSupport.clean(null)).isEqualTo("null");
        assertThat(LogSupport.clean("a".repeat(500))).hasSize(203).endsWith("...");
        assertThat(JCRTemplate.getInstance()).isSameAs(caller.template);
    }
}
