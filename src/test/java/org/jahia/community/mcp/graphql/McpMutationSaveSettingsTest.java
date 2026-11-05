package org.jahia.community.mcp.graphql;

import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/** saveSettings delegates to {@link McpConfigService#saveWhitelist} and reports why a save failed. */
class McpMutationSaveSettingsTest {

    private MockedStatic<BundleUtils> bundleUtils;
    private McpConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        bundleUtils = mockStatic(BundleUtils.class);
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(config);
    }

    @AfterEach
    void tearDown() {
        bundleUtils.close();
    }

    @Test
    @DisplayName("success returns true and hands the entries to the config service")
    void delegates_and_returns_true() throws IOException {
        List<String> entries = List.of("query:jcr", "mutation:jcr.deleteNode");

        assertThat(new McpMutation().saveSettings(entries)).isTrue();

        verify(config).saveWhitelist(entries);
    }

    @Test
    @DisplayName("a rejected entry surfaces as a GraphQL error carrying the reason")
    void rejected_entry_surfaces_reason() throws IOException {
        doThrow(new IllegalArgumentException("Malformed whitelist entry 'x y'")).when(config).saveWhitelist(List.of("x y"));

        assertThatThrownBy(() -> new McpMutation().saveSettings(List.of("x y")))
                .isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("Malformed whitelist entry 'x y'");
    }

    @Test
    @DisplayName("a storage failure is a generic GraphQL error that does not leak the exception text")
    void storage_failure_is_generic() throws IOException {
        doThrow(new IOException("/secret/path disk full")).when(config).saveWhitelist(List.of("query:x"));

        assertThatThrownBy(() -> new McpMutation().saveSettings(List.of("query:x")))
                .isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("could not be stored")
                .hasMessageNotContaining("/secret/path");
    }

    @Test
    @DisplayName("an unavailable config service is an error, not a silent false")
    void config_service_unavailable() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(null);

        assertThatThrownBy(() -> new McpMutation().saveSettings(List.of("query:x")))
                .isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("not available");
    }
}
