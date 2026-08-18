package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_CHAIN_TERMINUS;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_HOP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anchor for {@code intent_field_chain_terminus} and for the name-matched arm of
 * {@code intent_field_reference_step_hop} it stands on: where a {@code @routine} chain lands, and
 * whether the landing is a table-valued function's result.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * the reference-path anchor states: the subject is a resolution against a real catalog, and a
 * hand-seeded chain is free to describe a hop the catalog cannot make. The fixtures use
 * {@code films_for_actor}, whose result exposes {@code film_id}, so the name-match to {@code film}
 * succeeds and the one to {@code actor} does not.
 *
 * <p>The name-matched hop is asserted through the chain wherever the chain can see it, which is the
 * hop view's own doctrine. The one case reading the hop view directly is the one about the columns
 * the chain deliberately drops: a terminus is a place and carries no route.
 */
@PipelineTier
class ChainTerminusTest {

    @TempDir
    Path tmp;

    // ===== Which node ends the chain =====

    /**
     * The single-node chain: no {@code @reference} follows the routine, so the terminus is the
     * function result itself and the kind says so. This is the population the read surface treats
     * differently, a function result having no primary key for an ordering to fall back on.
     */
    @Test
    void aRoutineWithNoHopLandsOnItsOwnResult() {
        withCaptured("""
            type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
              organisasjonskode: Int
              rollekode: String
            }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """, dsl -> {
            var rows = termini(dsl);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.VIA)).isEqualTo("ROUTINE");
            assertThat(table(row)).isEqualTo("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.TABLE_TYPE)).isEqualTo("FUNCTION");
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.POSITION))
                .as("a routine application has no path elements")
                .isNull();
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.CANDIDATES)).isEqualTo(1);
        });
    }

    /**
     * The routine-then-hop chain, which is the whole reason the hop view needed a third arm: no
     * foreign key departs a function result, so the hop out of {@code films_for_actor} is keyed by
     * matching {@code film}'s primary-key column name against the columns the function exposes.
     */
    @Test
    void aHopOutOfTheResultLandsOnTheHoppedToTable() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl -> {
            var rows = termini(dsl);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.VIA)).isEqualTo("REFERENCE");
            assertThat(table(row)).isEqualTo("film");
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.TABLE_TYPE))
                .as("the terminus is a stored table, so an ordering there has a key to fall back on")
                .isEqualTo("TABLE");
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.POSITION)).isZero();
            assertThat(row.get(INTENT_FIELD_CHAIN_TERMINUS.ORDINAL))
                .as("the terminating @reference application's own ordinal")
                .isZero();
        });
    }

    /**
     * A path element after the routine composes past one hop, the second element departing from the
     * first's arrival exactly as it would on an ordinary path. Nothing about the chain's start
     * reaches the second element.
     */
    @Test
    void theWalkComposesPastTheFirstHop() {
        withCaptured(filmHop("[{table: \"film\"}, {table: \"film_translation\"}]"), dsl -> {
            var rows = termini(dsl);
            assertThat(rows).hasSize(1);
            assertThat(table(rows.getFirst())).isEqualTo("film_translation");
            assertThat(rows.getFirst().get(INTENT_FIELD_CHAIN_TERMINUS.POSITION)).isEqualTo(1);
        });
    }

    /**
     * A terminus is a place and not a join, so an element reaching one table by two foreign keys is
     * one row here where the hop and target views have two. {@code film} declares two keys to
     * {@code language}; the arity a reader of a terminus needs is unaffected by that.
     */
    @Test
    void twoRoutesToOneTableAreOneLanding() {
        withCaptured(filmHop("[{table: \"film\"}, {table: \"language\"}]"), dsl -> {
            assertThat(hops(dsl, "TABLE").map(
                r -> r.get(INTENT_FIELD_REFERENCE_STEP_HOP.CONSTRAINT_NAME)))
                .as("the routes the hop view does carry, so the collapse below is not vacuous")
                .contains("film_language_id_fkey", "film_original_language_id_fkey");
            var rows = termini(dsl);
            assertThat(rows).hasSize(1);
            assertThat(table(rows.getFirst())).isEqualTo("language");
            assertThat(rows.getFirst().get(INTENT_FIELD_CHAIN_TERMINUS.CANDIDATES)).isEqualTo(1);
        });
    }

    // ===== Where the walk stops =====

    /**
     * The name-match is a real precondition and not a formality: {@code actor}'s primary key column
     * is not exposed on the {@code films_for_actor} result, so no hop departs there and the field
     * gets no terminus at all. Absence means "not reached", never a terminus at the routine node the
     * walk did reach, which is the answer the generator refuses to produce.
     */
    @Test
    void anUnmatchableHopEndsTheWalkWithNoTerminus() {
        withCaptured(filmHop("[{table: \"actor\"}]"), dsl -> {
            assertThat(hops(dsl, "NAME_MATCH"))
                .as("the element resolved; no departure exposes actor_id")
                .isEmpty();
            assertThat(termini(dsl)).isEmpty();
        });
    }

    /**
     * {@code @routine} names a callable, so a name that resolves to a stored table seeds nothing.
     * The silence is one step earlier than the unmatchable hop's and means the same thing.
     */
    @Test
    void aRoutineNamingAStoredTableSeedsNothing() {
        withCaptured("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! @routine(name: "film") }
            """, dsl -> assertThat(termini(dsl)).isEmpty());
    }

    /** A field with no {@code @routine} is not in this relation's population at all. */
    @Test
    void aReferenceOnlyFieldHasNoChainTerminus() {
        withCaptured("""
            type Film @table(name: "film") {
              lang: Language @reference(path: [{table: "language"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """, dsl -> assertThat(termini(dsl)).isEmpty());
    }

    // ===== Written order, not ordinal order =====

    /**
     * An application written before the routine moves where the chain starts and never where it
     * ends. The two relations number their ordinals separately, so the tail is the applications
     * whose source position follows the routine's, and reading ordinals instead would make this
     * field's terminus the pre-routine hop's arrival.
     */
    @Test
    void anApplicationBeforeTheRoutineIsNotTheTerminus() {
        withCaptured("""
            type Actor @table(name: "actor") {
              rows: [ActorFilmRow!]!
                @reference(path: [{key: "film_actor_actor_id_fkey"}])
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
            }
            type ActorFilmRow @table(name: "films_for_actor") { title: String }
            type Query { actors: [Actor] }
            """, dsl -> {
            var rows = termini(dsl);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_FIELD_CHAIN_TERMINUS.VIA)).isEqualTo("ROUTINE");
            assertThat(table(rows.getFirst())).isEqualTo("films_for_actor");
        });
    }

    // ===== The name-matched hop's own columns =====

    /**
     * What the name-matched arm carries, read at the hop view because the chain drops it. No foreign
     * key is involved, so the two foreign-key columns are null rather than borrowed; the constraint
     * such a hop does key by is the arrival's primary key, which the arriving triple already reaches
     * through {@code sql_primary_key} and which carrying here would only duplicate.
     */
    @Test
    void aNameMatchedHopNamesNoForeignKey() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl -> {
            var rows = hops(dsl, "NAME_MATCH");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_HOP.FROM_TABLE))
                .stream().map(ChainTerminusTest::lower).toList())
                .as("every function result in the graph's sources exposing film_id is a candidate")
                .containsExactly("films_for_actor");
            var row = rows.getFirst();
            assertThat(lower(row.get(INTENT_FIELD_REFERENCE_STEP_HOP.TO_TABLE))).isEqualTo("film");
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_HOP.CONSTRAINT_NAME)).isNull();
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_HOP.FK_ON_FROM)).isNull();
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_HOP.KEY_MATCHED_BY)).isNull();
        });
    }

    /**
     * The same element still resolves its foreign-key arm, which is what lets one written
     * {@code {table:}} element serve both parent shapes. The two arms cannot produce one row: a
     * function result declares no foreign key for the other arm to discover.
     */
    @Test
    void theForeignKeyArmOfTheSameElementIsUntouched() {
        withCaptured(filmHop("[{table: \"film\"}]"), dsl ->
            assertThat(hops(dsl, "TABLE"))
                .as("film's own foreign keys, discovered as before")
                .isNotEmpty());
    }

    // ===== Helpers =====

    /**
     * The root routine-then-hops fixture the hop cases vary: {@code films_for_actor} in the FROM and
     * one {@code @reference} application carrying {@code path}.
     */
    private static String filmHop(String path) {
        return """
            type Film @table(name: "film") { title: String }
            type FilmTranslation @table(name: "film_translation") { title: String }
            type Language @table(name: "language") { name: String }
            type Actor @table(name: "actor") { firstName: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor",
                         argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: %s)
            }
            """.formatted(path);
    }

    private static Result<Record> termini(DSLContext dsl) {
        return dsl.select(INTENT_FIELD_CHAIN_TERMINUS.fields())
            .from(INTENT_FIELD_CHAIN_TERMINUS)
            .where(INTENT_FIELD_CHAIN_TERMINUS.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(INTENT_FIELD_CHAIN_TERMINUS.TYPE_NAME, INTENT_FIELD_CHAIN_TERMINUS.FIELD_NAME,
                INTENT_FIELD_CHAIN_TERMINUS.TABLE_SCHEMA, INTENT_FIELD_CHAIN_TERMINUS.TABLE_NAME)
            .fetch();
    }

    private static Result<Record> hops(DSLContext dsl, String via) {
        return dsl.select(INTENT_FIELD_REFERENCE_STEP_HOP.fields())
            .from(INTENT_FIELD_REFERENCE_STEP_HOP)
            .where(INTENT_FIELD_REFERENCE_STEP_HOP.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(INTENT_FIELD_REFERENCE_STEP_HOP.VIA.eq(via))
            .orderBy(INTENT_FIELD_REFERENCE_STEP_HOP.POSITION,
                INTENT_FIELD_REFERENCE_STEP_HOP.FROM_TABLE,
                INTENT_FIELD_REFERENCE_STEP_HOP.TO_TABLE)
            .fetch();
    }

    /** The landing's SQL name, lowercased: the catalog's case is not what any case here is about. */
    private static String table(Record row) {
        return lower(row.get(INTENT_FIELD_CHAIN_TERMINUS.TABLE_NAME));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void withCaptured(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
    }
}
