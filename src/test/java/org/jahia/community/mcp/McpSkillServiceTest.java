package org.jahia.community.mcp;

import org.jahia.community.mcp.access.CallerFixture;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.services.content.JCRTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import javax.jcr.ItemExistsException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.content.QueryManagerWrapper;
import org.jahia.services.query.QueryResultWrapper;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** JCR behaviour of {@link McpSkillService} against an in-memory fake of the JCR calls it makes. */
class McpSkillServiceTest {

    private static final String ROOT = McpSkillService.SKILLS_PATH;
    /** The type 1.x created for the container and its sub-folders, kept so upgrades need no migration. */
    private static final String CONTENT_LIST = "jnt:contentList";

    /** Just enough JCR: a path-to-node map, addNode registering children, a stubbed query. */
    private static final class FakeJcr {
        final JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        final Map<String, JCRNodeWrapper> nodes = new HashMap<>();
        final Map<String, String> types = new HashMap<>();
        final Map<String, Boolean> aclBreak = new HashMap<>();
        boolean failOnAdd;

        FakeJcr() throws RepositoryException {
            when(session.nodeExists(anyString())).thenAnswer(inv -> nodes.containsKey((String) inv.getArgument(0)));
            when(session.getNode(anyString())).thenAnswer(inv -> {
                JCRNodeWrapper n = nodes.get((String) inv.getArgument(0));
                if (n == null) {
                    throw new javax.jcr.PathNotFoundException((String) inv.getArgument(0));
                }
                return n;
            });
            nodes.put("/sites/systemsite/contents", node("/sites/systemsite/contents", "jnt:contentFolder"));
        }

        JCRNodeWrapper node(final String path, final String type) throws RepositoryException {
            final JCRNodeWrapper n = mock(JCRNodeWrapper.class);
            types.put(path, type);
            nodes.put(path, n);
            when(n.getPath()).thenReturn(path);
            when(n.getName()).thenReturn(path.substring(path.lastIndexOf('/') + 1));
            when(n.isNodeType(anyString())).thenAnswer(inv -> inv.getArgument(0).equals(types.get(path)));
            when(n.getAclInheritanceBreak()).thenAnswer(inv -> aclBreak.getOrDefault(path, false));
            when(n.addNode(anyString(), anyString())).thenAnswer(inv -> {
                if (failOnAdd) {
                    throw new ItemExistsException("exists");
                }
                return node(path + "/" + inv.getArgument(0), inv.getArgument(1));
            });
            org.mockito.Mockito.doAnswer(inv -> {
                aclBreak.put(path, (Boolean) inv.getArgument(0));
                return null;
            }).when(n).setAclInheritanceBreak(org.mockito.ArgumentMatchers.anyBoolean());
            return n;
        }

        JCRNodeWrapper container() throws RepositoryException {
            return node(ROOT, CONTENT_LIST);
        }

        JCRNodeWrapper skill(final String relative, final String title, final String description, final String content)
                throws RepositoryException {
            final JCRNodeWrapper n = node(ROOT + "/" + relative, "mcp:skill");
            stubProperty(n, "jcr:title", title);
            stubProperty(n, "mcp:description", description);
            stubProperty(n, "mcp:content", content);
            return n;
        }

        void stubProperty(final JCRNodeWrapper n, final String name, final String value) throws RepositoryException {
            if (value == null) {
                when(n.hasProperty(name)).thenReturn(false);
                return;
            }
            final JCRPropertyWrapper p = mock(JCRPropertyWrapper.class);
            when(p.getString()).thenReturn(value);
            when(n.hasProperty(name)).thenReturn(true);
            when(n.getProperty(name)).thenReturn(p);
        }

