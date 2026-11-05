package org.jahia.community.mcp;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.Value;
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
import java.util.regex.Pattern;

@SuppressWarnings("java:S1075")
@Component(service = McpSkillService.class, immediate = true)
public class McpSkillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpSkillService.class);
    static final String SKILLS_PATH = "/sites/systemsite/contents/mcp-skills";
    /** Upper bound on the Markdown body of one skill (characters). */
    public static final int MAX_CONTENT_CHARS = 200_000;
    private static final String ACL_NODE = "j:acl";
    private static final String SKILL_NODE_TYPE = "mcp:skill";
    /**
     * Type of the skills container and of its sub-folders: the type 1.x created, so an upgraded
     * install needs no migration. {@code mcpmix:mcp} carries {@code jmix:droppableContent}, which this
     * list type requires of its children, and {@code jmix:hiddenType}, which keeps skills out of the
     * editors' content-creation pickers.
     */
    static final String FOLDER_NODE_TYPE = "jnt:contentList";
    private static final String PROP_NAME = "jcr:title";
    private static final String PROP_DESCRIPTION = "mcp:description";
    private static final String PROP_CONTENT = "mcp:content";
    private static final String SKILLS_RESOURCE_DIR = "skills";

    /**
     * Allowed skill name: alphanumeric, underscore, hyphen segments separated by single slashes.
     * Possessive quantifiers ({@code ++}, {@code *+}) eliminate backtracking entirely,
     * making the pattern ReDoS-safe (S5998).
     * Empty segments (leading/trailing/double slash) and traversal segments (./..)
     * are additionally rejected by the per-segment check in {@link #validateSkillName(String)}.
     */
    static final Pattern SKILL_NAME_PATTERN =
            Pattern.compile("^(?:[A-Za-z0-9_-]++/)*+[A-Za-z0-9_-]++$");

    /**
     * Validates a caller-supplied skill name is safe for JCR path construction.
     * @throws IllegalArgumentException if the name is null, blank, or contains illegal characters
     */
    public static String validateSkillName(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name must not be null or blank");
        }
        if (!SKILL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Skill name contains illegal characters (only A-Za-z0-9_-/ allowed, no leading slash): " + LogSupport.clean(name));
        }
        for (final String segment : name.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        "Skill name must not contain empty path segments (leading, trailing, or double slash): " + LogSupport.clean(name));
            }
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException(
                        "Skill name must not contain path-traversal segments: " + LogSupport.clean(name));
            }
        }
        return name;
    }

    /** Asserts the resolved JCR path is a proper descendant of SKILLS_PATH. */
    private static void assertDescendant(final String resolvedPath) {
        if (!resolvedPath.startsWith(SKILLS_PATH + "/") || resolvedPath.length() <= SKILLS_PATH.length() + 1) {
            throw new SecurityException("Resolved skill path escapes the skills root: " + resolvedPath);
        }
    }

    @Activate
    public void activate(BundleContext bundleContext) {
        final Enumeration<URL> entries = bundleContext.getBundle().findEntries(SKILLS_RESOURCE_DIR, "*.md", true);
        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                // Once, here — not on the read path. Also hardens containers created by older versions.
                ensureContainer(session);
                purgeLocalGrants(session);
                while (entries != null && entries.hasMoreElements()) {
                    final URL url = entries.nextElement();
                    try {
                        importSkillEntry(session, url);
                    } catch (IOException ex) {
                        LOGGER.error("Cannot read skill file {}", url, ex);
                    } catch (ItemExistsException | InvalidItemStateException | ConstraintViolationException ex) {
                        // another cluster node seeded it first (or is saving the same folder)
                        session.refresh(false);
                    } catch (RepositoryException ex) {
                        // one skill failing must not abort the seeding of the others
                        session.refresh(false);
                        LOGGER.warn("Could not import default skill {}; it is retried at the next activation",
                                url, ex);
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

        // Validate the bundled skill name — reject anything that looks like a traversal path.
        // Bundled resources come from the bundle itself, but validate defensively anyway.
        try {
            validateSkillName(relNoExt);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Skipping bundled skill with unsafe name '{}': {}", relNoExt, ex.getMessage());
            return;
        }

        final String jcrPath = SKILLS_PATH + "/" + relNoExt;
        if (session.nodeExists(jcrPath)) {
            return;
        }

        final String[] segments = relNoExt.split("/");
        final JCRNodeWrapper parent = ensureFolders(session, segments);

        // Parse file and create skill node
        final String[] parsed = parseFrontmatter(url);
        final JCRNodeWrapper skill = parent.addNode(segments[segments.length - 1], SKILL_NODE_TYPE);
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

    /** Every skill with its body. */
    public List<SkillEntry> listSkills() {
        return listSkills(true);
    }

    /** Every skill without its body (empty {@code content}): enough to list names and descriptions. */
    public List<SkillEntry> listSkillHeaders() {
        return listSkills(false);
    }

    private List<SkillEntry> listSkills(final boolean withContent) {
        try {
            // Reads run under a SYSTEM session on purpose: the skills container has its ACL
            // inheritance broken (see ensureContainer), so no ordinary role can see or write it
            // through generic JCR paths, and the only entry points (the tools and the communityMcp
            // GraphQL operations) are admin-gated.
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final Query query = session.getWorkspace().getQueryManager().createQuery(LIST_SKILLS_SQL, Query.JCR_SQL2);
                final QueryResult result = query.execute();
                final List<SkillEntry> skills = new ArrayList<>();
                final NodeIterator iter = result.getNodes();
                while (iter.hasNext()) {
                    skills.add(toEntry((JCRNodeWrapper) iter.nextNode(), withContent));
                }
                return Collections.unmodifiableList(skills);
            });
        } catch (RepositoryException e) {
            LOGGER.error("Error listing MCP skills", e);
            return Collections.emptyList();
        }
    }

    public SkillEntry getSkill(final String name) {
        try {
            validateSkillName(name);
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("getSkill rejected unsafe name: {}", ex.getMessage());
            return null;
        }
        try {
            // SYSTEM session, see listSkills(). The name is still validated (validateSkillName +
            // assertDescendant) so it cannot reach a node outside SKILLS_PATH.
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final String path = SKILLS_PATH + "/" + name;
                assertDescendant(path);
                if (!session.nodeExists(path)) {
                    return null;
                }
                final JCRNodeWrapper node = session.getNode(path);
                return node.isNodeType(SKILL_NODE_TYPE) ? toEntry(node, true) : null;
            });
        } catch (SecurityException ex) {
            LOGGER.warn("getSkill path-traversal attempt blocked: {}", ex.getMessage());
            return null;
        } catch (RepositoryException e) {
            LOGGER.error("Error getting MCP skill: {}", LogSupport.clean(name), e);
            return null;
        }
    }

    /** True when the name is a safe skill key and the content fits; otherwise logs the reason at WARN. */
    private static boolean isAcceptedForSave(final String name, final String content) {
        try {
            validateSkillName(name);
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("saveSkill rejected unsafe name: {}", ex.getMessage());
            return false;
        }
        if (content != null && content.length() > MAX_CONTENT_CHARS) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("saveSkill rejected: content of '{}' exceeds {} characters", LogSupport.clean(name),
                        MAX_CONTENT_CHARS);
            }
            return false;
        }
        return true;
    }

    public boolean saveSkill(final String name, final String mcpName, final String description, final String content) {
        final String actingUser = LogSupport.actingUser();
        if (!isAcceptedForSave(name, content)) {
            return false;
        }
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                ensureContainer(session);
                final String path = SKILLS_PATH + "/" + name;
                assertDescendant(path);
                final JCRNodeWrapper node;
                if (session.nodeExists(path)) {
                    node = session.getNode(path);
                    if (!node.isNodeType(SKILL_NODE_TYPE)) {
                        LOGGER.warn("saveSkill refused: '{}' exists and is not an {} node", LogSupport.clean(name), SKILL_NODE_TYPE);
                        return false;
                    }
                } else {
                    final String[] segments = name.split("/");
                    final JCRNodeWrapper parent = ensureFolders(session, segments);
                    node = parent.addNode(segments[segments.length - 1], SKILL_NODE_TYPE);
                }
                if (mcpName != null) {
                    node.setProperty(PROP_NAME, mcpName);
                }
                if (description != null) {
                    node.setProperty(PROP_DESCRIPTION, description);
                }
                node.setProperty(PROP_CONTENT, content);
                session.save();
                // JCR records the system session as author: the audit trail is this line.
                LOGGER.info("MCP skill '{}' saved by user '{}'", LogSupport.clean(name), actingUser);
                return true;
            });
        } catch (SecurityException | IllegalArgumentException ex) {
            LOGGER.warn("saveSkill refused: {}", ex.getMessage());
            return false;
        } catch (RepositoryException e) {
            LOGGER.error("Error saving MCP skill: {}", LogSupport.clean(name), e);
            return false;
        }
    }

    public boolean deleteSkill(final String name) {
        final String actingUser = LogSupport.actingUser();
        try {
            validateSkillName(name);
        } catch (IllegalArgumentException ex) {
            LOGGER.warn("deleteSkill rejected unsafe name: {}", ex.getMessage());
            return false;
        }
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                final String path = SKILLS_PATH + "/" + name;
                assertDescendant(path);
                if (!session.nodeExists(path)) {
                    return false;
                }
                final JCRNodeWrapper node = session.getNode(path);
                if (!node.isNodeType(SKILL_NODE_TYPE)) {
                    LOGGER.warn("deleteSkill refused: '{}' is not an {} node", LogSupport.clean(name), SKILL_NODE_TYPE);
                    return false;
                }
                node.remove();
                session.save();
                LOGGER.info("MCP skill '{}' deleted by user '{}'", LogSupport.clean(name), actingUser);
                return true;
            });
        } catch (SecurityException ex) {
            LOGGER.warn("deleteSkill path-traversal attempt blocked: {}", ex.getMessage());
            return false;
        } catch (RepositoryException e) {
            LOGGER.error("Error deleting MCP skill: {}", LogSupport.clean(name), e);
            return false;
        }
    }

    /**
     * Creates the skills container if missing and breaks its ACL inheritance (idempotent). With no
     * role granted on it, ordinary users and roles cannot write skills through generic JCR paths, so
     * a content author cannot plant text that an administrator's AI client would later read. The
     * {@code root} user bypasses ACLs and can still write (accepted: it can also whitelist
     * {@code mutation:jcr}); this module itself writes skills only through the system session.
     *
     * <p>Only the default workspace is hardened: skills are read from it exclusively, so a copy of
     * the container that a 1.x install published to the live workspace is never served by the
     * tools and needs no hardening there.</p>
     *
     * <p>Cluster-safe: every node runs this at activation, so a concurrent creation or ACL change
     * by another node is tolerated (the session is refreshed and the state re-read).</p>
     */
    private void ensureContainer(JCRSessionWrapper session) throws RepositoryException {
        if (!session.nodeExists(SKILLS_PATH)) {
            try {
                session.getNode("/sites/systemsite/contents").addNode("mcp-skills", FOLDER_NODE_TYPE);
                session.save();
            } catch (ItemExistsException | InvalidItemStateException | ConstraintViolationException ex) {
                // created concurrently by another cluster node (Jackrabbit reports the race under
                // either name); keep going if the container exists now, fail otherwise.
                session.refresh(false);
                if (!session.nodeExists(SKILLS_PATH)) {
                    throw ex;
                }
            }
        }
        final JCRNodeWrapper container = session.getNode(SKILLS_PATH);
        if (!container.getAclInheritanceBreak()) {
            try {
                container.setAclInheritanceBreak(true);
                session.save();
                LOGGER.info("Broke ACL inheritance on {}: no inherited role may write skills", SKILLS_PATH);
            } catch (RepositoryException ex) {
                // Cluster activation race, or a real failure: either way the container is still open to
                // inherited roles, so this is an ERROR. Idempotent: the next activation checks again.
                session.refresh(false);
                LOGGER.error("Could not break ACL inheritance on {} ({}): skills stay readable and writable by "
                        + "inherited roles until it is retried at the next activation; saveSkill retries it "
                        + "too", SKILLS_PATH, ex.toString());
            }
        }
    }

    /**
     * Removes every local ACE (role grant) on the skills container and on each node below it, in the
     * default workspace (the only one skills are read from). A 1.x install let administrators grant
     * roles on these nodes, which would still let editors write skills after the inheritance break.
     * Idempotent; the {@code j:acl} nodes themselves stay, so the container remains broken.
     */
    private void purgeLocalGrants(final JCRSessionWrapper session) {
        try {
            final List<String> removed = new ArrayList<>();
            collectAndRemoveAces(session.getNode(SKILLS_PATH), removed);
            if (!removed.isEmpty()) {
                session.save();
                for (final String grant : removed) {
                    LOGGER.info("Removed local ACL grant {} (no role may hold a grant on the skills container)",
                            grant);
                }
                LOGGER.info("Removed {} local ACL grant(s) under {}; review the existing skills, they may have "
                        + "been written by users who held those grants", removed.size(), SKILLS_PATH);
            }
        } catch (RepositoryException | RuntimeException ex) {
            try {
                session.refresh(false);
            } catch (RepositoryException refreshFailure) {
                LOGGER.debug("refresh after a failed ACL purge failed", refreshFailure);
            }
            LOGGER.error("Could not remove the local ACL grants under {}; they will be retried at the next "
                    + "activation", SKILLS_PATH, ex);
        }
    }

    private static void collectAndRemoveAces(final JCRNodeWrapper node, final List<String> removed)
            throws RepositoryException {
        if (node.hasNode(ACL_NODE)) {
            final NodeIterator aces = node.getNode(ACL_NODE).getNodes();
            while (aces != null && aces.hasNext()) {
                final Node ace = aces.nextNode();
                if (ace.isNodeType("jnt:ace")) {
                    removed.add(describeAce(node.getPath(), ace));
                    ace.remove();
                }
            }
        }
        final NodeIterator children = node.getNodes();
        while (children != null && children.hasNext()) {
            final Node child = children.nextNode();
            if (!ACL_NODE.equals(child.getName()) && child instanceof JCRNodeWrapper) {
                collectAndRemoveAces((JCRNodeWrapper) child, removed);
            }
        }
    }

    private static String describeAce(final String path, final Node ace) throws RepositoryException {
        final String principal = ace.hasProperty("j:principal") ? ace.getProperty("j:principal").getString() : "?";
        final StringBuilder roles = new StringBuilder();
        if (ace.hasProperty("j:roles")) {
            for (final Value role : ace.getProperty("j:roles").getValues()) {
                roles.append(roles.length() == 0 ? "" : ",").append(role.getString());
            }
        }
        return "on '" + LogSupport.clean(path) + "' for '" + LogSupport.clean(principal)
                + "' roles [" + LogSupport.clean(roles.toString()) + "]";
    }

    /**
     * Returns the folder that must hold the skill {@code segments[last]}, creating the intermediate
     * folders. An intermediate segment that is itself a skill cannot become a folder.
     */
    private static JCRNodeWrapper ensureFolders(final JCRSessionWrapper session, final String[] segments)
            throws RepositoryException {
        JCRNodeWrapper parent = session.getNode(SKILLS_PATH);
        for (int i = 0; i < segments.length - 1; i++) {
            final String folderPath = parent.getPath() + "/" + segments[i];
            if (session.nodeExists(folderPath)) {
                parent = session.getNode(folderPath);
                if (parent.isNodeType(SKILL_NODE_TYPE)) {
                    throw new IllegalArgumentException("'" + segments[i] + "' is a skill and cannot be used as a folder");
                }
            } else {
                parent = parent.addNode(segments[i], FOLDER_NODE_TYPE);
                session.save();
            }
        }
        return parent;
    }

    /** Path of a skill node relative to {@link #SKILLS_PATH} — the key {@link #getSkill} expects. */
    static String relativePath(final String nodePath) {
        final String prefix = SKILLS_PATH + "/";
        return nodePath.startsWith(prefix) ? nodePath.substring(prefix.length()) : nodePath;
    }

    private SkillEntry toEntry(JCRNodeWrapper node, boolean withContent) throws RepositoryException {
        return new SkillEntry(
                node.getName(),
                relativePath(node.getPath()),
                node.hasProperty(PROP_NAME) ? node.getProperty(PROP_NAME).getString() : "",
                node.hasProperty(PROP_DESCRIPTION) ? node.getProperty(PROP_DESCRIPTION).getString() : "",
                withContent && node.hasProperty(PROP_CONTENT) ? node.getProperty(PROP_CONTENT).getString() : ""
        );
    }

    public static final class SkillEntry {
        /** JCR node name. */
        public final String name;
        /** Path relative to the skills root (e.g. {@code default/hello-jahia}); the getSkill key. */
        public final String path;
        public final String mcpName;
        public final String description;
        public final String content;

        public SkillEntry(String name, String path, String mcpName, String description, String content) {
            this.name = name;
            this.path = path;
            this.mcpName = mcpName;
            this.description = description;
            this.content = content;
        }
    }
}
