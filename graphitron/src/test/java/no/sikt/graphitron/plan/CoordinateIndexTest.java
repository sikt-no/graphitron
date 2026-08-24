package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The coordinate-keyed row set every command relation with that key holds: the key rejection,
 * producer order, and the lookup. Pinned here rather than once per relation because the point of
 * the carrier is that the three relations do not each own a copy of this behaviour, so a check
 * living on one of them would be testing that relation's delegation and not the invariant.
 *
 * <p>Order is asserted on all three read surfaces because it is load-bearing rather than
 * incidental: consumers fold rows into emitted files, and a map that read out in hash order would
 * make generated output depend on coordinate names.
 */
@UnitTier
class CoordinateIndexTest {

    private record Row(FieldCoordinates coordinate, String payload) {}

    private static Row row(String type, String field) {
        return new Row(FieldCoordinates.coordinates(type, field), type + "." + field);
    }

    private static CoordinateIndex<Row> indexOf(Row... rows) {
        return CoordinateIndex.of(List.of(rows), Row::coordinate, "test");
    }

    @Test
    void aCoordinateAppearingTwiceIsRejectedAndTheMessageNamesBothTheRelationAndTheKey() {
        var duplicate = row("Query", "films");
        assertThatThrownBy(() -> indexOf(duplicate, row("Query", "actors"), duplicate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("test relation is keyed by coordinate")
            .hasMessageContaining("Query.films");
    }

    /**
     * Two rows that differ only in payload still collide: the key is the coordinate, so "the same
     * coordinate twice" is the rejection whether or not the producer minted equal rows. A relation
     * that wants the merge has the wrong key.
     */
    @Test
    void divergingRowsUnderOneCoordinateCollideThesameWayEqualOnesDo() {
        var first = new Row(FieldCoordinates.coordinates("Query", "films"), "one");
        var second = new Row(FieldCoordinates.coordinates("Query", "films"), "two");
        assertThatThrownBy(() -> indexOf(first, second))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("appeared twice");
    }

    @Test
    void rowsCoordinatesAndTheMapAllReadOutInProducerOrder() {
        var index = indexOf(row("Query", "zeta"), row("Query", "alpha"), row("Film", "title"));
        assertThat(index.rows()).extracting(Row::payload)
            .containsExactly("Query.zeta", "Query.alpha", "Film.title");
        assertThat(index.coordinates()).containsExactly(
            FieldCoordinates.coordinates("Query", "zeta"),
            FieldCoordinates.coordinates("Query", "alpha"),
            FieldCoordinates.coordinates("Film", "title"));
        assertThat(index.byCoordinate().keySet()).containsExactly(
            FieldCoordinates.coordinates("Query", "zeta"),
            FieldCoordinates.coordinates("Query", "alpha"),
            FieldCoordinates.coordinates("Film", "title"));
    }

    @Test
    void theLookupAnswersForAHeldCoordinateAndIsEmptyForAnythingElse() {
        var index = indexOf(row("Query", "films"), row("Film", "title"));
        assertThat(index.rowFor("Query", "films")).map(Row::payload).contains("Query.films");
        assertThat(index.rowFor("Query", "actors")).isEmpty();
        // The coordinate is the pair, so neither half alone matches: a field name held under a
        // different parent type is a different key, not a near miss the lookup should forgive.
        assertThat(index.rowFor("Actor", "title")).isEmpty();
    }

    @Test
    void anEmptyRelationIsAValueRatherThanARejection() {
        var index = CoordinateIndex.<Row>of(List.of(), Row::coordinate, "test");
        assertThat(index.rows()).isEmpty();
        assertThat(index.coordinates()).isEmpty();
        assertThat(index.rowFor("Query", "films")).isEmpty();
    }

    @Test
    void theRowsAreCopiedSoALaterMutationOfTheProducersListCannotReachTheRelation() {
        var mutable = new java.util.ArrayList<>(List.of(row("Query", "films")));
        var index = CoordinateIndex.of(mutable, Row::coordinate, "test");
        mutable.add(row("Query", "actors"));
        assertThat(index.rows()).hasSize(1);
        assertThat(index.rowFor("Query", "actors")).isEmpty();
    }

    @Test
    void bothReadSurfacesRejectMutationByAConsumer() {
        var index = indexOf(row("Query", "films"));
        assertThatThrownBy(() -> index.rows().add(row("Query", "actors")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> index.byCoordinate()
                .put(FieldCoordinates.coordinates("Query", "actors"), row("Query", "actors")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
