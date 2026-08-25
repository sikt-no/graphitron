package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FOREIGN_KEY_NODE_KEY_LIFT;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_foreign_key_node_key_lift} states: whether a decoded node id of the type a
 * foreign key points at has columns on the declaring table to be compared against, or reaches its
 * row only through the join.
 *
 * <p>The verdict is what every write rail asks before admitting an input field that carries such an
 * id, so the two cases that matter most are the pair that look identical from the declaring side.
 * A key pointing at the target's node key and a key pointing at some other unique column of the
 * same target are the same shape in the catalog and opposite answers here, and the second is the
 * one a relation that only checked for a foreign key would wave through.
 *
 * <p>The rule is stated as the absent landing rather than as the present translation, so the
 * composite cases are the ones pinning that framing: a key covering some but not all of a
 * two-column node key is translated on the strength of the position that did not land, not on the
 * one that did.
 */
class ForeignKeyNodeKeyLiftTest {

    private static final String GRAPH = "g";
    private static final String PKG = "no.sikt.jooq";
    private static final String PUBLIC = "public";

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            body.accept(dsl);
        });
    }

    private static void table(DSLContext dsl, String name, String... columns) {
        seedTable(dsl, PKG, PUBLIC, name);
        for (int ordinal = 0; ordinal < columns.length; ordinal++) {
            seedColumn(dsl, PKG, PUBLIC, name, columns[ordinal], ordinal,
                columns[ordinal].toUpperCase(Locale.ROOT));
        }
    }

    /** A node type bound to a table, with its key pinned in SDL to the named columns. */
    private static void nodeType(DSLContext dsl, String typeName, String tableName, String... keyColumns) {
        seedType(dsl, GRAPH, typeName, "OBJECT");
        seedTableBinding(dsl, GRAPH, typeName, tableName);
        seedNode(dsl, GRAPH, typeName);
        for (int position = 0; position < keyColumns.length; position++) {
            seedNodeKeyColumnRef(dsl, GRAPH, typeName, position, keyColumns[position]);
        }
    }


    /** The supertype row a referential constraint hangs off, then the reference itself. */
    private static void foreignKey(DSLContext dsl, String declaringTable, String constraintName,
                                   String referencedTable, String referencedConstraint) {
        seedConstraint(dsl, PKG, PUBLIC, declaringTable, constraintName, "FOREIGN KEY", null);
        seedReferentialConstraint(dsl, PKG, PUBLIC, declaringTable, constraintName,
            PKG, PUBLIC, referencedTable, referencedConstraint);
    }

    private static String lift(DSLContext dsl, String constraintName) {
        derive(dsl);
        return dsl.select(INTENT_FOREIGN_KEY_NODE_KEY_LIFT.LIFT)
            .from(INTENT_FOREIGN_KEY_NODE_KEY_LIFT)
            .where(INTENT_FOREIGN_KEY_NODE_KEY_LIFT.CONSTRAINT_NAME.eq(constraintName))
            .fetchOne(INTENT_FOREIGN_KEY_NODE_KEY_LIFT.LIFT);
    }

    /** The ordinary reference: the key points at the target's primary key, which is its node key. */
    @Test
    void aKeyReferencingTheNodeKeyLiftsDirectly() {
        withCatalog(dsl -> {
            table(dsl, "parent", "pk_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "pk_id");
            nodeType(dsl, "Parent", "parent", "pk_id");

            table(dsl, "child", "child_id", "parent_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_fkey", "parent", "parent_pkey");

            assertThat(lift(dsl, "child_parent_fkey")).isEqualTo("DIRECT");
        });
    }

    /**
     * The same shape pointing at an alternate unique key of the same target. Nothing on the child
     * holds the node key's value, so the filter can only be written by visiting the parent.
     */
    @Test
    void aKeyReferencingAnAlternateKeyIsTranslated() {
        withCatalog(dsl -> {
            table(dsl, "parent", "pk_id", "alt_key");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "pk_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "parent", "parent_alt_uk", "alt_key");
            nodeType(dsl, "Parent", "parent", "pk_id");

            table(dsl, "child", "child_id", "parent_alt_key");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_alt_fkey", "parent", "parent_alt_uk");

            assertThat(lift(dsl, "child_parent_alt_fkey")).isEqualTo("TRANSLATED");
        });
    }

    /** A two-column node key referenced whole lifts, each position having somewhere to land. */
    @Test
    void aCompositeNodeKeyReferencedWholeLiftsDirectly() {
        withCatalog(dsl -> {
            table(dsl, "parent", "key_a", "key_b");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "key_a", "key_b");
            nodeType(dsl, "Parent", "parent", "key_a", "key_b");

            table(dsl, "child", "child_id", "fk_a", "fk_b");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_fkey", "parent", "parent_pkey");

            assertThat(lift(dsl, "child_parent_fkey")).isEqualTo("DIRECT");
        });
    }

    /**
     * A two-column node key of which the referenced constraint carries only one position. The
     * partial landing is the case the absent-landing framing exists for: one position lands and the
     * verdict is still translated, because a decoded id is a whole tuple or it is nothing.
     */
    @Test
    void aCompositeNodeKeyReferencedInPartIsTranslated() {
        withCatalog(dsl -> {
            table(dsl, "parent", "key_a", "key_b");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "key_a");
            nodeType(dsl, "Parent", "parent", "key_a", "key_b");

            table(dsl, "child", "child_id", "fk_a");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_fkey", "parent", "parent_pkey");

            assertThat(lift(dsl, "child_parent_fkey")).isEqualTo("TRANSLATED");
        });
    }

    /**
     * A key whose referenced table backs no node type draws no row. There is no id of that table to
     * decode, so the question does not arise, and answering it negatively would put a translated
     * verdict on a key nothing was ever going to lift through.
     */
    @Test
    void aKeyIntoATableBackingNoNodeTypeDrawsNoRow() {
        withCatalog(dsl -> {
            table(dsl, "parent", "pk_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "pk_id");

            table(dsl, "child", "child_id", "parent_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_fkey", "parent", "parent_pkey");

            assertThat(lift(dsl, "child_parent_fkey")).isNull();
        });
    }

    /** The row is keyed by the declaring table, so it names the child and the parent it reaches. */
    @Test
    void theRowNamesTheDeclaringSideAndTheTypeItAsksAbout() {
        withCatalog(dsl -> {
            table(dsl, "parent", "pk_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "parent", "parent_pkey", "pk_id");
            nodeType(dsl, "Parent", "parent", "pk_id");

            table(dsl, "child", "child_id", "parent_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "child", "child_pkey", "child_id");
            foreignKey(dsl, "child", "child_parent_fkey", "parent", "parent_pkey");

            derive(dsl);
            var row = dsl.selectFrom(INTENT_FOREIGN_KEY_NODE_KEY_LIFT)
                .where(INTENT_FOREIGN_KEY_NODE_KEY_LIFT.CONSTRAINT_NAME.eq("child_parent_fkey"))
                .fetchSingle();
            assertThat(row.getTableName()).isEqualTo("child");
            assertThat(row.getReferencedTable()).isEqualTo("parent");
            assertThat(row.getNodeTypeName()).isEqualTo("Parent");
            assertThat(row.getGraphName()).isEqualTo(GRAPH);
        });
    }
}
