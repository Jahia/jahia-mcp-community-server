package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.modules.mcp.spi.tool.JahiaMcpTool;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolResult;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base of every community tool. {@link #execute} is final and always runs
 * {@code audit(adminGate(doExecute))}: a subclass cannot skip the administrator check or the audit
 * event. The input schema is loaded in the constructor (not in a static initializer) so a missing
 * resource fails a single DS component with a clear message.
 */
abstract class AbstractCommunityTool implements JahiaMcpTool {

    private final String name;
    private final JsonNode schema;

    protected AbstractCommunityTool(final String name) {
        this.name = name;
        this.schema = ToolSupport.loadSchema(name);
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final JsonNode inputSchema() {
        return schema;
    }

    @Override
    public Set<String> tags() {
        return Set.of("community");
    }

    @Override
    public final ToolResult execute(final JsonNode input, final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        final ToolResult result = denied.isPresent() ? denied.get() : doExecute(input, ctx);
        return ToolSupport.audited(ctx, name, result, denied.isPresent() ? Map.of() : auditDetails(input));
    }

    /** Runs the tool; only reached for administrators. */
    protected abstract ToolResult doExecute(JsonNode input, ToolContext ctx);

    /** Extra audit details derived from the input (never content or secrets). */
    // java:S1172 false positive: template-method hook; the default ignores the input, overrides
    // (GraphQLExecuteTool) read it.
    @SuppressWarnings("java:S1172")
    protected Map<String, Object> auditDetails(final JsonNode input) {
        return Map.of();
    }
}
