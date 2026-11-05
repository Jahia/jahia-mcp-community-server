package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.jahia.settings.SettingsBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/** The communityMcp GraphQL surface: query/mutation behaviour, namespace wiring and access annotations. */
class McpGraphQLTest {

    private MockedStatic<BundleUtils> bundleUtils;
    private McpConfigService config;
    private McpSkillService skills;
    private MockedStatic<SettingsBean> settingsStatic;
    private SettingsBean settingsBean;

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        skills = mock(McpSkillService.class);
        bundleUtils = mockStatic(BundleUtils.class);
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(config);
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(skills);
        settingsBean = mock(SettingsBean.class);
        settingsStatic = mockStatic(SettingsBean.class);
        settingsStatic.when(SettingsBean::getInstance).thenReturn(settingsBean);
    }

    @AfterEach
    void tearDown() {
        bundleUtils.close();
        settingsStatic.close();
    }

    // ── access annotations ────────────────────────────────────────────────────

    @Test
    @DisplayName("every communityMcp operation (query and mutation) requires the 'admin' permission")
    void every_operation_requires_admin() {
        int operations = 0;
        for (Class<?> type : new Class<?>[]{McpQuery.class, McpMutation.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GraphQLField.class)) {
                    operations++;
                    GraphQLRequiresPermission permission = method.getAnnotation(GraphQLRequiresPermission.class);
                    assertThat(permission).as("%s.%s must be permission-gated", type.getSimpleName(), method.getName()).isNotNull();
                    assertThat(permission.value()).isEqualTo("admin");
                }
            }
        }
        assertThat(operations).as("settings, skills, saveSettings, saveSkill, deleteSkill").isEqualTo(5);
    }

    @Test
    @DisplayName("the namespace containers hang under ONE root field each and use the CommunityMcp* type names")
    void namespace_wiring() throws Exception {
        assertThat(McpQuery.class.getAnnotation(GraphQLName.class).value()).isEqualTo("CommunityMcpQuery");
        assertThat(McpMutation.class.getAnnotation(GraphQLName.class).value()).isEqualTo("CommunityMcpMutation");
        assertThat(McpQuery.GqlMcpSettings.class.getAnnotation(GraphQLName.class).value()).isEqualTo("CommunityMcpSettings");
        assertThat(McpQuery.GqlMcpSkill.class.getAnnotation(GraphQLName.class).value()).isEqualTo("CommunityMcpSkill");

        assertThat(McpQueryExtension.class.getAnnotation(GraphQLTypeExtension.class).value()).isEqualTo(DXGraphQLProvider.Query.class);
        assertThat(McpMutationExtension.class.getAnnotation(GraphQLTypeExtension.class).value()).isEqualTo(DXGraphQLProvider.Mutation.class);
        for (Class<?> extension : new Class<?>[]{McpQueryExtension.class, McpMutationExtension.class}) {
            List<Method> fields = java.util.Arrays.stream(extension.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(GraphQLField.class)).toList();
            assertThat(fields).hasSize(1);
            assertThat(fields.get(0).getName()).isEqualTo("communityMcp");
            assertThat(fields.get(0).getAnnotation(GraphQLName.class).value()).isEqualTo("communityMcp");
            assertThat(Modifier.isStatic(fields.get(0).getModifiers())).isTrue();
        }
        assertThat(McpQueryExtension.communityMcp()).isInstanceOf(McpQuery.class);
        assertThat(McpMutationExtension.communityMcp()).isInstanceOf(McpMutation.class);
        assertThat(new McpGraphQLExtensionsProvider()).isNotNull();
    }

    // ── queries ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("settings returns the configured whitelist entries")
    void settings_returns_whitelist() {
        when(config.getWhitelist()).thenReturn(Set.of("query:jcr"));

        assertThat(new McpQuery().getSettings().getWhitelist()).containsExactly("query:jcr");
    }

    @Test
    @DisplayName("settings report whether Jahia is clustered (the whitelist is per node)")
    void settings_report_clustering() throws Exception {
        when(config.getWhitelist()).thenReturn(Set.of());
        when(settingsBean.isClusterActivated()).thenReturn(true);
        assertThat(new McpQuery().getSettings().getClustered()).isTrue();
        when(settingsBean.isClusterActivated()).thenReturn(false);
        assertThat(new McpQuery().getSettings().getClustered()).isFalse();

        final Method getter = McpQuery.GqlMcpSettings.class.getMethod("getClustered");
        assertThat(getter.getAnnotation(GraphQLName.class).value()).isEqualTo("clustered");
        assertThat(getter.isAnnotationPresent(graphql.annotations.annotationTypes.GraphQLNonNull.class)).isTrue();
    }

    @Test
    @DisplayName("skill and settings descriptions present skill text as guidance, never as instructions to follow")
    void descriptions_do_not_tell_the_ai_to_obey_skill_text() throws Exception {
        final String content = McpQuery.GqlMcpSkill.class.getMethod("getContent")
                .getAnnotation(graphql.annotations.annotationTypes.GraphQLDescription.class).value();
        assertThat(content).contains("guidance").contains("not commands").doesNotContain("should follow");
        final Method save = McpMutation.class.getMethod("saveSkill", String.class, String.class, String.class,
                String.class);
        final String param = java.util.Arrays.stream(save.getParameters()).filter(p -> "content".equals(
                p.getAnnotation(GraphQLName.class).value())).findFirst().orElseThrow()
                .getAnnotation(graphql.annotations.annotationTypes.GraphQLDescription.class).value();
        assertThat(param).contains("guidance").doesNotContain("should follow");
        final String saveSettings = McpMutation.class.getMethod("saveSettings", List.class)
                .getAnnotation(graphql.annotations.annotationTypes.GraphQLDescription.class).value();
        assertThat(saveSettings).contains("never-allowed").contains("grantRoles").contains("personalApiTokens");
    }

    @Test
    @DisplayName("settings with an empty whitelist returns an empty list (read-only default)")
    void settings_empty_whitelist() {
        when(config.getWhitelist()).thenReturn(WhitelistSnapshot.of(Set.of()).raw());

        assertThat(new McpQuery().getSettings().getWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("settings fails with an error when the config service is missing, never an empty list")
    void settings_missing_service_is_an_error() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(null);

        final McpQuery query = new McpQuery();
        assertThatThrownBy(query::getSettings)
                .isInstanceOf(DataFetchingException.class).hasMessageContaining("not available");
    }

    @Test
    @DisplayName("skills are identified by their relative path, the key save/delete/get accept")
    void skills_use_relative_path_as_name() {
        when(skills.listSkills()).thenReturn(List.of(
                new McpSkillService.SkillEntry("hello-jahia", "default/hello-jahia", "Hello", "Says hi", "# Hi"),
                new McpSkillService.SkillEntry("root", "root", "Root", "d", "c")));

        List<McpQuery.GqlMcpSkill> result = new McpQuery().getSkills();

        assertThat(result).extracting(McpQuery.GqlMcpSkill::getName).containsExactly("default/hello-jahia", "root");
        assertThat(result.get(0).getMcpName()).isEqualTo("Hello");
        assertThat(result.get(0).getDescription()).isEqualTo("Says hi");
        assertThat(result.get(0).getContent()).isEqualTo("# Hi");
    }

    @Test
    @DisplayName("skills fails with an error when the skill service is missing")
    void skills_missing_service_is_an_error() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(null);

        final McpQuery query = new McpQuery();
        assertThatThrownBy(query::getSkills).isInstanceOf(DataFetchingException.class);
    }

    // ── mutations ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveSkill and deleteSkill delegate and return true on success")
    void skill_mutations_delegate() {
        when(skills.saveSkill("a/b", "m", "d", "c")).thenReturn(true);
        when(skills.deleteSkill("a/b")).thenReturn(true);

        assertThat(new McpMutation().saveSkill("a/b", "m", "d", "c")).isTrue();
        assertThat(new McpMutation().deleteSkill("a/b")).isTrue();
        verify(skills).saveSkill("a/b", "m", "d", "c");
    }

    @Test
    @DisplayName("a refused save or delete is a GraphQL error with a reason, not a silent false")
    void skill_mutation_failures_throw() {
        when(skills.saveSkill("a", "m", "d", "c")).thenReturn(false);
        when(skills.deleteSkill("a")).thenReturn(false);
        final McpMutation mutation = new McpMutation();

        assertThatThrownBy(() -> mutation.saveSkill("a", "m", "d", "c")).isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("could not be saved");
        assertThatThrownBy(() -> mutation.deleteSkill("a")).isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("was not deleted");
    }

    @Test
    @DisplayName("skill mutations fail with a GraphQL error when the skill service is missing")
    void skill_mutations_missing_service() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(null);
        final McpMutation mutation = new McpMutation();

        assertThatThrownBy(() -> mutation.saveSkill("a", "m", "d", "c")).isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> mutation.deleteSkill("a")).isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("not available");
    }

    @Test
    @DisplayName("invalid names are rejected with a client-safe error that does not echo the name, before JCR")
    void invalid_names_rejected_with_reason() {
        final McpMutation mutation = new McpMutation();

        for (String evil : new String[]{"../../etc/passwd", "a/../../b", "/abs", "a//b", "..", ".", "x\ny"}) {
            assertThatThrownBy(() -> mutation.saveSkill(evil, "m", "d", "c")).as(evil)
                    .isInstanceOf(DataFetchingException.class).hasMessageContaining("Invalid skill name")
                    .hasMessageNotContaining(evil);
            assertThatThrownBy(() -> mutation.deleteSkill(evil)).as(evil)
                    .isInstanceOf(DataFetchingException.class).hasMessageContaining("Invalid skill name");
        }
        verifyNoInteractions(skills);
    }

    @Test
    @DisplayName("an oversized body is rejected with the limit in the message")
    void oversized_content_rejected() {
        final McpMutation mutation = new McpMutation();
        final String big = "x".repeat(McpSkillService.MAX_CONTENT_CHARS + 1);

        assertThatThrownBy(() -> mutation.saveSkill("a", "m", "d", big)).isInstanceOf(DataFetchingException.class)
                .hasMessageContaining("too long").hasMessageContaining(String.valueOf(McpSkillService.MAX_CONTENT_CHARS));
        verifyNoInteractions(skills);
    }

    @Test
    @DisplayName("saveSettings never touches the whitelist for a missing service")
    void save_settings_missing_service() throws Exception {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(null);

        final McpMutation mutation = new McpMutation();
        final List<String> entries = List.of("query:x");
        assertThatThrownBy(() -> mutation.saveSettings(entries)).isInstanceOf(DataFetchingException.class);
        verify(config, never()).saveWhitelist(List.of("query:x"));
    }
}
