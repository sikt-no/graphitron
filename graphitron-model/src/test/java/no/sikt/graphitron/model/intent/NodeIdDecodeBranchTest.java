package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_INPUT_FIELD_CARRIER_ROLE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_COLUMN;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_HOP_COLUMN;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The branch a decode departs from is part of the decode family's grain. A field whose named type is
 * a multi-table polymorphic container is rooted in one table per participant, so a {@code @nodeId}
 * argument on it has one endpoint pair per branch and the decode is stated once per branch.
 *
 * <p>Every relation below {@link no.sikt.graphitron.model.Tables#INTENT_NODE_ID_DECODE_ENDPOINT} once
 * keyed on the coordinate alone, which is that key with the branch removed. Where the branches join on
 * foreign keys that happen to pair the same column names, the rows became indistinguishable and the
 * relation held one row many times over; the counts above it then read those repeats as separate
 * facts. These cases pin the branch as a key column rather than the row counts as a symptom, because a
 * collapse at the top would have made the counts right and the answers no better.
 */
class NodeIdDecodeBranchTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";

    /**
     * Three branches joining on identically named columns are three hops and three pairings, not one
     * repeated three times. The shape the defect was found on, reduced to the smallest catalog that
     * carries it: each participant declares its own foreign key to the node type's table and all three
     * pair the same two column names.
     */
    @Test
    void eachBranchContributesItsOwnHopColumnPairing() {
        withPolymorphicCatalog(dsl -> {
            derive(dsl);

            assertThat(hopColumns(dsl)).containsExactly(
                "emnerolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "emnerolle 1 rollekode -> rollekode",
                "klasserolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "klasserolle 1 rollekode -> rollekode",
                "kullrolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "kullrolle 1 rollekode -> rollekode");
        });
    }

    /**
     * The key positions land once per branch. Before the branch was carried, the lift walked every
     * branch's rows for every branch, so this coordinate held three branches times two positions times
     * three lifted rows where it holds six facts.
     */
    @Test
    void eachBranchLandsItsOwnKeyPositionsOnce() {
        withPolymorphicCatalog(dsl -> {
            derive(dsl);

            assertThat(decodeColumns(dsl)).containsExactly(
                "emnerolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "emnerolle 1 rollekode -> rollekode",
                "klasserolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "klasserolle 1 rollekode -> rollekode",
                "kullrolle 0 institusjonsnr_eier -> institusjonsnr_eier",
                "kullrolle 1 rollekode -> rollekode");
        });
    }

    /**
     * Each branch is its own decode, and its arity is the node key's real width. The column the item
     * is named for: arity counts the key-column child within the partition, so before the branch was
     * a partitioning column this coordinate was described as having eighteen key columns where the
     * node type has two, and one row stood for three branches rather than three rows for three.
     */
    @Test
    void eachBranchIsItsOwnDecodeWithTheKeysRealArity() {
        withPolymorphicCatalog(dsl -> {
            derive(dsl);

            var t = INTENT_NODE_ID_DECODE;
            assertThat(dsl.select(t.ORIGIN_TABLE, t.DESTINATION, t.ARITY)
                    .from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH))
                    .orderBy(t.ORIGIN_TABLE)
                    .fetch(r -> r.value1() + " " + r.value2() + " arity=" + r.value3()))
                .containsExactly(
                    "emnerolle OWN_TABLE_COLUMNS arity=2",
                    "klasserolle OWN_TABLE_COLUMNS arity=2",
                    "kullrolle OWN_TABLE_COLUMNS arity=2");
        });
    }

    /**
     * The carrier role answers per branch, which is the one reading downstream of here that a
     * consumer can observe. Its rows were already keyed on the resolving table while the landing
     * aggregate it read was not, so every branch of a slot received one verdict computed over all
     * branches' rows together; here each branch lifts to a column of its own table and is told so.
     */
    @Test
    void theCarrierRoleAnswersOncePerBranch() {
        withChainedCatalog(dsl -> {
            derive(dsl);

            var t = INTENT_INPUT_FIELD_CARRIER_ROLE;
            assertThat(dsl.select(t.RESOLVING_TABLE, t.CARRIER_ROLE)
                    .from(t)
                    .where(t.GRAPH_NAME.eq(GRAPH))
                    .orderBy(t.RESOLVING_TABLE)
                    .fetch(r -> r.value1() + " " + r.value2()))
                .containsExactly(
                    "emnerolle CROSS_TABLE_FK",
                    "klasserolle CROSS_TABLE_FK");
        });
    }

    /**
     * Neither relation holds a row twice. The guard that would have failed on the day the branch was
     * dropped, stated as a property of the two relations the sweep found holding duplicates rather
     * than as a roster over every materialization target: the other eighteen have a key available.
     */
    @Test
    void neitherRelationHoldsADuplicateRow() {
        withPolymorphicCatalog(dsl -> {
            derive(dsl);

            assertThat(duplicateRows(dsl, INTENT_NODE_ID_DECODE_HOP_COLUMN)).isZero();
            assertThat(duplicateRows(dsl, INTENT_NODE_ID_DECODE_COLUMN)).isZero();
        });
    }

    /**
     * A two-hop path walks inside the branch it started in. Both branches resolve the same authored
     * path, their first hops arrive at different intermediate tables whose second-hop departing
     * columns share a name, and their second hops therefore look interchangeable to a walk keyed on
     * the coordinate alone. Such a walk leaves one branch's chain at its first hop and rejoins the
     * other's at its second, so each branch lifts back to a column of the table it never departed.
     *
     * <p>The reason a collapse above could not have fixed this: the extra row is a route no branch
     * declares rather than a second copy of a route one does, so removing repeats removes evidence
     * and leaves the wrong answer standing. Each branch here lifts its own column and only its own.
     */
    @Test
    void aTwoHopPathLiftsBackThroughItsOwnBranchOnly() {
        withChainedCatalog(dsl -> {
            derive(dsl);

            assertThat(decodeColumns(dsl)).containsExactly(
                "emnerolle 0 rollekode -> emne_code",
                "klasserolle 0 rollekode -> klasse_code");
        });
    }

    // ===== Fixture =====

    /**
     * A union of three role tables, each declaring its own foreign key to the one role table the node
     * type binds, all three pairing the same two column names. The container binds no table of its
     * own, which is what makes the field's scope the participants' tables rather than one table.
     */
    private static void withPolymorphicCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedType(dsl, GRAPH, "ID", "SCALAR");

            seedTable(dsl, PKG, PUBLIC, "rolle");
            seedColumn(dsl, PKG, PUBLIC, "rolle", "institusjonsnr_eier", 0, "INSTITUSJONSNR_EIER");
            seedColumn(dsl, PKG, PUBLIC, "rolle", "rollekode", 1, "ROLLEKODE");
            seedPrimaryKey(dsl, PKG, PUBLIC, "rolle", "rolle_pkey",
                "institusjonsnr_eier", "rollekode");

            for (String table : BRANCH_TABLES) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedColumn(dsl, PKG, PUBLIC, table, "institusjonsnr_eier", 0,
                    "INSTITUSJONSNR_EIER");
                seedColumn(dsl, PKG, PUBLIC, table, "rollekode", 1, "ROLLEKODE");
                seedPrimaryKey(dsl, PKG, PUBLIC, table, table + "_pkey",
                    "institusjonsnr_eier", "rollekode");
                seedForeignKey(dsl, PKG, PUBLIC, table, table + "_rolle_fkey",
                    "rolle", "rolle_pkey", "institusjonsnr_eier", "rollekode");
            }

            seedTableBinding(dsl, GRAPH, "Rolle", "rolle");
            seedNode(dsl, GRAPH, "Rolle");
            for (int position = 0; position < BRANCH_TABLES.length; position++) {
                String memberType = MEMBER_TYPES[position];
                seedTableBinding(dsl, GRAPH, memberType, BRANCH_TABLES[position]);
                seedUnionMember(dsl, GRAPH, "Roller", memberType, position);
            }

            seedField(dsl, GRAPH, "Query", "personroller", "Roller", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "personroller", "fsRoller", "Rolle");

            body.accept(dsl);
        });
    }

    private static final String[] BRANCH_TABLES = {"emnerolle", "klasserolle", "kullrolle"};
    private static final String[] MEMBER_TYPES = {"EmneRolle", "KlasseRolle", "KullRolle"};

    /**
     * Two branches whose authored path is one path and whose walks are two. Each role table declares
     * a key spelled {@code link_fkey} onto its own intermediate table, and each intermediate declares
     * one spelled {@code rolle_fkey} onto the role table, so the one path the input field carries
     * resolves against whichever table the branch departs. The intermediates share the column name
     * their second hop departs, which is what makes the two second hops interchangeable to a walk
     * that has forgotten which branch it is on; the columns the first hops depart differ, which is
     * what makes the confusion visible in the lift rather than merely doubling a row.
     */
    private static void withChainedCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedType(dsl, GRAPH, "ID", "SCALAR");

            seedTable(dsl, PKG, PUBLIC, "rolle");
            seedColumn(dsl, PKG, PUBLIC, "rolle", "rollekode", 0, "ROLLEKODE");
            seedPrimaryKey(dsl, PKG, PUBLIC, "rolle", "rolle_pkey", "rollekode");

            for (int branch = 0; branch < CHAINED_TABLES.length; branch++) {
                String table = CHAINED_TABLES[branch];
                String link = CHAINED_LINKS[branch];
                String departing = CHAINED_COLUMNS[branch];

                seedTable(dsl, PKG, PUBLIC, table);
                seedColumn(dsl, PKG, PUBLIC, table, departing, 0, departing.toUpperCase());
                seedPrimaryKey(dsl, PKG, PUBLIC, table, table + "_pkey", departing);

                // The intermediate: both spell the column their second hop departs the same way.
                seedTable(dsl, PKG, PUBLIC, link);
                seedColumn(dsl, PKG, PUBLIC, link, "link_code", 0, "LINK_CODE");
                seedPrimaryKey(dsl, PKG, PUBLIC, link, link + "_pkey", "link_code");

                seedForeignKey(dsl, PKG, PUBLIC, table, "link_fkey",
                    link, link + "_pkey", departing);
                seedForeignKey(dsl, PKG, PUBLIC, link, "rolle_fkey",
                    "rolle", "rolle_pkey", "link_code");

                seedTableBinding(dsl, GRAPH, CHAINED_MEMBERS[branch], table);
                seedUnionMember(dsl, GRAPH, "Roller", CHAINED_MEMBERS[branch], branch);
            }

            seedTableBinding(dsl, GRAPH, "Rolle", "rolle");
            seedNode(dsl, GRAPH, "Rolle");

            seedField(dsl, GRAPH, "Query", "personroller", "Roller", true);
            seedArgument(dsl, GRAPH, "Query", "personroller", "filter", "RolleFilter");
            seedType(dsl, GRAPH, "RolleFilter", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "RolleFilter", "rolleId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "RolleFilter", "rolleId", "Rolle");
            seedFieldReference(dsl, GRAPH, "RolleFilter", "rolleId", 0);
            seedFieldReferenceStep(dsl, GRAPH, "RolleFilter", "rolleId", 0, 0, null, "link_fkey");
            seedFieldReferenceStep(dsl, GRAPH, "RolleFilter", "rolleId", 0, 1, null, "rolle_fkey");
            seedOccurrencePath(dsl, GRAPH, "Query", "personroller", "filter", "RolleFilter",
                new OccurrenceStep("RolleFilter", "rolleId", "ID"));

            body.accept(dsl);
        });
    }

    private static final String[] CHAINED_TABLES = {"emnerolle", "klasserolle"};
    private static final String[] CHAINED_LINKS = {"emne_link", "klasse_link"};
    private static final String[] CHAINED_COLUMNS = {"emne_code", "klasse_code"};
    private static final String[] CHAINED_MEMBERS = {"EmneRolle", "KlasseRolle"};

    private static List<String> hopColumns(DSLContext dsl) {
        var t = INTENT_NODE_ID_DECODE_HOP_COLUMN;
        return dsl.select(t.ORIGIN_TABLE, t.PAIR_POSITION, t.FROM_COLUMN_NAME, t.TO_COLUMN_NAME)
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.ORIGIN_TABLE, t.POSITION, t.PAIR_POSITION)
            .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3() + " -> " + r.value4());
    }

    private static List<String> decodeColumns(DSLContext dsl) {
        var t = INTENT_NODE_ID_DECODE_COLUMN;
        return dsl.select(t.ORIGIN_TABLE, t.POSITION, t.KEY_COLUMN_NAME, t.LOCAL_COLUMN_NAME)
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.ORIGIN_TABLE, t.POSITION)
            .fetch(r -> r.value1() + " " + r.value2() + " " + r.value3() + " -> "
                        + (r.value4() != null ? r.value4() : "(none)"));
    }

    /** How many rows the relation holds beyond its own distinct content. */
    private static int duplicateRows(DSLContext dsl, org.jooq.Table<? extends Record> relation) {
        Result<Record> all = dsl.select(relation.fields()).from(relation).fetch();
        return all.size() - (int) all.stream().map(Record::valuesRow).distinct().count();
    }
}
