package org.jahia.community.mcp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WhitelistEntriesTest {

    @Test
    @DisplayName("query: and mutation: prefixes map to operation-qualified internal paths")
    void prefixed_entries_are_qualified() {
        assertThat(WhitelistEntries.normalize(Set.of("query:jcr.nodeByPath", "mutation:jcr")))
                .containsExactlyInAnyOrder("query.jcr.nodeByPath", "mutation.jcr");
    }

    @Test
    @DisplayName("a legacy unprefixed entry is query-only — mutations must be allowed explicitly")
    void legacy_entry_is_query_only() {
        assertThat(WhitelistEntries.normalize(Set.of("jcr"))).containsExactly("query.jcr");
    }

    @ParameterizedTest(name = "\"{0}\" grants nothing")
    @ValueSource(strings = {"subscription:x", "foo:jcr", "query:", "mutation:", ":jcr", "query:jcr..x",
            "query:1abc", "query:jcr.", "jcr nodeByPath", ""})
    @DisplayName("malformed entries and unknown operation types are dropped (fail closed)")
    void malformed_entries_dropped(final String entry) {
        assertThat(WhitelistEntries.normalize(Set.of(entry))).isEmpty();
        assertThat(WhitelistEntries.isValid(entry)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" is valid")
    @ValueSource(strings = {"jcr", "admin.jahia.isAlive", "query:currentUser", "mutation:jcr.deleteNode", "_x.y_2"})
    @DisplayName("well-formed prefixed and legacy entries are valid")
    void valid_entries(final String entry) {
        assertThat(WhitelistEntries.isValid(entry)).isTrue();
    }

    @Test
    @DisplayName("legacy entries are reported so operators can migrate them")
    void legacy_entries_reported() {
        Set<String> raw = new LinkedHashSet<>(List.of("jcr", "query:currentUser", "mutation:x"));
        assertThat(WhitelistEntries.legacyEntries(raw)).containsExactly("jcr");
    }

    @Test
    @DisplayName("internal paths render back in the user-facing prefix syntax")
    void display_form() {
        assertThat(WhitelistEntries.display("mutation.jcr.deleteNode")).isEqualTo("mutation:jcr.deleteNode");
        assertThat(WhitelistEntries.display("query.currentUser")).isEqualTo("query:currentUser");
    }
}
