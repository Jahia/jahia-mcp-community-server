package org.jahia.community.mcp.config;

import org.jahia.community.mcp.LogSupport;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Holder of the whitelist configuration ({@value #PID}).
 *
 * <p>Fail-closed lifecycle: the component requires its configuration
 * ({@link ConfigurationPolicy#REQUIRE}); the shipped default {@code .cfg} provides it. Until the
 * configuration is delivered (startup) and after it is deleted, this service is not active, so the
 * gate and the tools that reference it are not active either — nothing runs on a guessed default.</p>
 *
 * <p>An empty whitelist is the read-only default (queries only), see {@link WhitelistSnapshot}.</p>
 */
@Component(immediate = true, service = McpConfigService.class,
        configurationPid = McpConfigService.PID, configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = McpConfigService.Config.class)
public class McpConfigService {

    /** Configuration PID of this module. */
    public static final String PID = "org.jahia.community.mcp";
    /** Configuration key holding the whitelist. */
    public static final String KEY_WHITELIST = "whitelist";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpConfigService.class);
    private static final int MAX_SHOWN_ENTRY_LENGTH = 100;

    /** Typed view of the configuration. A CSV string ({@code a,b}) and a String[] are both accepted. */
    @ObjectClassDefinition(name = "Jahia MCP Community Server",
            description = "Operation whitelist of the community.graphql.execute tool")
    public @interface Config {
        @AttributeDefinition(name = "Whitelist",
                description = "query:<path> / mutation:<path> entries. Empty: every query except those under 'admin' is allowed, "
                        + "no mutation and no subscription.")
        String[] whitelist() default {};
    }

    private final ConfigurationAdmin configurationAdmin;
    // One immutable object (final class, unmodifiable sets), replaced as a whole: readers always see
    // a consistent snapshot, which is what S3077 asks for.
    @SuppressWarnings("java:S3077")
    private volatile WhitelistSnapshot snapshot;

    @Activate
    public McpConfigService(@Reference final ConfigurationAdmin configurationAdmin, final Config config) {
        this.configurationAdmin = configurationAdmin;
        apply(config);
    }

    @Modified
    void modified(final Config config) {
        apply(config);
    }

    private void apply(final Config config) {
        final String joined = config == null || config.whitelist() == null
                ? "" : String.join(",", config.whitelist());
        final WhitelistSnapshot next = WhitelistSnapshot.of(parseList(joined));
        final WhitelistSnapshot previous = snapshot;
        snapshot = next;
        logChange(previous, next);
    }

    private static void logChange(final WhitelistSnapshot previous, final WhitelistSnapshot next) {
        if (previous != null && previous.equals(next)) {
            return;
        }
        if (next.isReadOnlyDefault()) {
            LOGGER.info("MCP GraphQL gate is in READ-ONLY mode (whitelist is empty): every query is allowed "
                    + "except those under 'admin' (OSGi configuration values), mutations and subscriptions "
                    + "are denied. Add 'mutation:<path>' entries in "
                    + "Administration -> MCP Server to allow specific mutations.");
        }
        final Set<String> legacy = WhitelistEntries.legacyEntries(next.raw());
        if (!legacy.isEmpty()) {
            LOGGER.warn("MCP whitelist entries without an operation prefix are treated as query-only: {}. "
                    + "Rewrite them as 'query:<path>', and add 'mutation:<path>' entries for mutations.", legacy);
        }
        for (final String entry : next.raw()) {
            if (!WhitelistEntries.isValid(entry)) {
                LOGGER.warn("Ignoring malformed MCP whitelist entry '{}' (expected query:<path> or mutation:<path>)",
                        LogSupport.clean(entry));
            } else if (WhitelistEntries.isNeverAllowedEntry(entry)) {
                LOGGER.warn("Ignoring MCP whitelist entry '{}': the communityMcp and admin.jahia.configuration "
                        + "mutations can never be run through community.graphql.execute",
                        LogSupport.clean(entry));
            }
        }
    }

    /** Splits a comma-separated value; a non-blank value without any entry is kept as one malformed entry. */
    private static Set<String> parseList(final String value) {
        final String str = value.trim();
        if (str.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> entries = new LinkedHashSet<>();
        for (final String token : str.split(",")) {
            if (!token.isBlank()) {
                entries.add(token.trim());
            }
        }
        // A value made only of separators (e.g. "," in the .cfg) is NOT an empty whitelist: keeping
        // it as one malformed entry makes the gate deny everything instead of falling back to read-only.
        return entries.isEmpty() ? Set.of(str) : entries;
    }

    /** The current immutable configuration snapshot. */
    public WhitelistSnapshot snapshot() {
        return snapshot;
    }

    /** The whitelist entries as configured. */
    public Set<String> getWhitelist() {
        return snapshot.raw();
    }

    /** Logs a refused whitelist at WARN and returns the exception to throw (message is client-safe). */
    private static IllegalArgumentException rejected(final String message) {
        LOGGER.warn("Rejected MCP whitelist: {}", LogSupport.clean(message));
        return new IllegalArgumentException(message);
    }

    /**
     * Validates and persists {@code entries} as the new whitelist (an empty list selects the
     * read-only default).
     *
     * @throws IllegalArgumentException if an entry is malformed or can never take effect; the
     *         message names the offending entry and nothing is persisted
     * @throws IOException if ConfigurationAdmin cannot store the configuration
     */
    public void saveWhitelist(final List<String> entries) throws IOException {
        if (entries == null) {
            throw rejected("The whitelist must not be null");
        }
        for (final String entry : entries) {
            if (!WhitelistEntries.isValid(entry)) {
                throw rejected("Malformed whitelist entry '" + shown(entry)
                        + "': expected query:<path> or mutation:<path>, where <path> is dot-separated GraphQL names");
            }
            if (WhitelistEntries.isNeverAllowedEntry(entry)) {
                throw rejected("Whitelist entry '" + shown(entry)
                        + "' is refused: the communityMcp and admin.jahia.configuration mutations can never be run "
                        + "through community.graphql.execute");
            }
        }
        final Configuration config = configurationAdmin.getConfiguration(PID, null);
        Dictionary<String, Object> props = config.getProperties();
        if (props == null) {
            props = new Hashtable<>();
        }
        props.put(KEY_WHITELIST, String.join(",", entries));
        config.update(props);
    }

    private static String shown(final String entry) {
        if (entry == null) {
            return "null";
        }
        final String cleaned = LogSupport.clean(entry);
        return cleaned.length() <= MAX_SHOWN_ENTRY_LENGTH ? cleaned
                : cleaned.substring(0, MAX_SHOWN_ENTRY_LENGTH) + "...";
    }
}
