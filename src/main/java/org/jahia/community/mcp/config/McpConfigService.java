package org.jahia.community.mcp.config;

import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.LinkedHashSet;
import java.util.Set;

@Component(immediate = true, service = {McpConfigService.class, ManagedService.class},
        property = Constants.SERVICE_PID + "=org.jahia.community.mcp")
public class McpConfigService implements ManagedService {

    @SuppressWarnings("java:S3077")
    private volatile Set<String> whitelist = Collections.emptySet();

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) {
            whitelist = Collections.emptySet();
            return;
        }
        whitelist = parseList(dictionary.get("whitelist"));
    }

    private static Set<String> parseList(Object value) {
        if (value == null) {
            return Collections.emptySet();
        }
        final String str = value.toString().trim();
        if (str.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(str.split("\\s*,\\s*"))));
    }

    public Set<String> getWhitelist() {
        return whitelist;
    }
}
