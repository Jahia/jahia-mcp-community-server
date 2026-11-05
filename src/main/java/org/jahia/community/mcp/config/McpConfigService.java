package org.jahia.community.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;

import java.util.Collections;
import java.util.Dictionary;
import java.util.LinkedHashSet;
import java.util.Set;

@Component(immediate = true, service = {McpConfigService.class, ManagedService.class},
        property = Constants.SERVICE_PID + "=org.jahia.community.mcp")
public class McpConfigService implements ManagedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpConfigService.class);

    @SuppressWarnings("java:S3077")
    private volatile Set<String> whitelist = Collections.emptySet();

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) {
            whitelist = Collections.emptySet();
            return;
        }
        whitelist = parseList(dictionary.get("whitelist"));
        final Set<String> legacy = WhitelistEntries.legacyEntries(whitelist);
        if (!legacy.isEmpty()) {
            LOGGER.warn("MCP whitelist entries without an operation prefix are treated as query-only: {}. "
                    + "Rewrite them as 'query:<path>', and add 'mutation:<path>' entries for mutations.", legacy);
        }
        for (final String entry : whitelist) {
            if (!WhitelistEntries.isValid(entry)) {
                LOGGER.warn("Ignoring malformed MCP whitelist entry '{}' (expected query:<path> or mutation:<path>)", entry);
            }
        }
    }

    private static Set<String> parseList(Object value) {
        if (value == null) {
            return Collections.emptySet();
        }
        final String str = value.toString().trim();
        if (str.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> entries = new LinkedHashSet<>();
        for (final String token : str.split(",")) {
            if (!token.isBlank()) {
                entries.add(token.trim());
            }
        }
        // A non-blank value with no entry in it (e.g. "," hand-written in the .cfg) is NOT an empty
        // whitelist: keeping it as one (malformed) entry makes the gate deny instead of allow-all.
        return Collections.unmodifiableSet(entries.isEmpty() ? new LinkedHashSet<>(Set.of(str)) : entries);
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }
}
