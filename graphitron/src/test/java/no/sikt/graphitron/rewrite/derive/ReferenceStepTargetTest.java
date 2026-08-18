package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_SPELLED_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for the three resolution views a {@code @reference} path stands
 * on: {@code intent_spelled_table}, which resolves a written table name against the catalog census
 * whatever site wrote it, and the pair {@code intent_field_reference_step_hop} /
 * {@code intent_field_reference_step_target}, which split a path into its per-element resolutions
 * and the chain that walks them.
 *
 * <p>The hop view has no test of its own here on purpose. Every row it produces that matters is a
 * row the chain either reached or refused, so pinning it separately would pin the same joins twice
 * and let the two pins disagree about which one is the claim. Where a case is about the hop's local
 * resolution (which namespace a key name answered in, which foreign key a table element found) the
 * assertion still reads the chain, because the hop's answer is only a fact about the schema once
 * something arrives at its departing table.
 *
 * <p>Most cases capture real SDL against the test catalog rather than seeding rows, because the
 * resolutions here are exactly the ones a hand-built fixture gets wrong: a fixture is free to seed
 * a chain the catalog cannot connect, and the case then documents behaviour no build can produce.
 * The seeded cases are the ones the test catalog has no instance of (a constraint name colliding
 * across two schemas) or that capture cannot express (an element carrying neither key nor table).
 */
@PipelineTier
class ReferenceStepTargetTest {

    @TempDir
    Path tmp;

    // ===== The chain =====

