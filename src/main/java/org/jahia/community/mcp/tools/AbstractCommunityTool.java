package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jahia.community.mcp.LogSupport;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.modules.mcp.spi.tool.JahiaMcpTool;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Base of every community tool. {@link #execute} is final and always runs
 * {@code audit(accessGate(doExecute))}: a subclass cannot skip the administrator and
 * explicit-credential checks or the audit event. The input schema is loaded in the constructor (not in a static initializer) so a missing
 * resource fails a single DS component with a clear message.
 */
abstract class AbstractCommunityTool implements JahiaMcpTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCommunityTool.class);

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
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAccess(ctx);
        if (denied.isPresent()) {
            return ToolSupport.audited(ctx, name, denied.get(), Map.of());
        }
        ToolResult result;
        try {
            result = doExecute(input, ctx);
        } catch (StackOverflowError overflow) {
            LOGGER.error("{} exhausted the stack for user='{}'", name, LogSupport.clean(ctx.username()), overflow);
            result = ToolResult.error(ToolError.CODE_INTERNAL, "The request could not be processed.",
                    "Simplify the request (less nesting) and retry once, then report the failure.");
        } catch (Error error) {
            // Never swallowed: record the failed call, then let the Error propagate.
            ToolSupport.audited(ctx, name,
                    ToolResult.error(ToolError.CODE_INTERNAL, "The tool failed.", "See the Jahia logs."), Map.of());
            throw error;
        }
        return ToolSupport.audited(ctx, name, result, auditDetails(input));
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
