package org.jahia.community.mcp.graphql;

import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.osgi.BundleUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * saveSettings is the only writer of the whitelist the MCP gate enforces. The static
 * {@code BundleUtils.getOsgiService} lookup is stubbed so every test runs against an available
 * ConfigurationAdmin and inspects what reaches {@link Configuration#update(Dictionary)}.
 */
class McpMutationSaveSettingsTest {

    private static final String PID = "org.jahia.community.mcp";

    @Nested
    @DisplayName("persistence through ConfigurationAdmin")
    class Persistence {

        private MockedStatic<BundleUtils> bundleUtils;
        private ConfigurationAdmin configAdmin;
        private Configuration configuration;

        @BeforeEach
        void setUp() throws IOException {
            configAdmin = mock(ConfigurationAdmin.class);
            configuration = mock(Configuration.class);
            when(configAdmin.getConfiguration(PID, null)).thenReturn(configuration);
            bundleUtils = mockStatic(BundleUtils.class);
            bundleUtils.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(configAdmin);
        }

        @AfterEach
        void tearDown() {
            bundleUtils.close();
        }

        @SuppressWarnings("unchecked")
        private Dictionary<String, Object> persisted() throws IOException {
            ArgumentCaptor<Dictionary<String, Object>> captor = ArgumentCaptor.forClass(Dictionary.class);
            verify(configuration).update(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("valid prefixed and legacy entries are persisted as one comma-separated value")
        void persists_valid_entries() throws IOException {
            Boolean saved = new McpMutation().saveSettings(List.of("query:jcr", "mutation:jcr.deleteNode", "currentUser"));

            assertThat(saved).isTrue();
            assertThat(persisted().get("whitelist")).isEqualTo("query:jcr,mutation:jcr.deleteNode,currentUser");
        }

        @Test
        @DisplayName("existing properties (e.g. FileInstall's .cfg binding) are kept, only the whitelist changes")
        void keeps_existing_properties() throws IOException {
            Dictionary<String, Object> existing = new Hashtable<>();
            existing.put("felix.fileinstall.filename", "file:/karaf/etc/org.jahia.community.mcp.cfg");
            existing.put("whitelist", "query:old");
            when(configuration.getProperties()).thenReturn(existing);

            assertThat(new McpMutation().saveSettings(List.of("query:currentUser"))).isTrue();

            Dictionary<String, Object> props = persisted();
            assertThat(props.get("felix.fileinstall.filename")).isEqualTo("file:/karaf/etc/org.jahia.community.mcp.cfg");
            assertThat(props.get("whitelist")).isEqualTo("query:currentUser");
        }

        @Test
        @DisplayName("a configuration without properties yet is created with the whitelist")
        void creates_properties_when_absent() throws IOException {
            when(configuration.getProperties()).thenReturn(null);

            assertThat(new McpMutation().saveSettings(List.of("mutation:x"))).isTrue();

            assertThat(persisted().get("whitelist")).isEqualTo("mutation:x");
        }

        @Test
        @DisplayName("an empty list is persisted as an empty value (the documented allow-all state)")
        void empty_list_persists_empty_value() throws IOException {
            assertThat(new McpMutation().saveSettings(List.of())).isTrue();

            assertThat(persisted().get("whitelist")).isEqualTo("");
        }

        @Test
        @DisplayName("what saveSettings persists is read back by McpConfigService as the same entries")
        void round_trip_through_config_service() throws Exception {
            List<String> entries = List.of("query:jcr.nodeByPath", "mutation:jcr.deleteNode");
            new McpMutation().saveSettings(entries);

            McpConfigService service = new McpConfigService();
            service.updated(persisted());

            assertThat(service.getWhitelist()).containsExactlyElementsOf(entries);
        }

        // Rejections are asserted WITH ConfigurationAdmin available: without it saveSettings returns
        // false anyway, so a rejection test outside this setup would pass even with validation removed.

        @Test
        @DisplayName("a malformed entry anywhere in the list means nothing is persisted")
        void malformed_entry_persists_nothing() throws IOException {
            assertThat(new McpMutation().saveSettings(List.of("query:ok", "query:not ok"))).isFalse();

            verify(configuration, never()).update(any());
        }

        @Test
        @DisplayName("an unknown operation type is rejected and nothing is persisted")
        void unknown_operation_type_rejected() throws IOException {
            assertThat(new McpMutation().saveSettings(List.of("query:jcr", "subscription:x"))).isFalse();

            verify(configuration, never()).update(any());
        }

        @Test
        @DisplayName("a blank entry is rejected and nothing is persisted")
        void blank_entry_rejected() throws IOException {
            assertThat(new McpMutation().saveSettings(List.of(" "))).isFalse();

            verify(configuration, never()).update(any());
        }

        @Test
        @DisplayName("ConfigurationAdmin unavailable → false, nothing persisted")
        void config_admin_unavailable() throws IOException {
            bundleUtils.when(() -> BundleUtils.getOsgiService(ConfigurationAdmin.class, null)).thenReturn(null);

            assertThat(new McpMutation().saveSettings(List.of("query:x"))).isFalse();

            verify(configAdmin, never()).getConfiguration(any(), isNull());
        }

        @Test
        @DisplayName("an I/O failure while updating the configuration → false")
        void update_failure() throws IOException {
            doThrow(new IOException("disk full")).when(configuration).update(any());

            assertThat(new McpMutation().saveSettings(List.of("query:x"))).isFalse();
        }
    }
}
