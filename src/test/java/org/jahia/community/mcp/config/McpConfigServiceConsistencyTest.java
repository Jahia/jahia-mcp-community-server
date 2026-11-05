package org.jahia.community.mcp.config;

import org.jahia.community.mcp.access.Denial;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.service.cm.ConfigurationAdmin;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("McpConfigService: snapshot visibility and failed updates")
class McpConfigServiceConsistencyTest {

    private static final String DOC = "mutation { jcr { deleteNode(pathOrId: \"/x\") } }";

    private static McpConfigService.Config config(final String... whitelist) {
        final McpConfigService.Config config = mock(McpConfigService.Config.class);
        when(config.whitelist()).thenReturn(whitelist);
        return config;
    }

    @Test
    void should_keep_the_previous_snapshot_when_an_update_cannot_be_read() {
        final McpConfigService service = new McpConfigService(mock(ConfigurationAdmin.class),
                config("mutation:jcr.deleteNode"));
        final WhitelistSnapshot before = service.snapshot();
        final McpConfigService.Config broken = mock(McpConfigService.Config.class);
        when(broken.whitelist()).thenThrow(new IllegalStateException("garbled"));

        service.modified(broken);

        assertThat(service.snapshot()).isSameAs(before);
        assertThat(service.snapshot().isReadOnlyDefault()).isFalse();
    }

    @Test
    void should_deny_everything_when_the_first_configuration_cannot_be_read() {
        final McpConfigService.Config broken = mock(McpConfigService.Config.class);
        when(broken.whitelist()).thenThrow(new IllegalStateException("garbled"));

        final McpConfigService service = new McpConfigService(mock(ConfigurationAdmin.class), broken);

        assertThat(service.snapshot().isReadOnlyDefault()).isFalse();
        final GraphQLWhitelistGate gate = new GraphQLWhitelistGate(service);
        assertThat(gate.evaluate("{ jcr { nodeByPath } }", "u", "ip")).isPresent();
        assertThat(gate.evaluate(DOC, "u", "ip")).isPresent();
    }

    @Test
    void should_never_show_a_reader_an_in_between_policy_while_the_whitelist_flips() throws Exception {
        final McpConfigService service = new McpConfigService(mock(ConfigurationAdmin.class),
                config("mutation:jcr.deleteNode"));
        final GraphQLWhitelistGate gate = new GraphQLWhitelistGate(service);
        final McpConfigService.Config allowing = config("mutation:jcr.deleteNode");
        final McpConfigService.Config denying = config("query:other");
        final int readers = 4;
        final ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger inconsistent = new AtomicInteger();
        final AtomicInteger checks = new AtomicInteger();
        try {
            final Future<?> writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2000; i++) {
                    service.modified(i % 2 == 0 ? denying : allowing);
                }
                stop.set(true);
                return null;
            });
            final java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int r = 0; r < readers; r++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    while (!stop.get()) {
                        final Optional<Denial> verdict = gate.evaluate(DOC, "u", "ip");
                        checks.incrementAndGet();
                        // allowed under "allowing"; under "denying" it is a plain whitelist refusal, never
                        // a different kind, never an unexpected exception
                        if (verdict.isPresent() && verdict.get().kind() != Denial.Kind.MUTATION_NOT_ALLOWED) {
                            inconsistent.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            for (final Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(checks.get()).isPositive();
        assertThat(inconsistent).hasValue(0);
    }

    @Test
    void should_refuse_to_save_entries_that_can_only_grant_privileges() {
        final McpConfigService service = new McpConfigService(mock(ConfigurationAdmin.class), config());

        assertThatThrownBy(() -> service.saveWhitelist(java.util.List.of("mutation:jcr.mutateNode.grantRoles")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can never be run")
                .hasMessageContaining("grantRoles");
        assertThatThrownBy(() -> service.saveWhitelist(java.util.List.of("mutation:admin.personalApiTokens.createToken")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("personalApiTokens");
    }
}
