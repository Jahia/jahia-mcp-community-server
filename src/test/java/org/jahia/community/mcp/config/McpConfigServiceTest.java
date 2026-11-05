package org.jahia.community.mcp.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Parsing, snapshot publication and persistence of {@link McpConfigService}; no OSGi container. */
class McpConfigServiceTest {

    private ConfigurationAdmin configAdmin;
    private Configuration configuration;

    @BeforeEach
    void setUp() throws IOException {
        configAdmin = mock(ConfigurationAdmin.class);
        configuration = mock(Configuration.class);
        when(configAdmin.getConfiguration(McpConfigService.PID, null)).thenReturn(configuration);
    }

    private static McpConfigService.Config config(final String... whitelist) {
        McpConfigService.Config config = mock(McpConfigService.Config.class);
        when(config.whitelist()).thenReturn(whitelist);
        return config;
    }

    private McpConfigService service(final String... whitelist) {
        return new McpConfigService(configAdmin, config(whitelist));
    }

    @Test
    @DisplayName("no entry (empty array, blank or whitespace-only string) is the read-only default")
    void empty_values_are_read_only_default() {
        assertThat(service().snapshot().isReadOnlyDefault()).isTrue();
        assertThat(service("").getWhitelist()).isEmpty();
        assertThat(service("   ").getWhitelist()).isEmpty();
        assertThat(new McpConfigService(configAdmin, null).snapshot().isReadOnlyDefault()).isTrue();
    }

