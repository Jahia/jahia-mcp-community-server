package org.jahia.community.mcp.access;

import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PRB2-1: the read-only default must not expose the privileged {@code admin} query namespace. */
class GraphQLWhitelistGateProtectedQueryTest {

    private static final String MESSAGE_START =
            "Operation not allowed: queries under 'admin' require an explicit query:admin<.path> whitelist entry";

    private McpConfigService config;
    private GraphQLWhitelistGate gate;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        gate = new GraphQLWhitelistGate(config);
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Collections.emptySet()));
    }

    private Optional<String> check(final String query) {
        return gate.check(query, "alice", "1.2.3.4");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ admin { jahia { configuration(pid: \"x\") { flatProperties { name value } } } } }",
            "{ admin { version } }",
            "{ a: admin { jahia { version } } }",
            "{ ... on Query { admin { jahia { version } } } }",
            "{ ... { ... on Query { admin { cluster { isActivated } } } } }",
            "query Q { jcr { nodeByPath(path: \"/\") { name } } admin { jahia { system { os } } } }",
            "query A { currentUser { name } } query B { admin { datetime } }"
    })
    @DisplayName("empty whitelist: any query reaching the admin namespace is refused (aliases, inline fragments, siblings)")
    void admin_queries_refused_in_read_only_default(final String query) {
        final Optional<String> result = check(query);

        assertThat(result).isPresent();
        assertThat(result.get()).startsWith(MESSAGE_START).contains("'query:admin");
    }

    @Test
    @DisplayName("empty whitelist: other query roots stay allowed")
    void other_queries_still_allowed() {
        assertThat(check("{ jcr { nodeByPath(path: \"/\") { name } } currentUser { name } "
                + "workflow { __typename } tag { __typename } }")).isEmpty();
        assertThat(check("{ __schema { types { name } } __typename }")).isEmpty();
        assertThat(check("{ adminish { x } }")).as("only the admin segment is protected, not a prefix match")
                .isEmpty();
    }

    @Test
    @DisplayName("a field merely named admin below another root is not the protected namespace")
    void nested_admin_field_is_not_protected() {
        assertThat(check("{ jcr { admin { name } } }")).isEmpty();
    }

    @Test
    @DisplayName("an explicit query:admin entry re-enables the namespace (whitelist mode)")
    void explicit_entry_reenables() {
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of("query:admin")));

        assertThat(check("{ admin { jahia { configuration(pid: \"x\") { flatProperties { name } } } } }")).isEmpty();
    }

    @Test
    @DisplayName("an explicit query:admin.<path> entry re-enables only that subpath")
    void explicit_subpath_entry() {
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of("query:admin.jahia.version")));

        assertThat(check("{ admin { jahia { version { release } } } }")).isEmpty();
        assertThat(check("{ admin { jahia { configuration(pid: \"x\") { pid } } } }")).isPresent();
    }
}
