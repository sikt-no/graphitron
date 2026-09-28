package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This module boots one store per test thread plus the fixtures that own theirs, and nothing else.
 *
 * <p>A case's {@link StoreFixture} borrows the thread's store; what boots per call is named: a
 * fixture opened through {@link StoreFixture#held()}, which every {@code @BeforeAll}, static and
 * DDL-issuing fixture is, counted as {@link CapturedStore#ownedStores()}; and
 * {@link SdlDeprecations#stores()}. So every boot {@link FactStores} counted is one of those or
 * one per booting thread, and a helper that starts booting per case again fails the equality.
 *
 * <p>An equality against the booting threads rather than the literal four: the fixed pool adds
 * compensation threads when a task blocks, so the thread count is the run's rather than the
 * configuration's. {@code @Isolated} because the counts are only exact at rest, and JUnit runs
 * isolated classes after every concurrent class has finished, so this reads the whole run.
 */
@Isolated("reads the JVM-wide boot counts, which are exact only while nothing is booting")
class StoreBootCountTest {

    @Test
    void everyBootIsAThreadStoreOrANamedOwner(@TempDir Path tmp) {
        // One borrow of its own, so the equality is over at least one thread store.
        try (var fixture = StoreFixture.of(tmp, "type Query { x: Int }\n")) {
            assertThat(fixture.sourceName()).isNotBlank();
        }
        assertThat(SeededStore.threadStoreBoots())
            .as("one funnel boot per thread that borrowed")
            .isEqualTo(SeededStore.threadStoreBootingThreads());
        assertThat(FactStores.boots())
            .as("thread stores, owned fixtures and the shipped-deprecations reader account for"
                + " every boot; more means something opens a store per case outside them")
            .isEqualTo(SeededStore.threadStoreBoots() + CapturedStore.ownedStores()
                + SdlDeprecations.stores());
    }
}