    /**
     * The two-element path every hop in it oriented independently: {@code film} declares neither
     * key, {@code film_actor} declares both, so the first element travels against its foreign key
     * and the second along it. The terminal element's arrival is what a {@code @field(name:)} on the
     * same field would resolve a column against.
     */
    @Test
    void aKeyChainWalksEachElementFromTheTypesBinding() {
        var sdl = """
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {key: "film_actor_film_id_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Actor @table(name: "actor") { actor_id: ID }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION)))
                .containsExactly(0, 1);
            assertThat(rows.map(ReferenceStepTargetTest::hop))
                .containsExactly("film->film_actor", "film_actor->actor");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.FK_ON_FROM)))
                .as("film declares neither key; film_actor declares both")
                .containsExactly(false, true);
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.VIA)))
                .containsExactly("KEY", "KEY");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CANDIDATES)))
                .containsExactly(1, 1);
        });
    }

    /** A table element names its destination and leaves the foreign key to be discovered. */
    @Test
    void aTableElementDiscoversItsForeignKey() {
        var sdl = """
            type Film @table(name: "film") {
                titleTexts: TranslatedTexts @reference(path: [{table: "film_translation"}])
            }
            type TranslatedTexts @table(name: "film_translation") { title: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(hop(row)).isEqualTo("film->film_translation");
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.VIA)).isEqualTo("TABLE");
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME))
                .isEqualToIgnoringCase("film_translation_film_id_fkey");
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.KEY_MATCHED_BY))
                .as("a table element names no constraint, so no namespace answered")
                .isNull();
        });
    }

    /**
     * The two arities apart, on the case that separates them: {@code film} declares two foreign
     * keys to {@code language}, so one destination is reached by two routes. A reader that needs
     * only the table can trust the answer; a reader that has to render the join cannot, and the
     * walk's own "which foreign key did you mean" rejection is what {@code candidates} counts.
     */
    @Test
    void twoForeignKeysToOneTableAreOneDestinationByTwoRoutes() {
        var sdl = """
            type Film @table(name: "film") {
                lang: Language @reference(path: [{table: "language"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(2);
            assertThat(rows.map(ReferenceStepTargetTest::hop))
                .containsOnly("film->language");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME)))
                .containsExactlyInAnyOrder("film_language_id_fkey", "film_original_language_id_fkey");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS)))
                .containsExactly(1, 1);
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CANDIDATES)))
                .containsExactly(2, 2);
        });
    }

    /**
     * A self-referential key is one hop and not two. Both orientations of such a key land on the
     * same table, so the orientation the walk picks from the field's cardinality chooses join
     * columns rather than a destination, and reporting two rows would make an unambiguous
     * destination look ambiguous to every reader gating on the arity.
     */
    @Test
    void aSelfReferentialKeyIsOneHopNotTwo() {
        var sdl = """
            type Category @table(name: "category") {
                parent: Category @reference(path: [{key: "category_parent_category_id_fkey"}])
            }
            type Query { categories: [Category] }
            """;
        withCapturedStore(sdl, dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(hop(row)).isEqualTo("category->category");
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS)).isEqualTo(1);
            assertThat(row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CANDIDATES)).isEqualTo(1);
        });
    }

    /**
     * The chain stops where an element does not resolve, so a path whose second element is perfectly
     * good contributes nothing when its first names an unknown key. Absence in this view means "not
     * reached" and never "resolves to nothing in particular", which is why the later element must
     * not appear starting from nowhere.
     */
    @Test
    void anUnresolvableFirstElementEndsTheChain() {
        var sdl = """
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {key: "no_such_fkey"},
                    {key: "film_actor_actor_id_fkey"}
                ])
            }
            type Actor @table(name: "actor") { actor_id: ID }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            assertThat(dsl.fetchCount(GRAPHITRON_FIELD_REFERENCE_STEP,
                GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME.eq(GRAPH)))
                .as("both elements were captured; only their resolution declines")
                .isEqualTo(2);
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    /** A type with no {@code @table} has no binding, so its path has nowhere to start. */
    @Test
    void aPathOnAnUnboundTypeStartsNowhere() {
        var sdl = """
            type Film {
                actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey"}])
            }
            type Actor @table(name: "actor") { actor_id: ID }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(chain(dsl, GRAPH)).isEmpty());
    }

    // ===== The key name's two namespaces =====

    /** The SQL constraint name, which is the namespace the resolver tries first. */
    @Test
    void aSqlConstraintNameAnswersInItsOwnNamespace() {
        withCapturedStore(keyPath("film_language_id_fkey"), dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.KEY_MATCHED_BY))
                .isEqualTo("SQL_NAME");
            assertThat(hop(rows.getFirst())).isEqualTo("film->language");
        });
    }

    /**
     * The generated {@code Keys} constant, eligible only where no SQL constraint name in the graph's
     * sources answers the spelling. Which namespace answered is the resolution's own reading of the
     * spelling, so it is a column rather than something a reader re-decides.
     */
    @Test
    void theGeneratedConstantAnswersWhereNoSqlNameDoes() {
        withCapturedStore(keyPath("FILM__FILM_LANGUAGE_ID_FKEY"), dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.KEY_MATCHED_BY))
                .isEqualTo("JOOQ_NAME");
            assertThat(hop(rows.getFirst())).isEqualTo("film->language");
        });
    }

    /** Both namespaces match case-insensitively, as the resolver matches them. */
    @Test
    void aKeyNameMatchesWithoutRegardToCase() {
        withCapturedStore(keyPath("FILM_LANGUAGE_ID_FKEY"), dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst())).isEqualTo("film->language");
        });
    }

    // ===== Seeded: what the test catalog has no instance of =====

    /**
     * A constraint name two schemas both declare reaches two different tables, so the arities move
     * together here where the two-routes case moved them apart. Kept as rows for the same reason the
     * table binding keeps its own ambiguity as rows: one reader refuses an ambiguous hop and another
     * offers both, and neither should have to re-derive the arity to tell which case it is in.
     */
    @Test
    void aKeyNameCollidingAcrossSchemasReachesEachSchemasTable() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, "dup_fk", null);
            var rows = chain(dsl, "g");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_SCHEMA)))
                .containsExactly("legacy", "public");
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS)))
                .containsExactly(2, 2);
            assertThat(rows.map(r -> r.get(INTENT_FIELD_REFERENCE_STEP_TARGET.CANDIDATES)))
                .containsExactly(2, 2);
        });
    }

    /**
     * An author's schema qualifier binds hard: it scopes to the declaring table's schema rather than
     * widening the candidate set, which is the resolver's own precedence.
     */
    @Test
    void anAuthorQualifierScopesTheCollidingName() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, "public.dup_fk", null);
            var rows = chain(dsl, "g");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_SCHEMA))
                .isEqualTo("public");
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_TARGET.KEY_MATCHED_BY))
                .as("a qualified spelling is a SQL constraint name; no constant carries a qualifier")
                .isEqualTo("SQL_NAME");
        });
    }

    /**
     * An element carrying neither key nor table is not a hop this view knows. Its destination comes
     * from a condition method's Java return type, a resolution this view does not perform, and the
     * silence must not read as "resolves to nothing".
     */
    @Test
    void anElementNamingNeitherKeyNorTableIsNotAHop() {
        withCollidingKeySeed(dsl -> {
            seedFieldReference(dsl, "g", "Root", "hop", 0);
            seedFieldReferenceCall(dsl, "g", "Root", "hop", 0, 0,
                "com.example.Conditions", "byOwner");
            assertThat(chain(dsl, "g")).isEmpty();
        });
    }

    /** The graph partition, on relations whose catalog side is scoped through membership. */
    @Test
    void aSiblingGraphReadsNoneOfTheChain() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, "public.dup_fk", null);
            assertThat(chain(dsl, "g")).hasSize(1);
            assertThat(chain(dsl, "other")).isEmpty();
        });
    }

    // ===== The spelling underneath =====

    /**
     * The spelling view's population is every table name this graph authors, not the ones one site
     * happens to write: a name only a path element spells resolves there too, which is what lets the
     * table arm above join it instead of repeating the qualifier split.
     */
    @Test
    void aSpellingOnlyAPathElementWroteStillResolves() {
        var sdl = """
            type Film @table(name: "film") {
                titleTexts: TranslatedTexts @reference(path: [{table: "film_translation"}])
            }
            type TranslatedTexts { title: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> {
            var resolved = dsl.select(INTENT_SPELLED_TABLE.TABLE_NAME)
                .from(INTENT_SPELLED_TABLE)
                .where(INTENT_SPELLED_TABLE.GRAPH_NAME.eq(GRAPH))
                .and(INTENT_SPELLED_TABLE.SPELLING.eq("film_translation"))
                .fetch(0, String.class);
            assertThat(resolved).hasSize(1);
            assertThat(resolved.getFirst()).isEqualToIgnoringCase("film_translation");
            assertThat(dsl.fetchCount(GRAPHITRON_TABLE, GRAPHITRON_TABLE.GRAPH_NAME.eq(GRAPH)
                .and(GRAPHITRON_TABLE.TABLE_REF.eq("film_translation"))))
                .as("no @table wrote this spelling; only the path element did")
                .isZero();
        });
    }

    /** A qualified spelling binds both halves, so one of two same-named tables answers. */
    @Test
    void aQualifiedSpellingBindsItsSchema() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, null, "legacy.owner");
            var rows = spelled(dsl, "legacy.owner");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().value1()).isEqualTo("legacy");
            assertThat(rows.getFirst().value2()).isEqualTo(1);
        });
    }

    /** The unqualified form of the same name reaches both schemas, and says so. */
    @Test
    void anUnqualifiedSpellingReachesEverySchemaDeclaringIt() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, null, "owner");
            var rows = spelled(dsl, "owner");
            assertThat(rows.map(org.jooq.Record2::value1)).containsExactly("legacy", "public");
            assertThat(rows.map(org.jooq.Record2::value2)).containsExactly(2, 2);
        });
    }

    /**
     * A spelling no site in the graph wrote resolves to nothing, the population being the spellings
     * authored rather than every name the census holds. The seeded catalog declares {@code owner} in
     * both schemas and no element spells it here.
     */
    @Test
    void anUnwrittenSpellingIsNotInThePopulation() {
        withCollidingKeySeed(dsl -> assertThat(spelled(dsl, "owner")).isEmpty());
    }

    // ===== Helpers =====

    private static final String GRAPH = "ReferenceStepTargetTest";

    /** A one-element key path off {@code film}, the fixture the namespace cases vary. */
    private static String keyPath(String keySpelling) {
        return """
            type Film @table(name: "film") {
                lang: Language @reference(path: [{key: "%s"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """.formatted(keySpelling);
    }

    private static org.jooq.Result<org.jooq.Record> chain(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_FIELD_REFERENCE_STEP_TARGET.fields())
            .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
            .where(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_FIELD_REFERENCE_STEP_TARGET.FIELD_NAME,
                INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION,
                INTENT_FIELD_REFERENCE_STEP_TARGET.TO_SCHEMA,
                INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME)
            .fetch();
    }

    /** What one spelling resolves to, schema and arity, in schema order. */
    private static org.jooq.Result<org.jooq.Record2<String, Integer>> spelled(
        DSLContext dsl, String spelling
    ) {
        return dsl.select(INTENT_SPELLED_TABLE.TABLE_SCHEMA, INTENT_SPELLED_TABLE.CANDIDATES)
            .from(INTENT_SPELLED_TABLE)
            .where(INTENT_SPELLED_TABLE.GRAPH_NAME.eq("g"))
            .and(INTENT_SPELLED_TABLE.SPELLING.eq(spelling))
            .orderBy(INTENT_SPELLED_TABLE.TABLE_SCHEMA)
            .fetch();
    }

    /** One row's hop, lowercased, as {@code from->to}: what every chain case reads first. */
    private static String hop(org.jooq.Record row) {
        return (row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.FROM_TABLE) + "->"
            + row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)).toLowerCase(Locale.ROOT);
    }

    private void withCapturedStore(String sdl, java.util.function.Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var store = GraphitronModelStore.open()) {
            var schemaFile = write(tmp, sdl);
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile)));
            FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(GRAPH, tmp),
                FactCapture.SubjectConfig.none(), registry, TestSchemaHelper.attribution(schemaFile),
                jooq, List.of(), new NodeDeclaration(null));
            body.accept(store.dsl());
        }
    }

    /**
     * Two schemas of one source, each declaring a table {@code owner} and a constraint
     * {@code dup_fk} against it, plus a {@code Root} type bound to the public one. The collision the
     * test catalog has no instance of, seeded at the smallest shape that produces it.
     */
    private static void withCollidingKeySeed(java.util.function.Consumer<DSLContext> body) {
        withSeededStore("g", dsl -> {
            seedSource(dsl, "pkg", "JOOQ_SCHEMA");
            seedGraphSource(dsl, "g", "pkg");
            for (String schema : List.of("public", "legacy")) {
                seedTable(dsl, "pkg", schema, "owner");
                seedTable(dsl, "pkg", schema, "note");
                // note.dup_fk -> owner.owner_pk, the same constraint name in both schemas.
                seedConstraint(dsl, "pkg", schema, "owner", "owner_pk", "PRIMARY KEY", null);
                seedConstraint(dsl, "pkg", schema, "note", "dup_fk", "FOREIGN KEY",
                    "NOTE__DUP_FK_" + schema.toUpperCase(Locale.ROOT));
                seedReferentialConstraint(dsl, "pkg", schema, "note", "dup_fk",
                    "pkg", schema, "owner", "owner_pk");
            }
            seedRootType(dsl);
            body.accept(dsl);
        });
    }

    /** {@code type Root @table(name: "note")} with one field the seeded path hangs off. */
    private static void seedRootType(DSLContext dsl) {
        seedField(dsl, "g", "Root", "hop");
        // "note" is unqualified and declared in both schemas, so the departure is deliberately
        // ambiguous: it is what lets a colliding key name be reached in either schema, and the
        // qualified cases scope the key rather than the departure.
        seedTableBinding(dsl, "g", "Root", "note");
    }

    /** One path element on the seeded {@code Root.hop}, spelling a key or a table. */
    private static void seedStep(DSLContext dsl, String keyRef, String tableRef) {
        seedFieldReference(dsl, "g", "Root", "hop", 0);
        seedFieldReferenceStep(dsl, "g", "Root", "hop", 0, 0, tableRef, keyRef);
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
