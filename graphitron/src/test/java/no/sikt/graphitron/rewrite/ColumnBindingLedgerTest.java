package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The column-binding ledger's own checks, on hand-built rows and members: a check nobody has seen
 * fire is not yet an enforcer.
 */
@UnitTier
class ColumnBindingLedgerTest {

    private static final FieldCoordinates FILMS = FieldCoordinates.coordinates("Query", "films");
    private static final ColumnRef FILM_ID = TestFixtures.filmIdCol();
    private static final ColumnRef ACTOR_ID = TestFixtures.col("actor_id", "ACTOR_ID", "java.lang.Integer");
    private static final TableRef FILM = TestFixtures.filmTable();
    private static final TableRef FILM_ACTOR =
        TestFixtures.tableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of(ACTOR_ID, FILM_ID));
    private static final CallSiteExtraction DIRECT = new CallSiteExtraction.Direct();

    private static BodyParam eq(String name, ColumnRef column, CallSiteExtraction extraction) {
        return new BodyParam.Eq(name, column, column.columnClass(), false, extraction);
    }

    private static GeneratedConditionFilter filterOf(TableRef table, BodyParam... params) {
        return new GeneratedConditionFilter(table, List.of(), List.of(params));
    }

    private static OperationMemberRelation conditionOn(TableRef table, WhereFilter filter) {
        return new OperationMemberRelation(Map.of(FILMS,
            List.of(new OperationMember.Condition.OnReturnTable(table, List.of(filter)))));
    }

    private static ColumnBindingLedger.ColumnBoundSlot slot(String name, List<ColumnRef> columns,
                                                            CallSiteExtraction extraction) {
        return new ColumnBindingLedger.ColumnBoundSlot(name, columns, extraction);
    }

    @Test
    void aPredicateWithNoSlotInItsRowFails() {
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("filmId", FILM_ID, DIRECT));
        ledger.record(FILMS, FILM, List.of(), List.of(filter));

        assertThatThrownBy(() -> ledger.requireCovers(conditionOn(FILM, filter)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films")
            .hasMessageContaining("table 'film'")
            .hasMessageContaining("slot 'filmId'");
    }

    @Test
    void aSlotRecordedUnderAnotherTableDoesNotCover() {
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("filmId", FILM_ID, DIRECT));
        ledger.record(FILMS, FILM_ACTOR, List.of(slot("filmId", List.of(FILM_ID), DIRECT)), List.of(filter));

        assertThatThrownBy(() -> ledger.requireCovers(conditionOn(FILM, filter)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films")
            .hasMessageContaining("table 'film'")
            .hasMessageContaining("slot 'filmId'");
    }

    @Test
    void aSlotMatchingOnNameAndColumnsButNotExtractionDoesNotCover() {
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("filmId", FILM_ID, new CallSiteExtraction.JooqConvert("FILM_ID")));
        ledger.record(FILMS, FILM, List.of(slot("filmId", List.of(FILM_ID), DIRECT)), List.of(filter));

        assertThatThrownBy(() -> ledger.requireCovers(conditionOn(FILM, filter)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films")
            .hasMessageContaining("table 'film'")
            .hasMessageContaining("slot 'filmId'");
    }

    @Test
    void aRowMayHoldSlotsBeyondTheEmittedPredicates() {
        // The suppressed bindings: the row names a slot whose predicate an authored @condition
        // took over, so no body param stands for it.
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("title", TestFixtures.titleCol(), DIRECT));
        ledger.record(FILMS, FILM, List.of(
            slot("filmId", List.of(FILM_ID), DIRECT),
            slot("title", List.of(TestFixtures.titleCol()), DIRECT)), List.of(filter));

        assertThatCode(() -> ledger.requireCovers(conditionOn(FILM, filter))).doesNotThrowAnyException();
    }

    @Test
    void aCompositeRemotePredicateIsCoveredThroughItsInnerPredicate() {
        var ledger = new ColumnBindingLedger();
        var tuple = List.of(ACTOR_ID, FILM_ID);
        var hop = TestFixtures.fkJoin(TestFixtures.foreignKeyRef("film_actor_film_id_fkey"),
            FILM, List.of(FILM_ID), FILM_ACTOR, List.of(FILM_ID), null, "film_actor_0");
        var remote = new BodyParam.RemoteColumnPredicate(List.of(hop),
            new BodyParam.RowEq("actorFilm", tuple, false, DIRECT));
        var filter = filterOf(FILM, remote);
        ledger.record(FILMS, FILM, List.of(slot("actorFilm", tuple, DIRECT)), List.of(filter));

        assertThatCode(() -> ledger.requireCovers(conditionOn(FILM, filter))).doesNotThrowAnyException();
    }

    @Test
    void aRepeatRecordWithAnEqualRowPasses() {
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("filmId", FILM_ID, DIRECT));
        var row = List.of(slot("filmId", List.of(FILM_ID), DIRECT));
        ledger.record(FILMS, FILM, row, List.of(filter));

        assertThatCode(() -> ledger.record(FILMS, FILM, List.copyOf(row), List.of(filter)))
            .doesNotThrowAnyException();
    }

    @Test
    void aRepeatRecordWithADifferentRowFails() {
        var ledger = new ColumnBindingLedger();
        var filter = filterOf(FILM, eq("filmId", FILM_ID, DIRECT));
        ledger.record(FILMS, FILM, List.of(slot("filmId", List.of(FILM_ID), DIRECT)), List.of(filter));

        assertThatThrownBy(() -> ledger.record(FILMS, FILM,
                List.of(slot("otherFilm", List.of(FILM_ID), DIRECT)), List.of(filter)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films")
            .hasMessageContaining("table 'film'");
    }

    @Test
    void aNonEmptyRowWithNoFiltersFails() {
        var ledger = new ColumnBindingLedger();

        assertThatThrownBy(() -> ledger.record(FILMS, FILM,
                List.of(slot("filmId", List.of(FILM_ID), DIRECT)), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films")
            .hasMessageContaining("filmId");
    }
}
