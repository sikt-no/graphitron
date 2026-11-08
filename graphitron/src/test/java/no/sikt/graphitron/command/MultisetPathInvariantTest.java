package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An inline multiset reads over a join path to the parent: a field on its parent's own table
 * reaches it over a written or inferred key, so neither multiset takes an empty path.
 */
@UnitTier
class MultisetPathInvariantTest {

    @Test
    void multiset_refusesAnEmptyPath() {
        assertThatThrownBy(() -> new CallWrap.Multiset(List.of(), null, TestFixtures.filmTable(), Arity.LIST,
                new OrderBySpec.None(), List.of(), false, null, false, List.of(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("an empty one correlates nothing to the parent");
    }

    @Test
    void lookupMultiset_refusesAnEmptyPath() {
        var film = TestFixtures.filmTable();
        var mapping = new LookupMapping.ColumnMapping(
            List.of(new LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg(
                "film_id", new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"),
                new CallSiteExtraction.Direct(), false)),
            film);
        var units = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);
        assertThatThrownBy(() -> new CallWrap.LookupMultiset(List.of(), null, film, mapping,
                units.inputRowsMethod(units.fetchers("Query"), "films"), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("an empty one correlates nothing to the parent");
    }
}