    @Test
    @DisplayName("a null whitelist attribute is also the read-only default")
    void null_attribute() {
        McpConfigService.Config config = mock(McpConfigService.Config.class);
        when(config.whitelist()).thenReturn(null);

        assertThat(new McpConfigService(configAdmin, config).getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("a CSV string and a String[] give the same entries")
    void csv_and_array_forms() {
        assertThat(service(" jcr , currentUser ,admin.jahia.isAlive").getWhitelist())
                .containsExactly("jcr", "currentUser", "admin.jahia.isAlive");
        assertThat(service("jcr", "currentUser", "admin.jahia.isAlive").getWhitelist())
                .containsExactly("jcr", "currentUser", "admin.jahia.isAlive");
    }

    @Test
    @DisplayName("duplicates collapse and empty tokens are ignored")
    void duplicates_and_empty_tokens() {
        assertThat(service("jcr,jcr,currentUser").getWhitelist()).containsExactly("jcr", "currentUser");
        assertThat(service("query:jcr,,mutation:x,").getWhitelist()).containsExactly("query:jcr", "mutation:x");
    }

    @Test
    @DisplayName("a value made only of separators is NOT an empty whitelist (fails closed)")
    void separators_only_is_not_read_only_default() {
        WhitelistSnapshot snapshot = service(" , ,").snapshot();

        assertThat(snapshot.isReadOnlyDefault()).isFalse();
        assertThat(snapshot.normalized()).as("grants nothing").isEmpty();
    }

    @Test
    @DisplayName("the snapshot carries raw entries, normalized entries and the deepest entry depth")
    void snapshot_content() {
        WhitelistSnapshot snapshot = service("jcr", "mutation:admin.jahia.isAlive", "bad entry").snapshot();

        assertThat(snapshot.raw()).containsExactly("jcr", "mutation:admin.jahia.isAlive", "bad entry");
        assertThat(snapshot.normalized()).containsExactly("query.jcr", "mutation.admin.jahia.isAlive");
        assertThat(snapshot.maxDepth()).isEqualTo(4);
        assertThat(snapshot.raw()).isUnmodifiable();
    }

    @Test
    @DisplayName("modified() replaces the snapshot as a whole")
    void modified_replaces_snapshot() {
        McpConfigService service = service("jcr");
        WhitelistSnapshot before = service.snapshot();

        service.modified(config("currentUser"));

        assertThat(before.raw()).containsExactly("jcr");
        assertThat(service.getWhitelist()).containsExactly("currentUser");
    }

    @Test
    @DisplayName("an unchanged configuration is applied again without error (no duplicate log)")
    void unchanged_modified_is_idempotent() {
        McpConfigService service = service("query:jcr");
        service.modified(config("query:jcr"));

        assertThat(service.snapshot()).isEqualTo(WhitelistSnapshot.of(java.util.Set.of("query:jcr")));
    }

    @Test
    @DisplayName("legacy, malformed and never-allowed entries are tolerated (they are only logged)")
    void odd_entries_tolerated() {
        McpConfigService service = service("jcr", "not valid", "mutation:communityMcp.saveSkill");

        assertThat(service.getWhitelist()).hasSize(3);
        assertThat(WhitelistEntries.normalize(service.getWhitelist())).contains("query.jcr");
    }

    // ── saveWhitelist ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Dictionary<String, Object> persisted() throws IOException {
        ArgumentCaptor<Dictionary<String, Object>> captor = ArgumentCaptor.forClass(Dictionary.class);
        verify(configuration).update(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("valid prefixed and legacy entries are persisted as one comma-separated value")
    void persists_valid_entries() throws IOException {
        service().saveWhitelist(List.of("query:jcr", "mutation:jcr.deleteNode", "currentUser"));

        assertThat(persisted().get("whitelist")).isEqualTo("query:jcr,mutation:jcr.deleteNode,currentUser");
    }

    @Test
    @DisplayName("existing properties (FileInstall binding) are kept, only the whitelist changes")
    void keeps_existing_properties() throws IOException {
        Dictionary<String, Object> existing = new Hashtable<>();
        existing.put("felix.fileinstall.filename", "file:/karaf/etc/org.jahia.community.mcp.cfg");
        existing.put("whitelist", "query:old");
        when(configuration.getProperties()).thenReturn(existing);

        service().saveWhitelist(List.of("query:currentUser"));

        Dictionary<String, Object> props = persisted();
        assertThat(props.get("felix.fileinstall.filename")).isEqualTo("file:/karaf/etc/org.jahia.community.mcp.cfg");
        assertThat(props.get("whitelist")).isEqualTo("query:currentUser");
    }

    @Test
    @DisplayName("a configuration without properties yet is created with the whitelist")
    void creates_properties_when_absent() throws IOException {
        when(configuration.getProperties()).thenReturn(null);

        service().saveWhitelist(List.of("mutation:x"));

        assertThat(persisted().get("whitelist")).isEqualTo("mutation:x");
    }

    @Test
    @DisplayName("an empty list is persisted as an empty value (the read-only default)")
    void empty_list_persists_empty_value() throws IOException {
        service().saveWhitelist(List.of());

        assertThat(persisted().get("whitelist")).isEqualTo("");
    }

    @Test
    @DisplayName("what is persisted is read back as the same entries (round trip)")
    void round_trip() throws IOException {
        List<String> entries = List.of("query:jcr.nodeByPath", "mutation:jcr.deleteNode");
        service().saveWhitelist(entries);

        assertThat(service(String.valueOf(persisted().get("whitelist"))).getWhitelist()).containsExactlyElementsOf(entries);
    }

    @Test
    @DisplayName("a malformed entry is rejected with a message naming it, and nothing is persisted")
    void malformed_entry_rejected() throws IOException {
        final McpConfigService service = service();
        final List<String> entries = List.of("query:ok", "query:not ok");
        assertThatThrownBy(() -> service.saveWhitelist(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'query:not ok'");

        verify(configuration, never()).update(any());
    }

    @Test
    @DisplayName("unknown operation type, blank and null entries and a null list are rejected")
    void other_invalid_inputs_rejected() throws IOException {
        McpConfigService service = service();

        final List<String> subscription = List.of("query:jcr", "subscription:x");
        final List<String> blank = List.of(" ");
        final List<String> withNull = java.util.Arrays.asList("query:a", null);
        assertThatThrownBy(() -> service.saveWhitelist(subscription))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subscription:x");
        assertThatThrownBy(() -> service.saveWhitelist(blank)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveWhitelist(withNull))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null");
        assertThatThrownBy(() -> service.saveWhitelist(null)).isInstanceOf(IllegalArgumentException.class);
        verify(configAdmin, never()).getConfiguration(any(), isNull());
    }

    @Test
    @DisplayName("a very long malformed entry is shortened in the message")
    void long_entry_shortened() {
        String longEntry = "q".repeat(500) + " x";

        assertThatThrownBy(() -> service().saveWhitelist(List.of(longEntry)))
                .hasMessageContaining("...").hasMessageNotContaining(longEntry);
    }

    @Test
    @DisplayName("mutation:admin.jahia.configuration entries are refused: they could rewrite this whitelist")
    void configuration_entry_refused() throws IOException {
        final McpConfigService service = service();
        final List<String> entries = List.of("query:jcr", "mutation:admin.jahia.configuration.value");
        assertThatThrownBy(() -> service.saveWhitelist(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin.jahia.configuration").hasMessageContaining("can never be run");

        verify(configuration, never()).update(any());
    }

    @Test
    @DisplayName("mutation:communityMcp entries are refused: the gate would always deny them")
    void never_allowed_entry_refused() throws IOException {
        final McpConfigService service = service();
        final List<String> entries = List.of("mutation:communityMcp.saveSkill");
        assertThatThrownBy(() -> service.saveWhitelist(entries))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("can never be run");

        verify(configuration, never()).update(any());
    }

    @Test
    @DisplayName("an I/O failure while updating the configuration is propagated")
    void update_failure_propagated() throws IOException {
        doThrow(new IOException("disk full")).when(configuration).update(any());

        assertThatThrownBy(() -> service().saveWhitelist(List.of("query:x"))).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("PRB2-14: the component fails closed, its configuration is REQUIRED")
    void component_requires_its_configuration() throws IOException {
        // @Component has CLASS retention (invisible to reflection): read the annotation values from the
        // class file's constant pool, where the enum constant name is stored.
        final String constants;
        try (java.io.InputStream in = McpConfigService.class.getResourceAsStream("McpConfigService.class")) {
            constants = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        assertThat(constants).contains("Lorg/osgi/service/component/annotations/ConfigurationPolicy;")
                .contains("REQUIRE")
                .doesNotContain("OPTIONAL").doesNotContain("IGNORE");
    }

    @Test
    @DisplayName("PRB2-4: CR/LF in a rejected entry cannot forge log lines nor leak into the client message")
    void rejected_entry_is_cleaned() {
        final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(McpConfigService.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final McpConfigService service = service();
            final List<String> hostile = List.of("x\nINFO forged line");
            assertThatThrownBy(() -> service.saveWhitelist(hostile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("\n");
            service("bad\rentry\n");

            assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                    .doesNotContain("\n").doesNotContain("\r"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