        void queryReturns(final JCRNodeWrapper... results) throws RepositoryException {
            final JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
            final QueryManagerWrapper manager = mock(QueryManagerWrapper.class);
            final QueryWrapper query = mock(QueryWrapper.class);
            final QueryResultWrapper result = mock(QueryResultWrapper.class);
            final JCRNodeIteratorWrapper iterator = mock(JCRNodeIteratorWrapper.class);
            when(session.getWorkspace()).thenReturn(workspace);
            when(workspace.getQueryManager()).thenReturn(manager);
            when(manager.createQuery(anyString(), eq(Query.JCR_SQL2))).thenReturn(query);
            when(query.execute()).thenReturn(result);
            when(result.getNodes()).thenReturn(iterator);
            final int[] i = {0};
            when(iterator.hasNext()).thenAnswer(inv -> i[0] < results.length);
            when(iterator.nextNode()).thenAnswer(inv -> results[i[0]++]);
        }
    }

    private MockedStatic<JCRTemplate> templateStatic;
    private JCRTemplate template;
    private FakeJcr jcr;
    private final McpSkillService service = new McpSkillService();

    @BeforeEach
    void setUp() throws RepositoryException {
        CallerFixture.releaseLeaked();
        jcr = new FakeJcr();
        template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSession(any(JCRCallback.class)))
                .thenAnswer(inv -> ((JCRCallback<?>) inv.getArgument(0)).doInJCR(jcr.session));
        templateStatic = mockStatic(JCRTemplate.class);
        templateStatic.when(JCRTemplate::getInstance).thenReturn(template);
    }

    @AfterEach
    void tearDown() {
        templateStatic.close();
    }

    // ── activation ────────────────────────────────────────────────────────────

    private static BundleContext bundleWith(final java.util.Enumeration<java.net.URL> entries) {
        final BundleContext context = mock(BundleContext.class);
        final Bundle bundle = mock(Bundle.class);
        when(context.getBundle()).thenReturn(bundle);
        when(bundle.findEntries("skills", "*.md", true)).thenReturn(entries);
        return context;
    }

    private static java.net.URL skillFile(final Path dir, final String relative, final String text) throws IOException {
        final Path file = dir.resolve("skills").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file.toUri().toURL();
    }

    @Test
    @DisplayName("activate creates the container and breaks its ACL inheritance, even with no bundled skill")
    void activate_creates_and_protects_container() throws Exception {
        service.activate(bundleWith(null));

        final JCRNodeWrapper container = jcr.nodes.get(ROOT);
        assertThat(container).isNotNull();
        verify(container).setAclInheritanceBreak(true);
        // Same type as 1.x created, so an upgraded install needs no migration of the container.
        assertThat(jcr.types.get(ROOT)).isEqualTo(CONTENT_LIST);
        verify(jcr.session, org.mockito.Mockito.atLeastOnce()).save();
    }

    @Test
    @DisplayName("activate hardens an existing container once and leaves a protected one alone")
    void activate_is_idempotent() throws Exception {
        final JCRNodeWrapper container = jcr.container();

        service.activate(bundleWith(null));
        verify(container).setAclInheritanceBreak(true);

        service.activate(bundleWith(null));
        verify(container, org.mockito.Mockito.times(1)).setAclInheritanceBreak(true);
    }

    @Test
    @DisplayName("activate hardens a container left by a 1.x install and imports into its existing folders")
    void activate_hardens_legacy_container(@TempDir final Path dir) throws Exception {
        // 1.x created the container and its sub-folders as jnt:contentList, with ACL inheritance kept
        final JCRNodeWrapper container = jcr.container();
        final JCRNodeWrapper legacyFolder = jcr.node(ROOT + "/default", CONTENT_LIST);
        jcr.skill("default/hello-jahia", "Hello", "d", "c");
        final java.net.URL added = skillFile(dir, "default/new-skill.md", "New");

        service.activate(bundleWith(Collections.enumeration(List.of(added))));

        verify(container).setAclInheritanceBreak(true);
        assertThat(jcr.aclBreak).containsEntry(ROOT, true);
        verify(container, never()).addNode(anyString(), anyString());
        verify(legacyFolder).addNode("new-skill", "mcp:skill");
        assertThat(jcr.types.get(ROOT)).isEqualTo(CONTENT_LIST);
        assertThat(jcr.types.get(ROOT + "/default")).isEqualTo(CONTENT_LIST);
    }

    @Test
    @DisplayName("activate tolerates a container created concurrently by another cluster node")
    void activate_tolerates_concurrent_container_creation() throws Exception {
        jcr.failOnAdd = true;
        // addNode throws ItemExists, but the other node's container is visible after refresh
        org.mockito.Mockito.doAnswer(inv -> {
            jcr.container();
            return null;
        }).when(jcr.session).refresh(false);

        service.activate(bundleWith(null));

        verify(jcr.session).refresh(false);
        verify(jcr.nodes.get(ROOT)).setAclInheritanceBreak(true);
    }

    @Test
    @DisplayName("activate imports bundled skills with frontmatter into folders and skips existing ones")
    void activate_imports_skills(@TempDir final Path dir) throws Exception {
        final java.net.URL fresh = skillFile(dir, "default/hello.md",
                "---\nmcpName: Hello\ndescription: Says hi\nignored line\n---\nLine one\nLine two\n");
        final java.net.URL plain = skillFile(dir, "plain.md", "Just text\nsecond");
        final java.net.URL existing = skillFile(dir, "default/old.md", "old");
        jcr.container();
        jcr.skill("default/old", "t", "d", "c");

        service.activate(bundleWith(Collections.enumeration(List.of(fresh, plain, existing))));

        final JCRNodeWrapper hello = jcr.nodes.get(ROOT + "/default/hello");
        assertThat(jcr.types.get(ROOT + "/default")).isEqualTo(CONTENT_LIST);
        verify(hello).setProperty("jcr:title", "Hello");
        verify(hello).setProperty("mcp:description", "Says hi");
        verify(hello).setProperty("mcp:content", "Line one\nLine two");
        final JCRNodeWrapper plainNode = jcr.nodes.get(ROOT + "/plain");
        verify(plainNode).setProperty("mcp:content", "Just text\nsecond");
        verify(plainNode, never()).setProperty(eq("jcr:title"), anyString());
        verify(jcr.nodes.get(ROOT + "/default/old"), never()).setProperty(eq("mcp:content"), anyString());
    }

    @Test
    @DisplayName("activate skips bundled resources with an unsafe name or a foreign layout")
    void activate_skips_unsafe_entries(@TempDir final Path dir) throws Exception {
        final java.net.URL unsafe = skillFile(dir, "bad name.md", "x");
        final java.net.URL notMarkdown = dir.resolve("skills/readme.txt").toUri().toURL();
        final java.net.URL outside = dir.resolve("elsewhere/x.md").toUri().toURL();

        service.activate(bundleWith(Collections.enumeration(List.of(unsafe, notMarkdown, outside))));

        assertThat(jcr.nodes.keySet()).containsExactlyInAnyOrder("/sites/systemsite/contents", ROOT);
    }

    @Test
    @DisplayName("activate carries on when a bundled file cannot be read, or when a skill was created concurrently")
    void activate_survives_read_and_race_failures(@TempDir final Path dir) throws Exception {
        final java.net.URL missing = dir.resolve("skills/gone.md").toUri().toURL();
        final java.net.URL raced = skillFile(dir, "raced.md", "x");
        final JCRNodeWrapper container = jcr.container();
        jcr.aclBreak.put(ROOT, true);
        org.mockito.Mockito.doThrow(new ItemExistsException("raced")).when(container).addNode("raced", "mcp:skill");

        service.activate(bundleWith(Collections.enumeration(List.of(missing, raced))));

        verify(jcr.session).refresh(false);
    }

    @Test
    @DisplayName("activate logs and gives up on a repository failure")
    void activate_repository_failure() throws Exception {
        when(template.doExecuteWithSystemSession(any(JCRCallback.class))).thenThrow(new RepositoryException("down"));

        service.activate(bundleWith(null));

        verify(jcr.session, never()).save();
    }

    // ── listSkills ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listSkills returns the relative path as the key, and never writes")
    void list_returns_relative_paths() throws Exception {
        jcr.container();
        jcr.queryReturns(jcr.skill("default/hello", "Hello", "Says hi", "# Hi"), jcr.skill("root-skill", null, null, null));

        final List<McpSkillService.SkillEntry> skills = service.listSkills();

        assertThat(skills).extracting(s -> s.name).containsExactly("hello", "root-skill");
        assertThat(skills).extracting(s -> s.path).containsExactly("default/hello", "root-skill");
        assertThat(skills.get(0).mcpName).isEqualTo("Hello");
        assertThat(skills.get(0).description).isEqualTo("Says hi");
        assertThat(skills.get(0).content).isEqualTo("# Hi");
        assertThat(skills.get(1).mcpName).isEmpty();
        assertThat(skills).isUnmodifiable();
        verify(jcr.session, never()).save();
    }

    @Test
    @DisplayName("listSkills returns nothing when the repository fails")
    void list_repository_failure() throws Exception {
        when(template.doExecuteWithSystemSession(any(JCRCallback.class))).thenThrow(new RepositoryException("down"));

        assertThat(service.listSkills()).isEmpty();
    }

    // ── getSkill ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSkill resolves a nested key and reads its properties")
    void get_returns_skill() throws Exception {
        jcr.container();
        jcr.skill("default/hello", "Hello", "Says hi", "# Hi");

        final McpSkillService.SkillEntry entry = service.getSkill("default/hello");

        assertThat(entry.path).isEqualTo("default/hello");
        assertThat(entry.content).isEqualTo("# Hi");
    }

    @Test
    @DisplayName("traversal and malformed names are rejected by the real service before any JCR access")
    void get_rejects_traversal() {
        for (final String name : new String[]{"../etc", "a/../b", "/abs", "a//b", "a/", "./x", "a b", "", " ", null}) {
            assertThat(service.getSkill(name)).as("getSkill(%s)", name).isNull();
            assertThat(service.saveSkill(name, "t", "d", "c")).as("saveSkill(%s)", name).isFalse();
            assertThat(service.deleteSkill(name)).as("deleteSkill(%s)", name).isFalse();
        }
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("getSkill returns null for a missing node and for a node that is not a skill")
    void get_missing_or_foreign() throws Exception {
        jcr.container();
        jcr.node(ROOT + "/folder", CONTENT_LIST);

        assertThat(service.getSkill("nothing")).isNull();
        assertThat(service.getSkill("folder")).isNull();
    }

    @Test
    @DisplayName("getSkill returns null on a repository failure")
    void get_repository_failure() throws Exception {
        when(template.doExecuteWithSystemSession(any(JCRCallback.class))).thenThrow(new RepositoryException("down"));

        assertThat(service.getSkill("a")).isNull();
    }

    // ── saveSkill ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveSkill creates a root-level skill with its properties")
    void save_creates_root_skill() throws Exception {
        jcr.container();

        assertThat(service.saveSkill("my-skill", "My", "Does things", "# Body")).isTrue();

        final JCRNodeWrapper node = jcr.nodes.get(ROOT + "/my-skill");
        assertThat(jcr.types.get(ROOT + "/my-skill")).isEqualTo("mcp:skill");
        verify(node).setProperty("jcr:title", "My");
        verify(node).setProperty("mcp:description", "Does things");
        verify(node).setProperty("mcp:content", "# Body");
        verify(jcr.session, org.mockito.Mockito.atLeastOnce()).save();
    }

    @Test
    @DisplayName("saveSkill creates the intermediate folders of a nested name")
    void save_creates_folders_for_nested_name() throws Exception {
        jcr.container();

        assertThat(service.saveSkill("team/sub/my-skill", null, null, "# Body")).isTrue();

        assertThat(jcr.types.get(ROOT + "/team")).isEqualTo(CONTENT_LIST);
        assertThat(jcr.types.get(ROOT + "/team/sub")).isEqualTo(CONTENT_LIST);
        assertThat(jcr.types.get(ROOT + "/team/sub/my-skill")).isEqualTo("mcp:skill");
        final JCRNodeWrapper node = jcr.nodes.get(ROOT + "/team/sub/my-skill");
        verify(node, never()).setProperty(eq("jcr:title"), anyString());
        verify(node, never()).setProperty(eq("mcp:description"), anyString());
    }

    @Test
    @DisplayName("saveSkill reuses existing folders and updates an existing skill in place")
    void save_updates_existing() throws Exception {
        jcr.container();
        jcr.node(ROOT + "/team", CONTENT_LIST);
        final JCRNodeWrapper existing = jcr.skill("team/s", "old", "old", "old");

        assertThat(service.saveSkill("team/s", "new", "newd", "newc")).isTrue();
        assertThat(service.saveSkill("team/t", "x", "y", "z")).isTrue();

        verify(existing).setProperty("mcp:content", "newc");
        assertThat(jcr.nodes).containsKey(ROOT + "/team/t");
    }

    @Test
    @DisplayName("saveSkill never overwrites a node that is not a skill")
    void save_refuses_foreign_node() throws Exception {
        jcr.container();
        final JCRNodeWrapper folder = jcr.node(ROOT + "/folder", CONTENT_LIST);

        assertThat(service.saveSkill("folder", "t", "d", "c")).isFalse();

        verify(folder, never()).setProperty(anyString(), anyString());
    }

    @Test
    @DisplayName("saveSkill refuses to use an existing skill as a folder")
    void save_refuses_skill_as_folder() throws Exception {
        jcr.container();
        jcr.skill("leaf", "t", "d", "c");

        assertThat(service.saveSkill("leaf/child", "t", "d", "c")).isFalse();
    }

    @Test
    @DisplayName("saveSkill returns false on a repository failure")
    void save_repository_failure() throws Exception {
        when(template.doExecuteWithSystemSession(any(JCRCallback.class))).thenThrow(new RepositoryException("down"));

        assertThat(service.saveSkill("a", "t", "d", "c")).isFalse();
    }

    // ── deleteSkill ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteSkill removes a skill addressed by its relative path")
    void delete_removes_skill() throws Exception {
        jcr.container();
        final JCRNodeWrapper node = jcr.skill("default/hello", "t", "d", "c");

        assertThat(service.deleteSkill("default/hello")).isTrue();

        verify(node).remove();
        verify(jcr.session).save();
    }

    @Test
    @DisplayName("deleteSkill never removes a node that is not a skill")
    void delete_refuses_foreign_node() throws Exception {
        jcr.container();
        final JCRNodeWrapper folder = jcr.node(ROOT + "/folder", CONTENT_LIST);

        assertThat(service.deleteSkill("folder")).isFalse();

        verify(folder, never()).remove();
    }

    @Test
    @DisplayName("deleteSkill returns false for a missing skill and on a repository failure")
    void delete_missing_and_failure() throws Exception {
        jcr.container();
        assertThat(service.deleteSkill("nothing")).isFalse();

        when(template.doExecuteWithSystemSession(any(JCRCallback.class))).thenThrow(new RepositoryException("down"));
        assertThat(service.deleteSkill("a")).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("relativePath strips the skills root only")
    void relative_path() {
        assertThat(McpSkillService.relativePath(ROOT + "/a/b")).isEqualTo("a/b");
        assertThat(McpSkillService.relativePath("/elsewhere/a")).isEqualTo("/elsewhere/a");
    }

    @Test
    @DisplayName("both SkillEntry constructors are kept; the 4-arg form is a root-level skill")
    void skill_entry_constructors() {
        final McpSkillService.SkillEntry four = new McpSkillService.SkillEntry("n", "m", "d", "c");
        final McpSkillService.SkillEntry five = new McpSkillService.SkillEntry("n", "p/n", "m", "d", "c");

        assertThat(four.path).isEqualTo("n");
        assertThat(five.path).isEqualTo("p/n");
        assertThat(five.name).isEqualTo("n");
    }
}
