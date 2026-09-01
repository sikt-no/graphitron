package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.KeyProjection;
import no.sikt.graphitron.command.KeyProjectionRelation;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.derive.ResolvedKeyProjections;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link KeyProjectionCommands} produces: a command row per store row, and nothing else.
 *
 * <p>The cases are deliberately thin, and the thinness is the claim. This producer used to join the
 * store's rows against the walked model to fetch each node type's table, key list and decode
 * reference, and it carried two throws for the two ways those could disagree. Both are gone: the facts
 * are assembled where facts are read, so there is no second source here to reconcile and nothing that
 * can fail. A test that still found a disagreement to provoke here would mean the join had come back.
 *
 * <p>Which leaves two things worth pinning. The row carries every component through unchanged, so an
 * emitter reading one sees what the store resolved, the trailing segment's null included, that null
 * being the one component whose absence an emitter reads as a fact; and the relation's key holds, a
 * second answer for one coordinate and path being a contradiction rather than a precedence question.
 * Unit tier now rather than pipeline: with no model to build there is nothing to capture.
 *
 * @see no.sikt.graphitron.rewrite.derive.StoreNodeTablesTest the assembly this consumes, against a
 *      real captured store
 */
@UnitTier
class KeyProjectionRelationTest {

    private static final ColumnRef ACTOR_ID = new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Long");
    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Long");
    private static final TableRef FILM_ACTOR =
        TestFixtures.tableRef("film_actor", "FILM_ACTOR", "FilmActor", List.of(ACTOR_ID, FILM_ID));

    /**
     * Every component arrives on the row as the store had it, the projected column as a
     * {@link ColumnRef} rather than a name and the key list whole beside it. Naming the column is what
     * lets the emitted read name it too instead of indexing a tuple, and carrying the list is what
     * lets the decode's positional load be written at all.
     */
    @Test
    void aRowCarriesTheStoresFactsThrough() {
        var row = only(relationOf(projection("in.pairId.film_id", FILM_ID)));

        assertThat(row.coordinate().getTypeName()).isEqualTo("Mutation");
        assertThat(row.coordinate().getFieldName()).isEqualTo("pair");
        assertThat(row.argumentPath()).isEqualTo("in.pairId.film_id");
        assertThat(row.trailingSegmentName())
            .as("the author's own spelling of the column, which is where in the path the wire id sits")
            .isEqualTo("film_id");
        assertThat(row.nodeTypeName()).isEqualTo("FilmActor");
        assertThat(row.typeId())
            .as("the wire id the decode matches, which is the only decode input a row carries")
            .isEqualTo("FilmActor");
        assertThat(row.nodeTable().recordClass().simpleName()).isEqualTo("FilmActorRecord");
        assertThat(row.column()).isEqualTo(FILM_ID);
        assertThat(row.keyColumns())
            .as("the whole key in the order the decode returns values")
            .containsExactly(ACTOR_ID, FILM_ID);
    }

    /**
     * The inferred arm's absent trailing segment carries through as null rather than being filled in
     * with the resolved column's name. The distinction is the whole of what an emitter reads it for:
     * null says the encoded id sits at the whole written path, and a filled-in name would say it sits
     * one segment above, which is a decode of the wrong slot.
     */
    @Test
    void anInferredRowCarriesNoTrailingSegment() {
        var row = only(relationOf(projection("in.pairId", null, FILM_ID)));

        assertThat(row.argumentPath()).isEqualTo("in.pairId");
        assertThat(row.trailingSegmentName()).isNull();
        assertThat(row.column())
            .as("the column is still resolved; only the author's spelling of it is absent")
            .isEqualTo(FILM_ID);
    }

    /**
     * A blank trailing segment is refused rather than read as absent. Blank is neither an author's
     * spelling nor the absence of one, and admitting it would make the two resolutions
     * indistinguishable at the one component that tells them apart.
     */
    @Test
    void aBlankTrailingSegmentIsRefusedByTheRow() {
        assertThatThrownBy(() -> relationOf(projection("in.pairId.film_id", "  ", FILM_ID)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank is neither");
    }

    /**
     * A composite key projected at two positions is two rows carrying the same key list and different
     * columns. Neither carries a position: naming the column is what makes a transposed projection
     * unconstructable, so the row projecting the second column looks exactly like the first.
     */
    @Test
    void aCompositeKeyIsProjectedByNameAtAnyPosition() {
        var relation = relationOf(
            projection("in.pairId.actor_id", ACTOR_ID),
            projection("in.pairId.film_id", FILM_ID));

        assertThat(relation.rows()).hasSize(2);
        assertThat(relation.projectionFor("Mutation", "pair", "in.pairId.film_id").orElseThrow()
            .column()).isEqualTo(FILM_ID);
        assertThat(relation.rows()).allSatisfy(r -> assertThat(r.keyColumns())
            .containsExactly(ACTOR_ID, FILM_ID));
    }

    /**
     * An unprojected path is absent rather than a row saying so, which is what lets an emitter render
     * the ordinary nested read on row absence instead of testing a flag.
     */
    @Test
    void anUnprojectedPathHasNoRow() {
        assertThat(KeyProjectionCommands.produce(ResolvedKeyProjections.Projections.empty())
            .projectionFor("Mutation", "pair", "in.pairId"))
            .isEmpty();
    }

    /**
     * The relation's key is the coordinate plus the written path, and it holds the claim that no two
     * sites can disagree about a leaf they share: within one coordinate a path reaches one leaf and one
     * node type whatever directive spelled it, so a second answer for one key is a contradiction
     * rather than a precedence question.
     */
    @Test
    void onePathAtOneCoordinateCannotResolveTwoColumns() {
        assertThatThrownBy(() -> relationOf(
            projection("in.pairId.actor_id", ACTOR_ID),
            projection("in.pairId.actor_id", FILM_ID)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keyed by coordinate and written path");
    }

    /**
     * The row's own invariant, which survives the move: a projected column that is not one of the key
     * columns beside it could never come out of a decode of that node id, so the carrier refuses to
     * hold the pair rather than letting an emitter write a read that cannot resolve.
     */
    @Test
    void aColumnOutsideTheKeyListIsRefusedByTheRow() {
        var stray = new ColumnRef("last_update", "LAST_UPDATE", "java.time.LocalDateTime");
        assertThatThrownBy(() -> relationOf(projection("in.pairId.last_update", stray)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not one of 'FilmActor's key columns");
    }

    private static ResolvedKeyProjections.Projection projection(String path, ColumnRef column) {
        return projection(path, path.substring(path.lastIndexOf('.') + 1), column);
    }

    private static ResolvedKeyProjections.Projection projection(String path,
            String trailingSegmentName, ColumnRef column) {
        return new ResolvedKeyProjections.Projection("Mutation", "pair", path, trailingSegmentName,
            "FilmActor", "FilmActor", FILM_ACTOR, List.of(ACTOR_ID, FILM_ID), column);
    }

    private static KeyProjectionRelation relationOf(ResolvedKeyProjections.Projection... rows) {
        return KeyProjectionCommands.produce(
            new ResolvedKeyProjections.Projections(List.of(rows)));
    }

    private static KeyProjection only(KeyProjectionRelation relation) {
        assertThat(relation.rows()).hasSize(1);
        return relation.rows().getFirst();
    }
}
