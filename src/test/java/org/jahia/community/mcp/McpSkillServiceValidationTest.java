package org.jahia.community.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for McpSkillService.validateSkillName (package-private static helper).
 * No JCR container needed — pure logic test.
 */
class McpSkillServiceValidationTest {

    @Nested
    @DisplayName("validateSkillName — accepted inputs")
    class AcceptedInputs {

        @ParameterizedTest(name = "[{index}] \"{0}\" is accepted")
        @ValueSource(strings = {
                "hello-jahia",
                "mySkill",
                "my_skill",
                "my-skill-123",
                "default/hello-jahia",
                "a/b/c",
                "A1/B2/C3",
                "skill_1/skill-2"
        })
        @DisplayName("valid names are accepted")
        void valid_names_accepted(String name) {
            assertThat(McpSkillService.validateSkillName(name)).isEqualTo(name);
        }
    }

    @Nested
    @DisplayName("validateSkillName — rejected inputs")
    class RejectedInputs {

        @Test
        @DisplayName("null is rejected")
        void null_rejected() {
            assertThatThrownBy(() -> McpSkillService.validateSkillName(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null or blank");
        }

        @Test
        @DisplayName("blank string is rejected")
        void blank_rejected() {
            assertThatThrownBy(() -> McpSkillService.validateSkillName("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null or blank");
        }

        @ParameterizedTest(name = "[{index}] traversal name \"{0}\" is rejected")
        @ValueSource(strings = {
                "../etc/passwd",
                "../../root",
                "valid/../evil",
                "a/../../b"
        })
        @DisplayName("path-traversal names are rejected")
        void traversal_names_rejected(String name) {
            assertThatThrownBy(() -> McpSkillService.validateSkillName(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "[{index}] name with colon/dot/slash prefix \"{0}\" is rejected")
        @ValueSource(strings = {
                "/absolute",
                "foo:bar",
                "foo.bar",
                "a//b",
                "skill name",
                "skill name with spaces"
        })
        @DisplayName("names with illegal characters are rejected")
        void illegal_character_names_rejected(String name) {
            assertThatThrownBy(() -> McpSkillService.validateSkillName(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("single dot segment is rejected")
        void single_dot_segment_rejected() {
            assertThatThrownBy(() -> McpSkillService.validateSkillName("a/./b"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("SKILL_NAME_PATTERN regex")
    class PatternTest {

        @Test
        @DisplayName("pattern rejects empty string")
        void pattern_rejects_empty() {
            assertThat(McpSkillService.SKILL_NAME_PATTERN.matcher("").matches()).isFalse();
        }

        @Test
        @DisplayName("pattern rejects trailing slash")
        void pattern_rejects_trailing_slash() {
            assertThat(McpSkillService.SKILL_NAME_PATTERN.matcher("valid/").matches()).isFalse();
        }

        @Test
        @DisplayName("pattern rejects leading slash")
        void pattern_rejects_leading_slash() {
            assertThat(McpSkillService.SKILL_NAME_PATTERN.matcher("/valid").matches()).isFalse();
        }

        @Test
        @DisplayName("pattern accepts deep path")
        void pattern_accepts_deep_path() {
            assertThat(McpSkillService.SKILL_NAME_PATTERN.matcher("a/b/c/d").matches()).isTrue();
        }
    }
}
