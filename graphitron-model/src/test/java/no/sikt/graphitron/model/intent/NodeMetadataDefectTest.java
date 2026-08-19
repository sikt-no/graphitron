package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_METADATA_DEFECT;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedStatedNodeMetadata;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What {@code intent_node_metadata_defect} says is wrong with the node-identity metadata a
 * generated table class stated. One case per defect the closed vocabulary names, plus the grain the
 * relation is built to have (several defects on one table, each its own row) and the resolution rule
 * the entry arm turns on.
 *
 * <p>Every input is stated as rows, which is the point of having the rows at all. Most of the states
 * a case here needs come from a generated class that is malformed: a constant holding null, a
 * constant holding something that is not the declared type, an array entry naming a column the table
 * does not have. Reaching those through a crawler would mean shipping a broken generated class into
 * the fixture tree for each of them; stating them takes a line. That the crawler reaches these
 * relations from real generated classes at all is pinned beside the crawler.
 *
 * <p>The well-formed cases assert <em>no</em> rows, which is the relation's other half: a metadata
 * row with no defect rows is what well-formed means, and a case that pins the silence is pinning
 * where the derivation stops complaining.
 */
class NodeMetadataDefectTest {

    private static final String SOURCE = "com.example.jooq";
    private static final String SCHEMA = "public";
    private static final String TABLE = "film";

    // ===== One arm at a time, on the type-id constant =====

