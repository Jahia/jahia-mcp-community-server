package org.jahia.community.mcp;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component(service = McpSkillService.class, immediate = true)
public class McpSkillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSkillService.class);
    private static final String SKILLS_PATH = "/sites/systemsite/contents/mcp-skills";
    private static final String SKILL_NODE_TYPE = "mcp:skill";
    private static final String PROP_NAME = "mcp:name";
    private static final String PROP_DESCRIPTION = "mcp:description";
    private static final String PROP_CONTENT = "mcp:content";

    private static final String DEFAULT_SKILL_PATH = SKILLS_PATH + "/default/hello-jahia";

    @Activate
    public void activate() {
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                if (!session.nodeExists(SKILLS_PATH + "/default")) {
                    session.getNode(SKILLS_PATH).addNode("default", "jnt:contentList");
                    session.save();
                }
                if (!session.nodeExists(DEFAULT_SKILL_PATH)) {
                    final JCRNodeWrapper skill = session.getNode(SKILLS_PATH + "/default").addNode("hello-jahia", SKILL_NODE_TYPE);
                    skill.setProperty(PROP_NAME, "Hello Jahia");
                    skill.setProperty(PROP_DESCRIPTION, "Simple skill — how to respond to \"Hello Jahia\"");
                    skill.setProperty(PROP_CONTENT, "If you get \"Hello Jahia\", answer \"Hello Jahian\".");
                    session.save();
                    LOGGER.info("Created default MCP skill: {}", DEFAULT_SKILL_PATH);
                }
                return null;
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error creating default MCP skills", e);
        }
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
