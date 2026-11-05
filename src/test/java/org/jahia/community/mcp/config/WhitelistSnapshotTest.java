package org.jahia.community.mcp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WhitelistSnapshot")
class WhitelistSnapshotTest {

    private static WhitelistSnapshot snap(final Set<String> raw, final Set<String> normalized, final int depth) {
        return new WhitelistSnapshot(raw, normalized, depth);
    }

    @Test
    void should_be_equal_to_a_snapshot_with_the_same_content_and_share_its_hash_code() {
        final WhitelistSnapshot a = WhitelistSnapshot.of(List.of("query:jcr", "mutation:jcr.deleteNode"));
        final WhitelistSnapshot b = WhitelistSnapshot.of(List.of("query:jcr", "mutation:jcr.deleteNode"));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isEqualTo(a);
    }

    @Test
    void should_differ_when_only_raw_differs() {
        assertThat(snap(Set.of("a"), Set.of("query.a"), 2)).isNotEqualTo(snap(Set.of("b"), Set.of("query.a"), 2));
    }

    @Test
    void should_differ_when_only_normalized_differs() {
        assertThat(snap(Set.of("a"), Set.of("query.a"), 2)).isNotEqualTo(snap(Set.of("a"), Set.of("query.b"), 2));
    }

    @Test
    void should_differ_when_only_max_depth_differs() {
        assertThat(snap(Set.of("a"), Set.of("query.a"), 2)).isNotEqualTo(snap(Set.of("a"), Set.of("query.a"), 3));
    }

    @Test
    void should_not_equal_null_or_another_type() {
        final WhitelistSnapshot a = WhitelistSnapshot.of(List.of());
        assertThat(a).isNotEqualTo(null).isNotEqualTo("snapshot");
    }

    @Test
    void should_measure_the_deepest_entry_including_the_operation_type() {
        assertThat(WhitelistSnapshot.of(List.of("mutation:a.b.c.d")).maxDepth()).isEqualTo(5);
        assertThat(WhitelistSnapshot.of(List.of()).maxDepth()).isEqualTo(2);
        assertThat(WhitelistSnapshot.of(List.of("query:a", "garbage entry")).maxDepth()).isEqualTo(2);
    }

    @Test
    void should_be_read_only_default_exactly_when_nothing_is_configured() {
        assertThat(WhitelistSnapshot.of(List.of()).isReadOnlyDefault()).isTrue();
        assertThat(WhitelistSnapshot.of(List.of("garbage")).isReadOnlyDefault()).isFalse();
    }

    @Test
    void should_be_immutable_and_not_follow_later_changes_to_its_source() {
        final Set<String> source = new LinkedHashSet<>(Set.of("query:jcr"));
        final WhitelistSnapshot s = WhitelistSnapshot.of(source);
        source.add("query:other");

        assertThat(s.raw()).containsExactly("query:jcr");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.raw().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.normalized().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
