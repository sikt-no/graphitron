package no.sikt.graphitron.model;

import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedOrderBy;
import static no.sikt.graphitron.model.test.SeededStore.seedRootOperation;
import static no.sikt.graphitron.model.test.SeededStore.seedRoutine;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code graphitron_entry_defect} answers: one row per written directive the generator will
 * not emit for, at the position it was written, naming the rule and nothing else. The relation
 * replaces a set of sibling relations that differed almost only in payload, so the cases here are
 * as much about the shape as about the three rules currently in it.
 *
 * <p>The key is the entry, not the coordinate. A coordinate is an aggregate over the sites that
 * declare it, so a write carrying a {@code @condition} and an {@code @orderBy} answers at each of
 * them rather than once for the field. The converse holds too: one entry breaking two rules
 * carries two rows, there being no ranking here and nothing that would choose between them.
 *
 * <p>Every rule here is about a write, so each has the same mask, a {@code @routine} on a mutation
 * root. A case states the mask by seeding the identical arrangement on {@code Query}, which is the
 * only fixture that tells a mask from an empty one.
 *
 * <p>Nothing here is about the order of a field's chain. Where a chain departs from, and whether
 * consecutive links meet, is resolved by the assembly gatherer and stated on
 * {@code graphitron_field_table_link}, whose own links are the population a chain defect would be
 * read off by anti-join. Restating any of it here would be a second walk over a resolved fact.
 */
class EntryDefectTest {

    private static final String GRAPH = "g";
    private static final String SOURCE = "seed.graphqls";

