package org.jahia.community.mcp.graphql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpMutationValidationTest {

    @Test
    @DisplayName("saveSettings rejects a malformed entry before touching the OSGi configuration")
    void rejects_malformed_entry() {
        assertThat(new McpMutation().saveSettings(List.of("query:jcr", "subscription:x"))).isFalse();
    }

    @Test
    @DisplayName("saveSettings rejects a blank entry")
    void rejects_blank_entry() {
        assertThat(new McpMutation().saveSettings(List.of(" "))).isFalse();
    }
}
