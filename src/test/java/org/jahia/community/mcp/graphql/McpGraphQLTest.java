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

    @BeforeEach
    void setUp() {
        config = mock(McpConfigService.class);
        skills = mock(McpSkillService.class);
        bundleUtils = mockStatic(BundleUtils.class);
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpConfigService.class, null)).thenReturn(config);
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(skills);
    }

    @AfterEach
    void tearDown() {
        bundleUtils.close();
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
                new McpSkillService.SkillEntry("root", "Root", "d", "c")));

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
    @DisplayName("saveSkill and deleteSkill delegate and return the service result")
    void skill_mutations_delegate() {
        when(skills.saveSkill("a/b", "m", "d", "c")).thenReturn(true);
        when(skills.deleteSkill("a/b")).thenReturn(false);

        assertThat(new McpMutation().saveSkill("a/b", "m", "d", "c")).isTrue();
        assertThat(new McpMutation().deleteSkill("a/b")).isFalse();
        verify(skills).saveSkill("a/b", "m", "d", "c");
    }

    @Test
    @DisplayName("skill mutations return false when the skill service is missing")
    void skill_mutations_missing_service() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(null);

        assertThat(new McpMutation().saveSkill("a", "m", "d", "c")).isFalse();
        assertThat(new McpMutation().deleteSkill("a")).isFalse();
    }

    @Test
    @DisplayName("traversal names are rejected through the real service and never reach JCR")
    void traversal_rejected_through_real_service() {
        bundleUtils.when(() -> BundleUtils.getOsgiService(McpSkillService.class, null)).thenReturn(new McpSkillService());

        for (String evil : new String[]{"../../etc/passwd", "a/../../b", "/abs", "a//b", "..", "."}) {
            assertThat(new McpMutation().saveSkill(evil, "m", "d", "c")).as(evil).isFalse();
            assertThat(new McpMutation().deleteSkill(evil)).as(evil).isFalse();
        }
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
