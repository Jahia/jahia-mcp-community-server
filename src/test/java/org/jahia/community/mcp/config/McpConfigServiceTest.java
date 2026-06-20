package org.jahia.community.mcp.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Dictionary;
import java.util.Hashtable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for McpConfigService.parseList / getWhitelist.
 * No OSGi container needed — pure logic test.
 */
class McpConfigServiceTest {

    private McpConfigService service;

    @BeforeEach
    void setUp() {
        service = new McpConfigService();
    }

    @Test
    @DisplayName("null dictionary produces empty whitelist")
    void null_dictionary_produces_empty_whitelist() throws Exception {
        service.updated(null);
        assertThat(service.getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("dictionary with null whitelist value produces empty whitelist")
    void null_whitelist_value_produces_empty() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        // no "whitelist" key → value will be null
        service.updated(dict);
        assertThat(service.getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("empty whitelist string produces empty whitelist")
    void empty_string_produces_empty_whitelist() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "");
        service.updated(dict);
        assertThat(service.getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("whitespace-only whitelist string produces empty whitelist")
    void whitespace_only_produces_empty_whitelist() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "   ");
        service.updated(dict);
        assertThat(service.getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("single entry whitelist is parsed correctly")
    void single_entry_parsed() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "jcr");
        service.updated(dict);
        assertThat(service.getWhitelist()).containsExactly("jcr");
    }

    @Test
    @DisplayName("multiple comma-separated entries are all parsed")
    void multiple_entries_parsed() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "jcr,currentUser,admin.jahia.isAlive");
        service.updated(dict);
        assertThat(service.getWhitelist())
                .containsExactlyInAnyOrder("jcr", "currentUser", "admin.jahia.isAlive");
    }

    @Test
    @DisplayName("entries with surrounding whitespace are trimmed")
    void whitespace_around_entries_trimmed() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", " jcr , currentUser , admin ");
        service.updated(dict);
        assertThat(service.getWhitelist())
                .containsExactlyInAnyOrder("jcr", "currentUser", "admin");
    }

    @Test
    @DisplayName("duplicate entries are deduplicated (LinkedHashSet)")
    void duplicate_entries_deduplicated() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "jcr,jcr,currentUser");
        service.updated(dict);
        assertThat(service.getWhitelist())
                .containsExactlyInAnyOrder("jcr", "currentUser")
                .hasSize(2);
    }

    @Test
    @DisplayName("returned set is unmodifiable")
    void returned_set_is_unmodifiable() throws Exception {
        Dictionary<String, Object> dict = new Hashtable<>();
        dict.put("whitelist", "jcr");
        service.updated(dict);
        assertThat(service.getWhitelist())
                .isUnmodifiable();
    }

    @Test
    @DisplayName("updated called twice replaces previous whitelist")
    void second_update_replaces_first() throws Exception {
        Dictionary<String, Object> dict1 = new Hashtable<>();
        dict1.put("whitelist", "jcr");
        service.updated(dict1);

        Dictionary<String, Object> dict2 = new Hashtable<>();
        dict2.put("whitelist", "currentUser");
        service.updated(dict2);

        assertThat(service.getWhitelist()).containsExactly("currentUser");
    }
}