    @Test
    void aTypeIdConstantTheClassNeverDeclaredIsItsOwnDefect() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "ABSENT", null, null, "FIELD_ARRAY", null);
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");

            assertThat(defects(dsl)).containsExactly(tuple("TYPE_ID_NOT_DECLARED", null));
        });
    }

    @Test
    void aTypeIdConstantHoldingNullIsNotTheSameAsNotDeclaringOne() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "NULL", null, null, "FIELD_ARRAY", null);
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");

            assertThat(defects(dsl)).containsExactly(tuple("TYPE_ID_NULL", null));
        });
    }

    @Test
    void aTypeIdConstantOfAnotherTypeIsADefectTheClassWitnessNames() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "OTHER", null, "java.lang.Integer", "FIELD_ARRAY", null);
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");

            assertThat(defects(dsl)).containsExactly(tuple("TYPE_ID_WRONG_TYPE", null));
        });
    }

    /** The empty string is a stated value, so its emptiness is judged here rather than at capture. */
    @Test
    void anEmptyTypeIdIsAStatedValueThisRelationRejects() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");

            assertThat(defects(dsl)).containsExactly(tuple("TYPE_ID_EMPTY", null));
        });
    }

    // ===== One arm at a time, on the key-columns constant =====

    @Test
    void aKeyColumnsConstantTheClassNeverDeclaredIsItsOwnDefect() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "STRING", "Film", null, "ABSENT", null);

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMNS_NOT_DECLARED", null));
        });
    }

    @Test
    void aKeyColumnsConstantHoldingNullIsNotTheSameAsNotDeclaringOne() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "STRING", "Film", null, "NULL", null);

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMNS_NULL", null));
        });
    }

    @Test
    void aKeyColumnsConstantOfAnotherTypeIsADefectTheClassWitnessNames() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "STRING", "Film", null, "OTHER", "java.lang.String");

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMNS_WRONG_TYPE", null));
        });
    }

    /** An empty array is the array form with no entries, which is how it reaches this arm at all. */
    @Test
    void anArrayWithNoEntriesIsTheEmptyDefect() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMNS_EMPTY", null));
        });
    }

    // ===== The per-entry arms, which carry the offending position =====

    @Test
    void aNullEntryIsADefectAtItsOwnPosition() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 1, null);

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMN_ENTRY_NULL", 1));
        });
    }

    @Test
    void anEntryNamingNoColumnOfTheTableIsADefectAtItsOwnPosition() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 1, "not_a_column");

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMN_UNRESOLVED", 1));
        });
    }

    /**
     * A column of the same name on another table does not resolve an entry: the entry is resolved
     * against its own table's columns, which is the join the relation is keyed through.
     */
    @Test
    void anEntryDoesNotResolveAgainstAnotherTablesColumn() {
        withCatalog(dsl -> {
            seedTable(dsl, SOURCE, SCHEMA, "actor");
            seedColumn(dsl, SOURCE, SCHEMA, "actor", "actor_id", 0, "ACTOR_ID");
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "actor_id");

            assertThat(defects(dsl)).containsExactly(tuple("KEY_COLUMN_UNRESOLVED", 0));
        });
    }

    // ===== The resolution rule the entry arm turns on =====

    /**
     * The two tiers the reading side resolves an entry through, both case-insensitive: the generated
     * Java name and the SQL name. Mirroring only one of them would diverge from that side exactly
     * where the two tiers exist to arbitrate.
     */
    @Test
    void anEntryResolvesCaseInsensitivelyThroughEitherName() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "FILM_ID");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 1, "release_year");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 2, "ReLeAsEyEaR");

            assertThat(defects(dsl))
                .as("the SQL name in another case, the SQL name as spelled, and the Java name"
                    + " in another case")
                .isEmpty();
        });
    }

    // ===== The grain =====

    /** Every defect gets its own row, with no first-failing short-circuit to make an order normative. */
    @Test
    void aTableExhibitingSeveralDefectsGetsARowForEachOfThem() {
        withCatalog(dsl -> {
            seedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE,
                "NULL", null, null, "FIELD_ARRAY", null);
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, null);
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 1, "not_a_column");

            assertThat(defects(dsl)).containsExactlyInAnyOrder(
                tuple("TYPE_ID_NULL", null),
                tuple("KEY_COLUMN_ENTRY_NULL", 0),
                tuple("KEY_COLUMN_UNRESOLVED", 1));
        });
    }

    /**
     * The silence that means well-formed, and the silence that means nothing was published. Both are
     * no rows here, which is why "well-formed" is the conjunction with a metadata row and never the
     * absence of defects alone.
     */
    @Test
    void wellFormedMetadataAndNoMetadataAtAllAreBothSilentHere() {
        withCatalog(dsl -> {
            seedStatedNodeMetadata(dsl, SOURCE, SCHEMA, TABLE, "Film");
            seedNodeKeyColumn(dsl, SOURCE, SCHEMA, TABLE, 0, "film_id");
            seedTable(dsl, SOURCE, SCHEMA, "actor");
            seedColumn(dsl, SOURCE, SCHEMA, "actor", "actor_id", 0, "ACTOR_ID");

            assertThat(defects(dsl)).isEmpty();
            assertThat(dsl.fetchCount(INTENT_NODE_METADATA_DEFECT))
                .as("a table that published nothing states no metadata row to be judged")
                .isZero();
        });
    }

    // ===== Harness =====

    /** One source, one schema, and one table with two columns for an entry to resolve against. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(dsl -> {
            seedSource(dsl, SOURCE, "JOOQ_SCHEMA");
            seedTable(dsl, SOURCE, SCHEMA, TABLE);
            seedColumn(dsl, SOURCE, SCHEMA, TABLE, "film_id", 0, "FILM_ID");
            seedColumn(dsl, SOURCE, SCHEMA, TABLE, "release_year", 1, "RELEASEYEAR");
            body.accept(dsl);
        });
    }

    private static List<org.assertj.core.groups.Tuple> defects(DSLContext dsl) {
        return dsl.select(INTENT_NODE_METADATA_DEFECT.DEFECT, INTENT_NODE_METADATA_DEFECT.POSITION)
            .from(INTENT_NODE_METADATA_DEFECT)
            .orderBy(INTENT_NODE_METADATA_DEFECT.DEFECT, INTENT_NODE_METADATA_DEFECT.POSITION)
            .fetch()
            .map(r -> tuple(r.value1(), r.value2()));
    }
}
