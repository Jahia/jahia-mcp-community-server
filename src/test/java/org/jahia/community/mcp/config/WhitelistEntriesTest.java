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

    @Test
    @DisplayName("the communityMcp mutation namespace is never allowed, queries and siblings are")
    void never_allowed_namespace() {
        assertThat(WhitelistEntries.isNeverAllowed("mutation.communityMcp")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.communityMcp.saveSkill")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.communityMcpX")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("query.communityMcp")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed(null)).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.jahia.configuration")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.jahia.configuration.value")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.jahia.configurationX")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.jahia")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("query.admin.jahia.configuration")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:admin.jahia.configuration")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:communityMcp.saveSettings")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("query:communityMcp")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowedEntry("garbage entry")).isFalse();
    }

    @Test
    @DisplayName("a pathological entry is rejected in linear time (possessive regex)")
    void regex_is_linear() {
        String evil = "a".repeat(50_000) + "!";
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThat(WhitelistEntries.isValid(evil)).isFalse());
    }

    @Test
    @DisplayName("role grants at any depth and the API token mutations are never allowed")
    void privilege_granting_mutations_are_never_allowed() {
        assertThat(WhitelistEntries.isNeverAllowed("mutation.jcr.mutateNode.grantRoles")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.jcr.mutateNode.revokeRoles")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.jcr.mutateNode.mutateDescendant.mutateChildren.grantRoles"))
                .isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.grantRoles")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.personalApiTokens")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.personalApiTokens.createToken")).isTrue();
        // near misses and other operation types are not caught
        assertThat(WhitelistEntries.isNeverAllowed("mutation.jcr.mutateNode.grantRolesX")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.jcr.mutateNode.grantroles")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("query.jcr.nodeByPath.grantRoles")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin.personalApiTokensX")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowed("mutation.admin")).isFalse();
    }

    @Test
    @DisplayName("entries that can only whitelist privilege-granting paths are refused; broad roots are not")
    void entries_for_privilege_granting_paths_are_never_allowed_entries() {
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:jcr.mutateNode.grantRoles")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:jcr.mutateNode.revokeRoles")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:admin.personalApiTokens.createToken")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:admin.personalApiTokens")).isTrue();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:jcr")).isFalse();
        assertThat(WhitelistEntries.isNeverAllowedEntry("mutation:admin")).isFalse();
    }
}