    /**
     * The mask every rule here stands on. A {@code @routine} on a query root heads no write, so the same
     * arrangement of directives is a schema with nothing wrong with it. Seeding the identical
     * fixture on {@code Query} is the only arrangement that tells the mask from an empty result.
     */
    @Test
    @DisplayName("the rules are about a write, so the same arrangement on a query root answers nothing")
    void aRoutineOnAQueryRootHeadsNothing() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "QUERY", "Query");
            seedField(dsl, GRAPH, "Query", "film");
            seedRoutine(dsl, GRAPH, "Query", "film", 0, "film_fn", 5);
            seedFieldReference(dsl, GRAPH, "Query", "film", 0);
            seedFieldCondition(dsl, GRAPH, "Query", "film", null, null, null, 2);
            seedArgument(dsl, GRAPH, "Query", "film", "id", "ID");
            seedArgumentCondition(dsl, GRAPH, "Query", "film", "id", null, null, null, 3);
            seedOrderBy(dsl, GRAPH, "Query", "film", "id", 4);

            assertThat(defects(dsl)).isEmpty();
        });
    }

    /**
     * The read-surface rule, stated at all three of the sites that can carry it. A write takes no
     * read surface, and the {@code detail} is which surface was written: one column, because what
     * a consumer needs beyond it is a join from the entry.
     */
    @Test
    @DisplayName("each read-surface site on a write answers at its own position with its own detail")
    void everyReadSurfaceSiteOnAWriteAnswersForItself() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedField(dsl, GRAPH, "Mutation", "insertFilm");
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 0, "film_fn", 1);
            seedFieldCondition(dsl, GRAPH, "Mutation", "insertFilm", null, null, null, 2);
            seedArgument(dsl, GRAPH, "Mutation", "insertFilm", "id", "ID");
            seedArgumentCondition(dsl, GRAPH, "Mutation", "insertFilm", "id", null, null, null, 3);
            seedOrderBy(dsl, GRAPH, "Mutation", "insertFilm", "id", 4);

            assertThat(defects(dsl)).containsExactly(
                "2:3 READ_SURFACE_ON_WRITE condition",
                "3:3 READ_SURFACE_ON_WRITE condition",
                "4:3 READ_SURFACE_ON_WRITE orderBy");
        });
    }

    /**
     * Two writes on one field. The individual applications are each legal, so the row sits at the
     * head rather than at a surplus one: what has no emitter is the plurality, and there is nothing
     * here for an author to delete.
     */
    @Test
    @DisplayName("more than one routine on a field is one defect, at the head")
    void aSecondRoutineIsOneDefectAtTheHead() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedField(dsl, GRAPH, "Mutation", "insertFilm");
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 0, "first_fn", 1);
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 1, "second_fn", 5);

            assertThat(defects(dsl)).containsExactly("5:3 MULTIPLE_ROUTINE_NODES null");
        });
    }

    /**
     * A paginated return on a write. This one runs a capture rather than seeding rows, because the
     * connection type the rule reads is not in the corpus at all: {@code @asConnection} mints it,
     * and the relation the arm joins is the post-macro field table. A seeded fixture would state a
     * world the macro never produces.
     *
     * <p>Two rules break at once and both are reported, where the relation this one replaces ranked
     * them and answered with the more fundamental alone.
     */
    @Test
    @DisplayName("a connection return on a write is a defect, and it does not displace another")
    void aConnectionReturnOnAWriteIsItsOwnDefect(@TempDir Path tmp) {
        withCapturedStore(tmp, """
            type Query { film: Film }
            type Film { id: ID }
            type Mutation {
              insertFilms: [Film] @asConnection @routine(name: "first_fn") @routine(name: "second_fn")
            }
            """, dsl -> assertThat(codes(dsl))
                .containsExactly("CONNECTION_RETURN", "MULTIPLE_ROUTINE_NODES"));
    }

    /**
     * The same corpus without the pagination and with one routine, which is what tells both arms
     * from a fixture that reports whatever it is given.
     */
    @Test
    @DisplayName("a plain return and one routine draw neither defect")
    void aPlainSingleRoutineWriteDrawsNeither(@TempDir Path tmp) {
        withCapturedStore(tmp, """
            type Query { film: Film }
            type Film { id: ID }
            type Mutation {
              insertFilms: [Film] @routine(name: "first_fn")
            }
            """, dsl -> assertThat(codes(dsl)).isEmpty());
    }

    /**
     * The vocabulary is a relation and the code is a reference into it, so a code the view emits
     * that nothing declares is a fact the store can state rather than a string nobody checks.
     */
    @Test
    @DisplayName("every code the relation emits is a declared defect type")
    void everyCodeIsDeclared() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedField(dsl, GRAPH, "Mutation", "insertFilm");
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 0, "first_fn", 1);
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 1, "second_fn", 5);
            seedFieldCondition(dsl, GRAPH, "Mutation", "insertFilm", null, null, null, 3);

            assertThat(defects(dsl)).as("the fixture reaches two codes, so the anti-join is not vacuous")
                .hasSize(2);
            assertThat(dsl.selectDistinct(GRAPHITRON_ENTRY_DEFECT.CODE)
                    .from(GRAPHITRON_ENTRY_DEFECT)
                    .whereNotExists(dsl.selectOne().from(GRAPHITRON_DEFECT_TYPE)
                        .where(GRAPHITRON_DEFECT_TYPE.CODE.eq(GRAPHITRON_ENTRY_DEFECT.CODE)))
                    .fetch(GRAPHITRON_ENTRY_DEFECT.CODE))
                .isEmpty();
        });
    }

    /**
     * Every row, as position, code and detail, ordered the way an author reads a file. The key is
     * asserted here rather than in a case of its own: a view declares its grain and nothing in the
     * store enforces it, so the cheapest place to hold the claim is the reading every case goes
     * through. Two rows sharing position and code would be the coordinate-grained relation this
     * one exists instead of.
     */
    private static List<String> defects(DSLContext dsl) {
        var rows = read(dsl);
        assertThat(rows).as("the entry and the code are the whole key").doesNotHaveDuplicates();
        return rows;
    }

    /** The codes alone, for a captured fixture whose positions are the corpus's rather than stated. */
    private static List<String> codes(DSLContext dsl) {
        return dsl.selectDistinct(GRAPHITRON_ENTRY_DEFECT.CODE)
            .from(GRAPHITRON_ENTRY_DEFECT)
            .orderBy(GRAPHITRON_ENTRY_DEFECT.CODE)
            .fetch(GRAPHITRON_ENTRY_DEFECT.CODE);
    }

    private static List<String> read(DSLContext dsl) {
        return dsl.select(GRAPHITRON_ENTRY_DEFECT.SOURCE_LINE, GRAPHITRON_ENTRY_DEFECT.SOURCE_COLUMN,
                GRAPHITRON_ENTRY_DEFECT.CODE, GRAPHITRON_ENTRY_DEFECT.DETAIL)
            .from(GRAPHITRON_ENTRY_DEFECT)
            .where(GRAPHITRON_ENTRY_DEFECT.GRAPH_NAME.eq(GRAPH))
            .and(GRAPHITRON_ENTRY_DEFECT.SOURCE_NAME.eq(SOURCE))
            .orderBy(GRAPHITRON_ENTRY_DEFECT.SOURCE_LINE, GRAPHITRON_ENTRY_DEFECT.SOURCE_COLUMN,
                GRAPHITRON_ENTRY_DEFECT.CODE)
            .fetch(r -> r.value1() + ":" + r.value2() + " " + r.value3() + " " + r.value4());
    }
}
