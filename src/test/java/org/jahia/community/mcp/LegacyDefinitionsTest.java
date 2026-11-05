package org.jahia.community.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Node types written by 1.x must stay readable by 2.x: the CND may add supertypes but must keep
 * the type names, the mixin that every skill carries and the property names.
 */
@DisplayName("definitions.cnd keeps the 1.x node types usable after an upgrade")
class LegacyDefinitionsTest {

    private static String cnd() throws IOException {
        try (InputStream in = LegacyDefinitionsTest.class.getResourceAsStream("/META-INF/definitions.cnd")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void should_keep_the_mixin_with_its_legacy_supertypes_and_the_hidden_type_addition() throws IOException {
        final String cnd = cnd();

        assertThat(cnd).containsPattern("\\[mcpmix:mcp\\]\\s*>[^\\n]*jmix:accessControllableContent")
                .containsPattern("\\[mcpmix:mcp\\]\\s*>[^\\n]*jmix:droppableContent")
                .containsPattern("\\[mcpmix:mcp\\]\\s*>[^\\n]*jmix:hiddenType[^\\n]*\\bmixin\\b");
    }

    @Test
    void should_keep_the_skill_type_its_mixin_and_the_property_names_used_by_1x() throws IOException {
        final String cnd = cnd();

        assertThat(cnd).containsPattern("\\[mcp:skill\\]\\s*>[^\\n]*jnt:content")
                .containsPattern("\\[mcp:skill\\]\\s*>[^\\n]*mcpmix:mcp")
                .containsPattern("\\[mcp:skill\\]\\s*>[^\\n]*mix:title")
                .contains("- mcp:description (string)").contains("- mcp:content (string, textarea)");
    }

    @Test
    void should_declare_the_namespaces_the_stored_1x_nodes_use() throws IOException {
        assertThat(cnd()).contains("<mcp = 'http://www.jahia.org/jahia/mcp/1.0'>")
                .contains("<mcpmix = 'http://www.jahia.org/jahia/mcpmix/1.0'>");
    }
}
