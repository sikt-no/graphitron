package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.model.Tables.META_RELATION;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derivation stratum only shrinks.
 *
 * <p>The derivation gatherer is dissolving: a relation it writes is recomputed in full on every
 * capture, after every other gatherer, whether or not anything it reads changed. A new derivation
 * belongs in the anchor phase of the gatherer that owns what it reads, marked and swept. The gate is
 * here because nothing else notices: a new stage is written by copying the one beside it, and
 * {@link DerivationStratum#steps} is the file it copies.
 *
 * <p>Two counts, each with its own ceiling. The relations the stratum's steps write, and, as a
 * backstop for a relation registered to the gatherer without a step, the {@code meta_relation} rows
 * it owns. A ceiling rather than a roster of names, because dissolutions run in several sessions at
 * once and a roster would collide on every one of them. A count may be stale on the low side; what
 * it may not do is rise. Lower a ceiling in the commit that dissolves a relation.
 */
class DerivationStratumCeilingGateTest {

    /** Relations {@link DerivationStratum#steps} writes on 2026-10-30, over 17 steps. */
    private static final int WRITTEN_CEILING = 20;

    /** {@code meta_relation} rows owned by {@code derivation} on 2026-10-30. */
    private static final int OWNED_CEILING = 10;

    private static final String WHERE_INSTEAD = "the derivation gatherer is dissolving: a new derivation "
        + "goes in the anchor phase of the gatherer that owns what it reads, marked and swept "
        + "(docs/architecture/explanation/modeling-discipline.adoc)";

    @Test
    @DisplayName("no step adds a relation to the derivation stratum")
    void theStratumWritesNoMoreRelations() {
        long written = DerivationStratum.steps(null).stream()
            .mapToLong(step -> step.writes().size())
            .sum();
        assertThat(written).as(WHERE_INSTEAD).isLessThanOrEqualTo(WRITTEN_CEILING);
    }

    @Test
    @DisplayName("no relation is registered to the derivation gatherer")
    void theGathererOwnsNoMoreRelations() {
        ThreadConfinedStore.run(dsl -> {
            int owned = dsl.fetchCount(META_RELATION, META_RELATION.OWNER_NAME.eq("derivation"));
            assertThat(owned).as(WHERE_INSTEAD).isLessThanOrEqualTo(OWNED_CEILING);
        });
    }
}
