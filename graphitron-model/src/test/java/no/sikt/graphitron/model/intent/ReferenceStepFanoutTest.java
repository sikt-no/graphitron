package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_FANOUT;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConditionMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_field_reference_step_fanout} returns: for each table a field-site
 * {@code @reference} path passes <em>through</em>, whether that table can hold more than one row
 * per pair of columns the entering and the leaving hop bind.
 *
 * <p>The subset direction is the whole rule, and one case is not enough to pin it. A fixture that
 * only fires passes under the inverted reading too, which is how {@code bound} inside
 * {@code columns(constraint)} survived a measurement on a corpus whose only junction is
 * {@code film_actor}. So the cases come in pairs: a junction whose key is exactly the bound pair
 * against one carrying a key column neither hop binds, and a one-column foreign key against a
 * two-column unique constraint on the table it arrives at.
 *
 * <p>The two undecidable arms are asserted as named rows rather than as absences, which is the
 * point of their existing: a test pinning a silence cannot tell a declined intermediate from an
 * unimplemented one, and the silence this relation owns is the one
 * {@code intent_field_reference_step_target} owns, that the walk did not resolve the element to a
 * single hop. What a declined row must not do is name a covering constraint, since coverage is
 * computed from the columns the readable hops bind and one hop can cover on its own; that is
 * asserted where the readable hop does cover, not where the fixture happens not to.
 */
class ReferenceStepFanoutTest {

    // ===== The pair-coverage rule =====

    /**
     * A pure join table is quiet. {@code film_actor}'s primary key is exactly the two columns the
     * entering and the leaving hop bind, so each actor arrives once and the hop multiplies nothing.
     * This is the most canonical {@code @reference} shape there is, and the two formulations this
     * rule was not built on both fire on it.
     */
    @Test
    void aPureJoinTableIsCovered() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "actors",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");

