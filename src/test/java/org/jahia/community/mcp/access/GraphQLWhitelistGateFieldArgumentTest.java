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

/**
 * graphql-dxm-provider's {@code fieldFilter}, {@code fieldSorter} and {@code fieldGrouping} arguments name
 * fields as strings, outside the selection the gate walks. With an explicit whitelist, the field that
 * carries one must be covered by an entry (so every field it can name is covered too), not merely lead
 * towards a deeper entry.
 */
class GraphQLWhitelistGateFieldArgumentTest {

    private static final String NARROW_ENTRY = "query:jcr.nodesByQuery.nodes.uuid";

    private GraphQLWhitelistGate gate;
    private McpConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        gate = new GraphQLWhitelistGate(config);
    }

    private void whitelist(final Set<String> entries) {
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(entries));
    }

    private Optional<Denial> evaluate(final String query) {
        return gate.evaluate(query, "root", "127.0.0.1");
    }

    private static String nodesByQueryWith(final String argument) {
        return "{ jcr { nodesByQuery(query: \"SELECT * FROM [jnt:page]\", " + argument + ") "
                + "{ nodes { uuid } } } }";
    }

    @ParameterizedTest(name = "{0} under a narrow entry is refused")
    @ValueSource(strings = {
        "fieldFilter: {filters: [{fieldName: \"displayName\", evaluation: EQUAL, value: \"x\"}]}",
        "fieldSorter: {fieldName: \"displayName\", sortType: ASC}",
        "fieldGrouping: {fieldName: \"displayName\", groups: [\"x\"], groupingType: START}"
    })
    void fieldNamingArgumentUnderNarrowEntryIsRefused(final String argument) {
        whitelist(Set.of(NARROW_ENTRY));

        final Optional<Denial> denial = evaluate(nodesByQueryWith(argument));

        assertThat(denial).isPresent();
        assertThat(denial.get().kind()).isEqualTo(Denial.Kind.NOT_WHITELISTED);
        assertThat(denial.get().message())
                .contains("'query:jcr.nodesByQuery'")
                .contains("fieldFilter, fieldSorter or fieldGrouping")
                .doesNotContain("displayName");
    }

    @Test
    @DisplayName("an argument passed as a variable is refused too: presence decides, not the value")
    void variableArgumentIsRefused() {
        whitelist(Set.of(NARROW_ENTRY));

        final Optional<Denial> denial = evaluate("query($f: InputFieldFiltersInput) { jcr { "
                + "nodesByQuery(query: \"q\", fieldFilter: $f) { nodes { uuid } } } }");

        assertThat(denial).map(Denial::kind).contains(Denial.Kind.NOT_WHITELISTED);
    }

    @Test
    @DisplayName("an alias does not hide the field that carries the argument")
    void aliasedFieldIsRefused() {
        whitelist(Set.of(NARROW_ENTRY));

        final Optional<Denial> denial = evaluate("{ jcr { probe: nodesByQuery(query: \"q\", "
                + "fieldSorter: {fieldName: \"displayName\", sortType: ASC}) { nodes { uuid } } } }");

        assertThat(denial).map(Denial::message).hasValueSatisfying(
                m -> assertThat(m).contains("'query:jcr.nodesByQuery'"));
    }

    @Test
    @DisplayName("an argument inside an inline fragment is found")
    void argumentInsideInlineFragmentIsRefused() {
        whitelist(Set.of(NARROW_ENTRY));

        final Optional<Denial> denial = evaluate("{ jcr { ... on JCRQuery { nodesByQuery(query: \"q\", "
                + "fieldSorter: {fieldName: \"displayName\", sortType: ASC}) { nodes { uuid } } } } }");

        assertThat(denial).map(Denial::kind).contains(Denial.Kind.NOT_WHITELISTED);
    }

    @Test
    @DisplayName("a deeper field carrying the argument is checked at its own path")
    void deeperFieldIsCheckedAtItsOwnPath() {
        whitelist(Set.of("query:jcr.nodeByPath.uuid"));

        final Optional<Denial> denial = evaluate("{ jcr { nodeByPath(path: \"/sites\") { uuid children("
                + "fieldFilter: {filters: [{fieldName: \"name\", value: \"x\"}]}) { nodes { uuid } } } } }");

        // children is outside the entry, so the plain path check refuses it first.
        assertThat(denial).map(Denial::message).hasValueSatisfying(
                m -> assertThat(m).contains("'query:jcr.nodeByPath.children' is not in the whitelist"));
    }

    @ParameterizedTest(name = "entry {0} covers the field, so the argument is allowed")
    @ValueSource(strings = {"query:jcr", "query:jcr.nodesByQuery"})
    void entryCoveringTheFieldAllowsTheArgument(final String entry) {
        whitelist(Set.of(entry));

        assertThat(evaluate(nodesByQueryWith(
                "fieldFilter: {filters: [{fieldName: \"displayName\", value: \"x\"}]}"))).isEmpty();
    }

    @Test
    @DisplayName("the read-only default is unchanged: every query outside admin already passes")
    void readOnlyDefaultAllowsTheArgument() {
        whitelist(Collections.emptySet());

        assertThat(evaluate(nodesByQueryWith(
                "fieldSorter: {fieldName: \"displayName\", sortType: ASC}"))).isEmpty();
    }

    @Test
    @DisplayName("without a field-naming argument, the container rule still applies")
    void containerRuleUnchangedWithoutTheArgument() {
        whitelist(Set.of(NARROW_ENTRY));

        assertThat(evaluate("{ jcr { nodesByQuery(query: \"q\") { nodes { uuid } } } }")).isEmpty();
    }

    @Test
    @DisplayName("a covered deeper field may carry the argument")
    void coveredDeeperFieldIsAllowed() {
        whitelist(Set.of("query:jcr.nodeByPath"));

        assertThat(evaluate("{ jcr { nodeByPath(path: \"/sites\") { children(fieldFilter: {filters: "
                + "[{fieldName: \"name\", value: \"x\"}]}) { nodes { uuid } } } } }")).isEmpty();
    }

    @Test
    @DisplayName("a second operation of the document carrying the argument is refused")
    void secondOperationIsChecked() {
        whitelist(Set.of(NARROW_ENTRY));

        final Optional<Denial> denial = evaluate("query A { jcr { nodesByQuery(query: \"q\") { nodes { uuid } } } } "
                + "query B { jcr { nodesByQuery(query: \"q\", fieldSorter: {fieldName: \"name\", sortType: ASC}) "
                + "{ nodes { uuid } } } }");

        assertThat(denial).map(Denial::kind).contains(Denial.Kind.NOT_WHITELISTED);
    }

    @Test
    @DisplayName("introspection paths are ignored, as by the path check")
    void introspectionPathIsIgnored() {
        whitelist(Set.of(NARROW_ENTRY));

        assertThat(evaluate("{ __type(name: \"JCRNode\", fieldSorter: {fieldName: \"name\"}) { name } }"))
                .isEmpty();
    }

    @Test
    @DisplayName("other arguments of the same field are not affected")
    void otherArgumentsAreNotAffected() {
        whitelist(Set.of(NARROW_ENTRY));

        assertThat(evaluate("{ jcr { nodesByQuery(query: \"q\", limit: 5, offset: 0) "
                + "{ nodes { uuid } } } }")).isEmpty();
    }
}
