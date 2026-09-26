package no.sikt.graphitron.model;

import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_RULE;
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
 * <p>Nothing here restates the order of a field's chain or walks it again. Where a chain departs
 * from, and whether consecutive links meet, is resolved by the assembly gatherer; what a chain arm
 * here reads is the resolution's own leftovers, which the rule that stores one row per link throws
 * away. {@code graphitron_field_chain_link_resolution} carries both walks labelled by which reached
 * a reading, so a chain arm is a count over rows that already exist rather than a second walk.
 *
 * <p>A chain arm needs a real catalog, an authored key being ambiguous only against tables that
 * declare more than one way between them, so those cases capture against the jOOQ catalog where
 * the write rules above capture SDL alone.
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
     * The vocabulary is a relation and the code is a reference into it, so a code the rule emits
     * that nothing declares is a fact the store can state rather than a string nobody checks.
     *
     * <p>Asserted against the rule rather than the target, where the target's own foreign key makes
     * the same row unwritable. The two are not the same claim: the constraint stops a bad code
     * reaching the store and this stops one being computed, and the stage between them would fail
     * the capture rather than the case that could name the offending code.
     */
    @Test
    @DisplayName("every code the rule emits is a declared defect type")
    void everyCodeIsDeclared() {
        withSeededStore(GRAPH, dsl -> {
            seedRootOperation(dsl, GRAPH, "MUTATION", "Mutation");
            seedField(dsl, GRAPH, "Mutation", "insertFilm");
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 0, "first_fn", 1);
            seedRoutine(dsl, GRAPH, "Mutation", "insertFilm", 1, "second_fn", 5);
            seedFieldCondition(dsl, GRAPH, "Mutation", "insertFilm", null, null, null, 3);

            assertThat(defects(dsl)).as("the fixture reaches two codes, so the anti-join is not vacuous")
                .hasSize(2);
            assertThat(dsl.selectDistinct(GRAPHITRON_ENTRY_DEFECT_RULE.CODE)
                    .from(GRAPHITRON_ENTRY_DEFECT_RULE)
                    .whereNotExists(dsl.selectOne().from(GRAPHITRON_DEFECT_TYPE)
                        .where(GRAPHITRON_DEFECT_TYPE.CODE.eq(GRAPHITRON_ENTRY_DEFECT_RULE.CODE)))
                    .fetch(GRAPHITRON_ENTRY_DEFECT_RULE.CODE))
                .isEmpty();
        });
    }

    /**
     * A path element naming a table two foreign keys reach. {@code film} declares both
     * {@code film_language_id_fkey} and {@code film_original_language_id_fkey} against
     * {@code language}, so the element resolves to one destination by two routes and the generator
     * has a join to emit and nothing to pick it by.
     *
     * <p>The chain states this as a hole rather than as a choice: both readings lie on a chain that
     * runs the whole way, so the resolution finds two and stores neither. That silence is what this
     * arm turns into a row, at the element the author would edit.
     */
    @Test
    @DisplayName("an element reaching its table by two foreign keys is an ambiguous route")
    void anElementTwoKeysReachIsAmbiguous(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              language: Language @reference(path: [{table: "language"}])
            }
            type Language @table(name: "language") { name: String }
            """, dsl -> assertThat(codes(dsl)).containsExactly("ROUTE_AMBIGUOUS"));
    }

    /**
     * The same path with the key named rather than the table, which is the edit the defect asks
     * for. One route, one link, no row: the case that tells the arm from a fixture reporting
     * whatever it is handed.
     */
    @Test
    @DisplayName("naming the key instead of the table resolves the ambiguity and draws nothing")
    void namingTheKeyDrawsNothing(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") { name: String }
            """, dsl -> assertThat(codes(dsl)).isEmpty());
    }

    /**
     * An element naming a constraint the catalog does not have. The chain stops there, and the
     * element before it resolved on its own terms and is not reported: a chain resolves at every
     * position or at none, so a broken one has a single break and the row sits at it.
     */
    @Test
    @DisplayName("an element naming nothing the catalog has is unresolved, and only it is reported")
    void anElementNamingNothingIsUnresolved(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { actors: [Actor!]! }
            type Actor @table(name: "actor") {
              broken: [Language!]! @reference(path: [{key: "film_actor_actor_id_fkey"},
                                                    {key: "no_such_constraint"}])
            }
            type Language @table(name: "language") { name: String }
            """, dsl -> assertThat(codes(dsl)).containsExactly("ELEMENT_UNRESOLVED"));
    }

    /**
     * An element that resolves and cannot be reached. {@code film_actor_actor_id_fkey} joins
     * film_actor to actor, both directions of it are real routes, and neither departs the film the
     * chain is standing on. Distinct from an unresolved element, which is what the break vocabulary
     * exists to tell apart: what the author changes is a different thing.
     */
    @Test
    @DisplayName("an element whose routes all depart elsewhere is unreachable, not unresolved")
    void anElementDepartingElsewhereIsUnreachable(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              actors: [Actor!]! @reference(path: [{key: "film_actor_actor_id_fkey"}])
            }
            type Actor @table(name: "actor") { name: String }
            """, dsl -> assertThat(codes(dsl)).containsExactly("NO_ROUTE_FROM_DEPARTURE"));
    }

    /**
     * A chain that runs the whole way and lands somewhere else. The key departs film as the chain
     * requires and arrives at film_actor, where the field's rows were said to come from language.
     * The break is the last link rather than one past it: nothing downstream failed, the chain
     * simply ended in the wrong place.
     */
    @Test
    @DisplayName("a chain arriving somewhere other than its target breaks at its last link")
    void aChainLandingElsewhereBreaksAtItsLastLink(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              wrong: [Language!]! @reference(path: [{key: "film_actor_film_id_fkey"}])
            }
            type Language @table(name: "language") { name: String }
            """, dsl -> assertThat(codes(dsl)).containsExactly("NO_ROUTE_TO_TARGET"));
    }

    /**
     * A field whose rows come from nowhere: the return type binds no table and no @routine names a
     * result, so there is no chain to break. Reported once at the first element rather than per
     * element, the whole chain being the one thing wrong.
     */
    @Test
    @DisplayName("a chain on a field with no table target is reported once, at its first element")
    void aChainWithNoTargetIsReportedOnce(@TempDir Path tmp) {
        withCatalogStore(tmp, """
            type Query { films: [Film!]! }
            type Film @table(name: "film") {
              loose: [Row!]! @reference(path: [{table: "film_actor"}, {table: "actor"}])
            }
            type Row { title: String }
            """, dsl -> assertThat(codes(dsl)).containsExactly("CHAIN_WITHOUT_TARGET"));
    }

    /**
     * A capture against the jOOQ catalog, which the chain arms need and the write arms do not: a
     * route is ambiguous only against tables that declare more than one way between them.
     */
    private static void withCatalogStore(Path directory, String sdl, Consumer<DSLContext> body) {
        var ctx = TestRunContext.of();
        try (var store = CapturedStore.ownStoreOfCatalog(directory, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
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

    /**
     * The rule and not the target, because a seeded store has run no capture and so no stage: the
     * table is legitimately empty there, and what a seeded case is asserting is the rule anyway.
     * The captured cases read the target through {@link #codes}, which is the other half of the
     * claim; that the two agree is {@code StageAnswerAgreementTest}'s, over every stage at once.
     */
    private static List<String> read(DSLContext dsl) {
        var d = GRAPHITRON_ENTRY_DEFECT_RULE;
        return dsl.select(d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE, d.DETAIL)
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH))
            .and(d.SOURCE_NAME.eq(SOURCE))
            .orderBy(d.SOURCE_LINE, d.SOURCE_COLUMN, d.CODE)
            .fetch(r -> r.value1() + ":" + r.value2() + " " + r.value3() + " " + r.value4());
    }
}
