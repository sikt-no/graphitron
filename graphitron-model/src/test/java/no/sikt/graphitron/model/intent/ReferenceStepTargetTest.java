package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Record2;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_SPELLED_TABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
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
 * What the three resolution views a {@code @reference} path stands on return:
 * {@code intent_spelled_table}, which resolves a written table name against the catalog census
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
 * <p>Both catalogs here are stated as rows, and the point of a case is usually a shape the catalog
 * has to hold rather than one an author wrote: two foreign keys between the same pair of tables, a
 * key that points at its own table, a constraint name two schemas both declare, a table name two
 * schemas both declare. Stating the catalog is what puts those side by side in a fixture small
 * enough to read, and a path element that resolves against nothing is a case rather than an
 * accident. That a real crawler produces catalog rows of this shape is pinned beside the crawler.
 */
class ReferenceStepTargetTest {

    // ===== The chain =====

    /**
     * The two-element path every hop in it oriented independently: {@code film} declares neither
     * key, {@code film_actor} declares both, so the first element travels against its foreign key
     * and the second along it. The terminal element's arrival is what a {@code @field(name:)} on the
     * same field would resolve a column against.
     */
    @Test
    void aKeyChainWalksEachElementFromTheTypesBinding() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "actors", "film_actor_film_id_fkey", "film_actor_actor_id_fkey");

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
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTablePath(dsl, "Film", "titleTexts", "film_translation");

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
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTablePath(dsl, "Film", "lang", "language");

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
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Category", "category");
            seedKeyPath(dsl, "Category", "parent", "category_parent_category_id_fkey");

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
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "actors", "no_such_fkey", "film_actor_actor_id_fkey");

            assertThat(dsl.fetchCount(GRAPHITRON_FIELD_REFERENCE_STEP,
                GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME.eq(GRAPH)))
                .as("both elements were authored; only their resolution declines")
                .isEqualTo(2);
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    /** A type with no {@code @table} has no binding, so its path has nowhere to start. */
    @Test
    void aPathOnAnUnboundTypeStartsNowhere() {
        withCatalog(dsl -> {
            seedKeyPath(dsl, "Film", "actors", "film_actor_film_id_fkey");
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    // ===== The key name's two namespaces =====

    /** The SQL constraint name, which is the namespace the resolver tries first. */
    @Test
    void aSqlConstraintNameAnswersInItsOwnNamespace() {
        withKeyPath("film_language_id_fkey", dsl -> {
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
        withKeyPath("FILM__FILM_LANGUAGE_ID_FKEY", dsl -> {
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
        withKeyPath("FILM_LANGUAGE_ID_FKEY", dsl -> {
            var rows = chain(dsl, GRAPH);
            assertThat(rows).hasSize(1);
            assertThat(hop(rows.getFirst())).isEqualTo("film->language");
        });
    }

    // ===== A name two schemas both declare =====

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
            var rows = chain(dsl, GRAPH);
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
            var rows = chain(dsl, GRAPH);
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
            seedFieldReference(dsl, GRAPH, "Root", "hop", 0);
            seedFieldReferenceCall(dsl, GRAPH, "Root", "hop", 0, 0,
                "com.example.Conditions", "byOwner");
            assertThat(chain(dsl, GRAPH)).isEmpty();
        });
    }

    /** The graph partition, on relations whose catalog side is scoped through membership. */
    @Test
    void aSiblingGraphReadsNoneOfTheChain() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, "public.dup_fk", null);
            assertThat(chain(dsl, GRAPH)).hasSize(1);
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
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedTablePath(dsl, "Film", "titleTexts", "film_translation");

            derive(dsl);
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
            assertThat(rows.map(Record2::value1)).containsExactly("legacy", "public");
            assertThat(rows.map(Record2::value2)).containsExactly(2, 2);
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

    /**
     * A spelling whose qualifier grammar wrote a period with one side empty resolves to nothing.
     * The empty half is stored as the empty string rather than repaired, so the failure is the join
     * finding no row; a rule treating a blank half as unqualified would have resolved the first of
     * these as though the author had written {@code owner}, in a schema they did not name.
     */
    @Test
    void aHalfEmptySpellingResolvesToNothing() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, null, "owner.");
            assertThat(spelled(dsl, "owner.")).isEmpty();
        });
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, null, ".owner");
            assertThat(spelled(dsl, ".owner")).isEmpty();
        });
    }

    /**
     * Everything after the first period is the name half, so a two-period spelling names no table
     * even though its first two segments would have. The partition stays a total function and the
     * resolution declines, rather than the split deciding what a third segment might mean.
     */
    @Test
    void aMultiPartSpellingResolvesToNothing() {
        withCollidingKeySeed(dsl -> {
            seedStep(dsl, null, "public.owner.x");
            assertThat(spelled(dsl, "public.owner.x")).isEmpty();
        });
    }

    /**
     * The type-name fallback is a spelling like any other by the time resolution sees it, and it
     * meets the catalog case-insensitively: {@code Owner} binds {@code owner}. Worth its own case
     * because this is the one comparison where a GraphQL identifier stands in as a SQL one, so it is
     * where a reader doubts whether the fold applies to the authored side as well as the catalog's.
     */
    @Test
    void aTypeNameFallbackBindsItsTableAcrossCase() {
        withCollidingKeySeed(dsl -> {
            seedField(dsl, GRAPH, "Owner", "id");
            seedTableBinding(dsl, GRAPH, "Owner", null);
            assertThat(spelled(dsl, "Owner").map(Record2::value1))
                .containsExactly("legacy", "public");
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String PUBLIC = "public";

    /**
     * A catalog of six tables holding the three shapes the chain cases turn on: two foreign keys
     * between one pair of tables, a join table declaring keys to both of its ends, and a key
     * pointing back at its own table. One key also carries the constant jOOQ would have generated
     * for it, which is the second namespace a spelling can answer in.
     *
     * <p>No type is bound here. Which type departs from which table is the case's own to state,
     * several cases binding the same table to different shapes.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String table : List.of("film", "actor", "language", "category",
                    "film_actor", "film_translation")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            foreignKey(dsl, "film", "film_language_id_fkey", "language", "FILM__FILM_LANGUAGE_ID_FKEY");
            foreignKey(dsl, "film", "film_original_language_id_fkey", "language", null);
            foreignKey(dsl, "film_actor", "film_actor_film_id_fkey", "film", null);
            foreignKey(dsl, "film_actor", "film_actor_actor_id_fkey", "actor", null);
            foreignKey(dsl, "film_translation", "film_translation_film_id_fkey", "film", null);
            foreignKey(dsl, "category", "category_parent_category_id_fkey", "category", null);
            body.accept(dsl);
        });
    }

    /** One foreign key from {@code table} to {@code referencedTable}'s primary key. */
    private static void foreignKey(DSLContext dsl, String table, String constraintName,
                                   String referencedTable, String jooqName) {
        seedConstraint(dsl, PKG, PUBLIC, table, constraintName, "FOREIGN KEY", jooqName);
        seedReferentialConstraint(dsl, PKG, PUBLIC, table, constraintName,
            PKG, PUBLIC, referencedTable, referencedTable + "_pkey");
    }

    /** {@code Film} bound to {@code film} with a one-element key path, the namespace cases' fixture. */
    private static void withKeyPath(String keySpelling, Consumer<DSLContext> body) {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "lang", keySpelling);
            body.accept(dsl);
        });
    }

    /** A field carrying one {@code @reference} whose elements each spell a key. */
    private static void seedKeyPath(DSLContext dsl, String typeName, String fieldName,
                                    String... keyRefs) {
        seedPath(dsl, typeName, fieldName, null, keyRefs);
    }

    /** The same, with each element spelling a table instead. */
    private static void seedTablePath(DSLContext dsl, String typeName, String fieldName,
                                      String... tableRefs) {
        seedPath(dsl, typeName, fieldName, tableRefs, null);
    }

    private static void seedPath(DSLContext dsl, String typeName, String fieldName,
                                 String[] tableRefs, String[] keyRefs) {
        seedField(dsl, GRAPH, typeName, fieldName);
        seedFieldReference(dsl, GRAPH, typeName, fieldName, 0);
        int elements = tableRefs != null ? tableRefs.length : keyRefs.length;
        for (int position = 0; position < elements; position++) {
            seedFieldReferenceStep(dsl, GRAPH, typeName, fieldName, 0, position,
                tableRefs == null ? null : tableRefs[position],
                keyRefs == null ? null : keyRefs[position]);
        }
    }

    /**
     * Two schemas of one source, each declaring a table {@code owner} and a constraint
     * {@code dup_fk} against it, plus a {@code Root} type bound to the public one. The collision the
     * catalog above has no instance of, seeded at the smallest shape that produces it.
     */
    private static void withCollidingKeySeed(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            for (String schema : List.of(PUBLIC, "legacy")) {
                seedTable(dsl, PKG, schema, "owner");
                seedTable(dsl, PKG, schema, "note");
                // note.dup_fk -> owner.owner_pk, the same constraint name in both schemas.
                seedConstraint(dsl, PKG, schema, "owner", "owner_pk", "PRIMARY KEY", null);
                seedConstraint(dsl, PKG, schema, "note", "dup_fk", "FOREIGN KEY",
                    "NOTE__DUP_FK_" + schema.toUpperCase(Locale.ROOT));
                seedReferentialConstraint(dsl, PKG, schema, "note", "dup_fk",
                    PKG, schema, "owner", "owner_pk");
            }
            seedRootType(dsl);
            body.accept(dsl);
        });
    }

    /** {@code type Root @table(name: "note")} with one field the seeded path hangs off. */
    private static void seedRootType(DSLContext dsl) {
        seedField(dsl, GRAPH, "Root", "hop");
        // "note" is unqualified and declared in both schemas, so the departure is deliberately
        // ambiguous: it is what lets a colliding key name be reached in either schema, and the
        // qualified cases scope the key rather than the departure.
        seedTableBinding(dsl, GRAPH, "Root", "note");
    }

    /** One path element on the seeded {@code Root.hop}, spelling a key or a table. */
    private static void seedStep(DSLContext dsl, String keyRef, String tableRef) {
        seedFieldReference(dsl, GRAPH, "Root", "hop", 0);
        seedFieldReferenceStep(dsl, GRAPH, "Root", "hop", 0, 0, tableRef, keyRef);
    }

    private static Result<Record> chain(DSLContext dsl, String graphName) {
        derive(dsl);
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
    private static Result<Record2<String, Integer>> spelled(DSLContext dsl, String spelling) {
        derive(dsl);
        return dsl.select(INTENT_SPELLED_TABLE.TABLE_SCHEMA, INTENT_SPELLED_TABLE.CANDIDATES)
            .from(INTENT_SPELLED_TABLE)
            .where(INTENT_SPELLED_TABLE.GRAPH_NAME.eq(GRAPH))
            .and(INTENT_SPELLED_TABLE.SPELLING.eq(spelling))
            .orderBy(INTENT_SPELLED_TABLE.TABLE_SCHEMA)
            .fetch();
    }

    /** One row's hop, lowercased, as {@code from->to}: what every chain case reads first. */
    private static String hop(Record row) {
        return (row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.FROM_TABLE) + "->"
            + row.get(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE)).toLowerCase(Locale.ROOT);
    }
}
