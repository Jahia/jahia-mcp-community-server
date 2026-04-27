package org.jahia.community.mcp;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component(service = McpSkillService.class, immediate = true)
public class McpSkillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSkillService.class);
    private static final String SKILLS_PATH = "/sites/systemsite/contents/mcp-skills";
    private static final String SKILL_NODE_TYPE = "mcp:skill";
    private static final String PROP_DESCRIPTION = "mcp:description";
    private static final String PROP_CONTENT = "mcp:content";

    public List<SkillEntry> listSkills() {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                final JCRNodeWrapper parent = session.getNode(SKILLS_PATH);
                final List<SkillEntry> skills = new ArrayList<>();
                final NodeIterator iter = parent.getNodes();
                while (iter.hasNext()) {
                    final JCRNodeWrapper node = (JCRNodeWrapper) iter.nextNode();
                    if (node.isNodeType(SKILL_NODE_TYPE)) {
                        skills.add(toEntry(node));
                    }
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

    public boolean saveSkill(String name, String description, String content) {
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
                node.hasProperty(PROP_DESCRIPTION) ? node.getProperty(PROP_DESCRIPTION).getString() : "",
                node.hasProperty(PROP_CONTENT) ? node.getProperty(PROP_CONTENT).getString() : ""
        );
    }

    public static final class SkillEntry {
        public final String name;
        public final String description;
        public final String content;

        public SkillEntry(String name, String description, String content) {
            this.name = name;
            this.description = description;
            this.content = content;
        }
    }
}
