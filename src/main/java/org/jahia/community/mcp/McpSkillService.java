package org.jahia.community.mcp;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Component(service = McpSkillService.class, immediate = true)
public class McpSkillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSkillService.class);
    private static final String SKILLS_PATH = "/sites/systemsite/contents/mcp-skills";
    private static final String SKILL_NODE_TYPE = "mcp:skill";
    private static final String PROP_NAME = "mcp:name";
    private static final String PROP_DESCRIPTION = "mcp:description";
    private static final String PROP_CONTENT = "mcp:content";
    private static final String SKILLS_RESOURCE_DIR = "skills";

    @Activate
    public void activate(BundleContext bundleContext) {
        final Enumeration<URL> entries = bundleContext.getBundle().findEntries(SKILLS_RESOURCE_DIR, "*.md", true);
        if (entries == null) {
            return;
        }
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                while (entries.hasMoreElements()) {
                    final URL url = entries.nextElement();
                    try {
                        importSkillEntry(session, url);
                    } catch (IOException ex) {
                        LOGGER.error("Cannot read skill file {}", url, ex);
                    }
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error importing default MCP skills", e);
        }
    }

    private void importSkillEntry(JCRSessionWrapper session, URL url) throws IOException, RepositoryException {
        // Derive relative path from URL, e.g. "/skills/default/hello-jahia.md" → "default/hello-jahia"
        final String urlPath = url.getPath();
        final String marker = "/" + SKILLS_RESOURCE_DIR + "/";
        final int idx = urlPath.lastIndexOf(marker);
        if (idx < 0) return;
        final String relative = urlPath.substring(idx + marker.length()); // "default/hello-jahia.md"
        if (!relative.endsWith(".md")) return;
        final String relNoExt = relative.substring(0, relative.length() - 3); // "default/hello-jahia"

        final String jcrPath = SKILLS_PATH + "/" + relNoExt;
        if (session.nodeExists(jcrPath)) {
            return;
        }

        // Ensure intermediate folder nodes
        final String[] segments = relNoExt.split("/");
        String parentPath = SKILLS_PATH;
        for (int i = 0; i < segments.length - 1; i++) {
            final String folderPath = parentPath + "/" + segments[i];
            if (!session.nodeExists(folderPath)) {
                session.getNode(parentPath).addNode(segments[i], "jnt:contentList");
                session.save();
            }
            parentPath = folderPath;
        }

        // Parse file and create skill node
        final String[] parsed = parseFrontmatter(url);
        final JCRNodeWrapper skill = session.getNode(parentPath).addNode(segments[segments.length - 1], SKILL_NODE_TYPE);
        if (!parsed[0].isEmpty()) skill.setProperty(PROP_NAME, parsed[0]);
        if (!parsed[1].isEmpty()) skill.setProperty(PROP_DESCRIPTION, parsed[1]);
        skill.setProperty(PROP_CONTENT, parsed[2]);
        session.save();
        LOGGER.info("Imported default MCP skill: {}", jcrPath);
    }

    private static String[] parseFrontmatter(URL url) throws IOException {
        String mcpName = "";
        String description = "";
        final StringBuilder content = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            final boolean hasFrontmatter = "---".equals(line);

            if (hasFrontmatter) {
                while ((line = reader.readLine()) != null && !"---".equals(line)) {
                    final int colon = line.indexOf(':');
                    if (colon > 0) {
                        final String key = line.substring(0, colon).trim();
                        final String val = line.substring(colon + 1).trim();
                        if ("mcpName".equals(key)) mcpName = val;
                        else if ("description".equals(key)) description = val;
                    }
                }
            } else if (line != null) {
                content.append(line);
            }

            String sep = content.length() > 0 ? "\n" : "";
            while ((line = reader.readLine()) != null) {
                content.append(sep).append(line);
                sep = "\n";
            }
        }

        return new String[]{mcpName, description, content.toString().trim()};
    }

    private static final String LIST_SKILLS_SQL =
            "SELECT * FROM [mcp:skill] AS skill WHERE ISDESCENDANTNODE(skill, '" + SKILLS_PATH + "') ORDER BY NAME(skill)";

    public List<SkillEntry> listSkills() {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                final Query query = session.getWorkspace().getQueryManager().createQuery(LIST_SKILLS_SQL, Query.JCR_SQL2);
                final QueryResult result = query.execute();
                final List<SkillEntry> skills = new ArrayList<>();
                final NodeIterator iter = result.getNodes();
                while (iter.hasNext()) {
                    skills.add(toEntry((JCRNodeWrapper) iter.nextNode()));
                }
                return skills;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error listing MCP skills", e);
            return Collections.emptyList();
        }
    }

    public SkillEntry getSkill(String name) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final String path = SKILLS_PATH + "/" + name;
                if (!session.nodeExists(path)) {
                    return null;
                }
                final JCRNodeWrapper node = session.getNode(path);
                return node.isNodeType(SKILL_NODE_TYPE) ? toEntry(node) : null;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error getting MCP skill: {}", name, e);
            return null;
        }
    }

    public boolean saveSkill(String name, String mcpName, String description, String content) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                final String path = SKILLS_PATH + "/" + name;
                final JCRNodeWrapper node;
                if (session.nodeExists(path)) {
                    node = session.getNode(path);
                } else {
                    node = session.getNode(SKILLS_PATH).addNode(name, SKILL_NODE_TYPE);
                }
                if (mcpName != null) {
                    node.setProperty(PROP_NAME, mcpName);
                }
                if (description != null) {
                    node.setProperty(PROP_DESCRIPTION, description);
                }
                node.setProperty(PROP_CONTENT, content);
                session.save();
                return true;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error saving MCP skill: {}", name, e);
            return false;
        }
    }

    public boolean deleteSkill(String name) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final String path = SKILLS_PATH + "/" + name;
                if (!session.nodeExists(path)) {
                    return false;
                }
                session.getNode(path).remove();
                session.save();
                return true;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error deleting MCP skill: {}", name, e);
            return false;
        }
    }

    private void ensureContainer(JCRSessionWrapper session) throws RepositoryException {
        if (!session.nodeExists(SKILLS_PATH)) {
            session.getNode("/sites/systemsite/contents").addNode("mcp-skills", "jnt:contentList");
            session.save();
        }
    }

    private SkillEntry toEntry(JCRNodeWrapper node) throws RepositoryException {
        return new SkillEntry(
                node.getName(),
                node.hasProperty(PROP_NAME) ? node.getProperty(PROP_NAME).getString() : "",
                node.hasProperty(PROP_DESCRIPTION) ? node.getProperty(PROP_DESCRIPTION).getString() : "",
                node.hasProperty(PROP_CONTENT) ? node.getProperty(PROP_CONTENT).getString() : ""
        );
    }

    public static final class SkillEntry {
        public final String name;
        public final String mcpName;
        public final String description;
        public final String content;

        public SkillEntry(String name, String mcpName, String description, String content) {
            this.name = name;
            this.mcpName = mcpName;
            this.description = description;
            this.content = content;
        }
    }
}