            var rows = fanout(dsl);
            assertThat(rows.map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 film_actor COVERED");
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_FANOUT.COVERING_CONSTRAINT_NAME))
                .isEqualTo("film_actor_pkey");
        });
    }

    /**
     * The discriminating half of that pair, and the reporter's own table: a junction carrying its
     * own payload column inside its key. Both hops together bind two of the three key columns, so
     * the third is free and the table may hold several rows per bound pair. The inverted reading
     * clears this hop, the bound pair sitting inside the key; the correct one fires.
     */
    @Test
    void aJunctionCarryingItsOwnKeyColumnFansOut() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "notes", "film_actor_film_id_fkey",
                "film_actor_note_pair_fkey", "film_actor_note_actor_id_fkey");

            assertThat(fanout(dsl).map(ReferenceStepFanoutTest::verdictAt))
                .as("the note table's lang_code is bound by neither hop")
                .containsExactly("0 film_actor COVERED", "1 film_actor_note FANS_OUT");
        });
    }

    /**
     * The subset direction asserted as such, on the minimal counterexample to
     * {@code bound} inside {@code columns(constraint)}: both hops bind {@code person_id} and the
     * only uniqueness on the table is over {@code (person_id, valid_from)}. A constraint pins a row
     * only where the join knows a value for every column of it, so this hop multiplies. The next
     * reader who thinks the subset looks backwards finds the answer here rather than deriving it.
     */
    @Test
    void aOneColumnKeyAgainstATwoColumnUniqueFansOut() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Person", "person");
            seedKeyPath(dsl, "Person", "crews", "stint_person_fkey", "stint_crew_fkey");

            assertThat(fanout(dsl).map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 stint FANS_OUT");
        });
    }

    /**
     * The control beside it, which is what makes the case above a statement about the direction
     * rather than about unique constraints: give the same table a unique constraint over the one
     * column both hops bind and the hop clears, named by that constraint.
     */
    @Test
    void aUniqueConstraintOverTheBoundColumnCovers() {
        withCatalog(dsl -> {
            seedUniqueKey(dsl, PKG, PUBLIC, "stint", "stint_person_key", "person_id");
            seedTableBinding(dsl, GRAPH, "Person", "person");
            seedKeyPath(dsl, "Person", "crews", "stint_person_fkey", "stint_crew_fkey");

            var rows = fanout(dsl);
            assertThat(rows.map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 stint COVERED");
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_FANOUT.COVERING_CONSTRAINT_NAME))
                .isEqualTo("stint_person_key");
        });
    }

    /**
     * A path ending on the many side has no leaving hop, so its last element is no intermediate and
     * draws no row at all. The terminal-hop exemption falls out of the grain rather than being
     * written as a case: this is a plain to-many list and there is nothing to say about it.
     */
    @Test
    void aPathTerminatingOnTheManySideYieldsNoRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "castLinks", "film_actor_film_id_fkey");

            assertThat(fanout(dsl)).isEmpty();
        });
    }

    // ===== The two undecidable arms, as rows =====

    /**
     * An intermediate left by an element that joins on an authored Java predicate: the columns that
     * predicate compares are in nobody's catalog, so one side of the bound set is unreadable and
     * the relation says so rather than guessing. Asserted as a named row, which is the whole reason
     * the arm is a value.
     */
    @Test
    void aConditionHopOutOfAnIntermediateIsDeclinedByName() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedConditionMethod(dsl, JAR, CONDITIONS, "junctionToActor",
                tableClass("film_actor"), tableClass("actor"));
            seedField(dsl, GRAPH, "Film", "bridged");
            seedFieldReference(dsl, GRAPH, "Film", "bridged", 0);
            seedFieldReferenceStep(dsl, GRAPH, "Film", "bridged", 0, 0,
                null, "film_actor_film_id_fkey");
            seedFieldReferenceCall(dsl, GRAPH, "Film", "bridged", 0, 1,
                CONDITIONS, "junctionToActor");

            var rows = fanout(dsl);
            assertThat(rows.map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 film_actor UNDECIDABLE_CONDITION_HOP");
            assertThat(rows.getFirst().get(INTENT_FIELD_REFERENCE_STEP_FANOUT.COVERING_CONSTRAINT_NAME))
                .as("a declined intermediate names no constraint, film_actor_pkey or otherwise")
                .isNull();
        });
    }

    /**
     * An intermediate entered by an element departing a table-valued function's result. A function
     * result declares no constraint, so the hop is keyed by matching column names and there is no
     * foreign key whose columns the entering side could contribute.
     */
    @Test
    void aNameMatchHopIntoAnIntermediateIsDeclinedByName() {
        withCatalog(dsl -> {
            seedTable(dsl, PKG, PUBLIC, "film_search", "FUNCTION");
            seedColumn(dsl, PKG, PUBLIC, "film_search", "film_id", 0, "FILM_ID");
            seedTableBinding(dsl, GRAPH, "Search", "film_search");
            seedPath(dsl, "Search", "languages",
                new String[] {"film", null}, new String[] {null, "film_language_id_fkey"});

            assertThat(fanout(dsl).map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 film UNDECIDABLE_NAME_MATCH_HOP");
        });
    }

    /**
     * The invariant the covering column's contract states, bound by a fixture that can break it
     * rather than by one that happens not to. Coverage reads whatever columns the readable hops
     * bind, so an intermediate whose one readable hop binds a whole primary key has a constraint to
     * name while the other side stays unreadable. Naming it beside a declined verdict would say the
     * hop is cleared, which is the false negative the two undecidable arms exist to avoid, so the
     * name answers under {@code COVERED} and nowhere else. Both arms carry the shape: the entering
     * hop into {@code film} binds {@code film_id}, which is {@code film_pkey} whole.
     */
    @Test
    void aDeclinedIntermediateNamesNothingWhereItsReadableHopCoversOnItsOwn() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "filmToActor",
                tableClass("film"), tableClass("actor"));
            seedTableBinding(dsl, GRAPH, "Cast", "film_actor");
            seedField(dsl, GRAPH, "Cast", "players");
            seedFieldReference(dsl, GRAPH, "Cast", "players", 0);
            seedFieldReferenceStep(dsl, GRAPH, "Cast", "players", 0, 0,
                null, "film_actor_film_id_fkey");
            seedFieldReferenceCall(dsl, GRAPH, "Cast", "players", 0, 1,
                CONDITIONS, "filmToActor");

            seedTable(dsl, PKG, PUBLIC, "film_search", "FUNCTION");
            seedColumn(dsl, PKG, PUBLIC, "film_search", "film_id", 0, "FILM_ID");
            seedTableBinding(dsl, GRAPH, "Search", "film_search");
            seedPath(dsl, "Search", "casts",
                new String[] {"film", null}, new String[] {null, "film_actor_film_id_fkey"});

            var rows = fanout(dsl);
            assertThat(rows.map(ReferenceStepFanoutTest::verdictAt))
                .containsExactly("0 film UNDECIDABLE_CONDITION_HOP",
                    "0 film UNDECIDABLE_NAME_MATCH_HOP");
            assertThat(rows.map(row ->
                row.get(INTENT_FIELD_REFERENCE_STEP_FANOUT.COVERING_CONSTRAINT_NAME)))
                .as("film_pkey is covered by the readable hop of each, and named by neither")
                .containsOnlyNulls();
        });
    }

    // ===== The one silence this relation owns =====

    /**
     * An element the walk did not reach draws no row here either. Absence means that and nothing
     * else: a path whose first element names an unknown key resolves to nothing at all, and a
     * fan-out verdict on top of a path that does not walk would be noise over a failure reported
     * elsewhere.
     */
    @Test
    void anUnreachedElementYieldsNoRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "actors", "no_such_fkey", "film_actor_actor_id_fkey");

            assertThat(fanout(dsl)).isEmpty();
        });
    }

    /** The graph partition, on a relation whose catalog side is scoped through membership. */
    @Test
    void aSiblingGraphReadsNoneOfIt() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedKeyPath(dsl, "Film", "actors",
                "film_actor_film_id_fkey", "film_actor_actor_id_fkey");
            assertThat(fanout(dsl)).hasSize(1);
            assertThat(rowsOf(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";
    private static final String CONDITIONS = "com.example.Conditions";

    /**
     * The catalog every case here draws from, holding the three shapes the rule turns on: a pure
     * join table whose key is its two foreign keys' columns, a junction carrying a third key column
     * neither hop can bind, and a table reached on one column of a two-column unique constraint.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, JAR);

            table(dsl, "film", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            table(dsl, "actor", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "actor", "actor_pkey", "actor_id");
            table(dsl, "language", "language_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "language", "language_pkey", "language_id");
            seedColumn(dsl, PKG, PUBLIC, "film", "language_id", 1, "LANGUAGE_ID");
            seedForeignKey(dsl, PKG, PUBLIC, "film", "film_language_id_fkey",
                "language", "language_pkey", "language_id");

            table(dsl, "film_actor", "film_id", "actor_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_pkey",
                "actor_id", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor", "film_actor_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");

            table(dsl, "film_actor_note", "actor_id", "film_id", "lang_code");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_pkey",
                "actor_id", "film_id", "lang_code");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_pair_fkey",
                "film_actor", "film_actor_pkey", "actor_id", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_actor_note", "film_actor_note_actor_id_fkey",
                "actor", "actor_pkey", "actor_id");

            table(dsl, "person", "person_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "person", "person_pkey", "person_id");
            table(dsl, "crew", "person_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "crew", "crew_pkey", "person_id");
            table(dsl, "stint", "person_id", "valid_from");
            seedUniqueKey(dsl, PKG, PUBLIC, "stint", "stint_person_from_key",
                "person_id", "valid_from");
            seedForeignKey(dsl, PKG, PUBLIC, "stint", "stint_person_fkey",
                "person", "person_pkey", "person_id");
            seedForeignKey(dsl, PKG, PUBLIC, "stint", "stint_crew_fkey",
                "crew", "crew_pkey", "person_id");

            body.accept(dsl);
        });
    }

    /** One catalog table and its columns, in written order. */
    private static void table(DSLContext dsl, String tableName, String... columnNames) {
        seedTable(dsl, PKG, PUBLIC, tableName);
        for (int ordinal = 0; ordinal < columnNames.length; ordinal++) {
            seedColumn(dsl, PKG, PUBLIC, tableName, columnNames[ordinal], ordinal,
                columnNames[ordinal].toUpperCase());
        }
    }

    /** The generated table class the seeded catalog names for a table, its own join key. */
    private static String tableClass(String table) {
        return PKG + ".tables." + table;
    }

    /** A field carrying one {@code @reference} whose elements each spell a key. */
    private static void seedKeyPath(DSLContext dsl, String typeName, String fieldName,
                                    String... keyRefs) {
        seedPath(dsl, typeName, fieldName, null, keyRefs);
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

    private static Result<Record> fanout(DSLContext dsl) {
        derive(dsl);
        return rowsOf(dsl, GRAPH);
    }

    private static Result<Record> rowsOf(DSLContext dsl, String graphName) {
        return dsl.select(INTENT_FIELD_REFERENCE_STEP_FANOUT.fields())
            .from(INTENT_FIELD_REFERENCE_STEP_FANOUT)
            .where(INTENT_FIELD_REFERENCE_STEP_FANOUT.GRAPH_NAME.eq(graphName))
            .orderBy(INTENT_FIELD_REFERENCE_STEP_FANOUT.TYPE_NAME,
                INTENT_FIELD_REFERENCE_STEP_FANOUT.FIELD_NAME,
                INTENT_FIELD_REFERENCE_STEP_FANOUT.ORDINAL,
                INTENT_FIELD_REFERENCE_STEP_FANOUT.POSITION)
            .fetch();
    }

    /** One row as the three things every case here is about: where, which table, what verdict. */
    private static String verdictAt(Record row) {
        return row.get(INTENT_FIELD_REFERENCE_STEP_FANOUT.POSITION) + " "
            + row.get(INTENT_FIELD_REFERENCE_STEP_FANOUT.TABLE_NAME) + " "
            + row.get(INTENT_FIELD_REFERENCE_STEP_FANOUT.VERDICT);
    }

}
