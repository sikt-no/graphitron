package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_LANDING_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_REFERENCE_FOR_APPLICATION;
import static no.sikt.graphitron.model.test.SeededStore.OccurrenceStep;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceFor;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedForeignKey;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedOccurrencePath;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedReferenceFor;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is wrong with the landing a {@code @nodeId} decode computed:
 * {@code intent_node_id_decode_landing_defect}, one row per refused instruction, use site and
 * branch, in a closed verdict vocabulary of two.
 *
 * <p>Every case here is a schema the store accepted with nothing to say about it. The landing was
 * matched by SQL column name along the path's hops and never checked, so a path stopping one hop
 * short landed the node's key on whatever the intermediate table happened to have named like it, and
 * a landed position pairing a converter-backed column with a raw one counted as one column. Each
 * verdict therefore gets its negative control stated beside it, because the population this relation
 * adds is exactly the schemas that used to pass.
 *
 * <p>The population's two edges get as many cases as the verdicts. The completeness gate keeps this
 * relation off a chain that stalled, which the walk already refuses by name at the same coordinate;
 * and the participant exclusion keeps it off a branch whose stated navigation the store computed for
 * a route the generator does not take. Both are pinned in both directions, the exclusion over
 * {@code intent_reference_for_application}'s own rows as well, because a narrowing asserted only as
 * an absence passes equally well when it excludes everything.
 */
class NodeIdDecodeLandingDefectTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";
    private static final String STRING = "java.lang.String";
    private static final String LONG = "java.lang.Long";

    // ===== PATH_STOPS_SHORT =====

    /**
     * The truncated junction path: one hop reaches the junction table and the node type lives one
     * hop further on, so a decoded id of that type binds against columns the path never reached.
     * Nothing lands, which is what makes this a correct-looking remote predicate today rather than a
     * codegen crash.
     */
    @Test
    void aPathStoppingShortOfTheNodeTypesTableIsRefusedNamingBothTables() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory", "film_category_film_id_fkey");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.films(inCategory) film Category PATH_STOPS_SHORT"
                    + " lands on film_category, not category");
        });
    }

    /** The same path with its last step written: the terminal hop arrives, so there is no fault. */
    @Test
    void theWholePathToTheNodeTypesTableIsNoDefect() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_category_category_id_fkey");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * The completeness gate. A second step that resolves to no hop leaves the chain stopped at its
     * first, which reads exactly like a truncated path from the hop relation alone; the walk already
     * refuses that coordinate by name for the key it could not resolve, and a row here would be a
     * second answer to one fault.
     */
    @Test
    void aChainThatStalledMidPathDrawsNoRowHere() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            // The second step names a key that connects nothing to the junction table.
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "org_child_org_fkey");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== LANDING_TYPE_DISAGREEMENT =====

    /**
     * A discovered key whose two ends disagree on the Java type jOOQ binds them as. The two columns
     * are one physical column pairing and the same SQL type; only the generated model's spelling
     * differs, which is what makes the emitted comparison uncompilable while the SQL it would have
     * rendered was always valid.
     */
    @Test
    void aLandingWhoseTwoColumnsDisagreeOnJavaTypeIsRefusedNamingBoth() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedField(dsl, GRAPH, "Query", "children", "OrgChild", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "children", "ofOrg", "Org");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.children(ofOrg) org_child Org LANDING_TYPE_DISAGREEMENT position 0"
                    + " org.org_code java.lang.String vs org_child.org_code java.lang.Long");
        });
    }

    /**
     * The same shape where both ends report one type: nothing to refuse. The control that says the
     * comparison is on the type the catalog reports and not on whether a converter is present, both
     * of these columns carrying one.
     */
    @Test
    void aLandingWhoseTwoColumnsAgreeIsNoDefect() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgCampus", "org_campus");
            seedField(dsl, GRAPH, "Query", "campuses", "OrgCampus", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "campuses", "ofOrg", "Org");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A position that landed nowhere has no second column for a type to disagree with. The junction
     * chain, whose whole path resolves and whose lift contributes nothing: a correlated
     * {@code EXISTS} on the node type's own table, which is a shape and not a fault.
     */
    @Test
    void aPositionLandingNowhereDrawsNoTypeVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Category", "category");
            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "films", "inCategory", "Category");
            seedArgumentPath(dsl, "Query", "films", "inCategory",
                "film_category_film_id_fkey", "film_category_category_id_fkey");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * One fault draws one row. A path that stops short and whose wrongly matched column also
     * disagrees on type is a single mistake: the column the lift matched by name on the wrong table
     * is not the node type's key column at all, so a type verdict beside the path one would be
     * describing a comparison the author never asked for.
     */
    @Test
    void aMislandedPathWhoseColumnAlsoDisagreesDrawsOnlyThePathVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedField(dsl, GRAPH, "Query", "children", "OrgChild", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "children", "ofOrg", "Org");
            seedArgumentPath(dsl, "Query", "children", "ofOrg", "org_child_mirror_fkey");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.children(ofOrg) org_child Org PATH_STOPS_SHORT"
                    + " lands on org_mirror, not org");
        });
    }

    // ===== The navigations this relation does not judge =====

    /** Own-row identity has nothing to land: the keys arrive on the row's own key columns. */
    @Test
    void ownRowIdentityDrawsNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedField(dsl, GRAPH, "Query", "orgs", "Org", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "orgs", "ids", "Org");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    /**
     * A discovered key arrives at the node type's table by construction, the discovery demanding a
     * key that connects the pair, so that navigation can only ever draw the type verdict. Stated as
     * a case rather than as a comment because it is what makes the path verdict's
     * {@code AUTHORED_PATH} gate a statement about the resolution and not a scoping guess.
     */
    @Test
    void aDiscoveredKeyDrawsOnlyTheTypeVerdict() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedField(dsl, GRAPH, "Query", "children", "OrgChild", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "children", "ofOrg", "Org");

            assertThat(rows(dsl).map(row -> row.get(INTENT_NODE_ID_DECODE_LANDING_DEFECT.VERDICT)))
                .containsExactly("LANDING_TYPE_DISAGREEMENT");
        });
    }

    // ===== The participant exclusion =====

    /**
     * A branch a per-participant route applies at is declined, and its sibling is not. The store
     * states such a branch's navigation as auto-discovery's, no view reading the {@code @referenceFor}
     * step tables, while the classifier walks the chain the author wrote there; a verdict computed
     * under the wrong navigation can refuse a schema that is sound, so the branch goes unjudged.
     * The sibling branch, named by no application, keeps its verdict, which is what says the
     * exclusion is about the route and not about the coordinate.
     */
    @Test
    void aBranchAParticipantRouteAppliesAtIsNotJudgedAndItsSiblingStillIs() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedTableBinding(dsl, GRAPH, "OrgSibling", "org_sibling");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChild", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgSibling", 1);
            seedField(dsl, GRAPH, "Query", "holders", "Holder", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "holders", "ofOrg", "Org");
            seedArgumentReferenceFor(dsl, GRAPH, "Query", "holders", "ofOrg", 0, "OrgChild");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.holders(ofOrg) org_sibling Org LANDING_TYPE_DISAGREEMENT position 0"
                    + " org.org_code java.lang.String vs org_sibling.org_code java.lang.Long");
        });
    }

    /**
     * With no application written, both branches are judged. The other direction of the case above,
     * and the one that says the exclusion is what silenced the first branch rather than anything
     * about a polymorphic consumer.
     */
    @Test
    void bothBranchesAreJudgedWhereNoParticipantRouteApplies() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedTableBinding(dsl, GRAPH, "OrgSibling", "org_sibling");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChild", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgSibling", 1);
            seedField(dsl, GRAPH, "Query", "holders", "Holder", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "holders", "ofOrg", "Org");

            assertThat(verdicts(dsl)).containsExactly(
                "Query.holders(ofOrg) org_child Org LANDING_TYPE_DISAGREEMENT position 0"
                    + " org.org_code java.lang.String vs org_child.org_code java.lang.Long",
                "Query.holders(ofOrg) org_sibling Org LANDING_TYPE_DISAGREEMENT position 0"
                    + " org.org_code java.lang.String vs org_sibling.org_code java.lang.Long");
        });
    }

    /**
     * Two participants over one table are one endpoint row, so naming one of them silences both.
     * That is the endpoint relation's grain arriving here rather than a conservatism chosen by this
     * relation: the endpoint is keyed by the coordinate and the departing table, and the participant
     * axis the distinction would need is what the store's own {@code @referenceFor} repair adds.
     */
    @Test
    void twoParticipantsOverOneTableAreExcludedTogether() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChildRead", "org_child");
            seedTableBinding(dsl, GRAPH, "OrgChildWrite", "org_child");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChildRead", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChildWrite", 1);
            seedField(dsl, GRAPH, "Query", "holders", "Holder", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "holders", "ofOrg", "Org");
            seedArgumentReferenceFor(dsl, GRAPH, "Query", "holders", "ofOrg", 0, "OrgChildRead");

            assertThat(rows(dsl)).isEmpty();
        });
    }

    // ===== The relation the exclusion reads =====

    /** An application naming a participant its consumer offers resolves to that participant's table. */
    @Test
    void anApplicationNamingAParticipantOfItsConsumerHasARow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedTableBinding(dsl, GRAPH, "OrgSibling", "org_sibling");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChild", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgSibling", 1);
            seedField(dsl, GRAPH, "Query", "holders", "Holder", true);
            seedArgument(dsl, GRAPH, "Query", "holders", "ofOrg", "ID");
            seedArgumentReferenceFor(dsl, GRAPH, "Query", "holders", "ofOrg", 0, "OrgChild");

            assertThat(applications(dsl)).containsExactly(
                "ARGUMENT Query.holders(ofOrg)#0 at Query.holders -> OrgChild org_child");
        });
    }

    /**
     * An application naming a participant no consumer offers has no row, which is the classifier's
     * inertness rule stated as a population rather than as a filter every reader repeats. The
     * whole-schema detection over these rows is what keeps that inertness from swallowing a typo.
     */
    @Test
    void anApplicationNamingNoParticipantAnywhereHasNoRow() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChild", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgSibling", 1);
            seedTableBinding(dsl, GRAPH, "OrgSibling", "org_sibling");
            seedField(dsl, GRAPH, "Query", "holders", "Holder", true);
            seedArgument(dsl, GRAPH, "Query", "holders", "ofOrg", "ID");
            seedArgumentReferenceFor(dsl, GRAPH, "Query", "holders", "ofOrg", 0, "OrgChidl");

            assertThat(applications(dsl)).isEmpty();
        });
    }

    /**
     * One input type consumed by two queries with different participant sets: the application
     * applies under the consumer that offers the participant and is inert under the one that does
     * not. Why the consumer is part of the grain, and why validity cannot be keyed on the
     * application alone.
     */
    @Test
    void anInputFieldApplicationResolvesPerConsumer() {
        withCatalog(dsl -> {
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedTableBinding(dsl, GRAPH, "OrgSibling", "org_sibling");
            seedTableBinding(dsl, GRAPH, "OrgCampus", "org_campus");
            seedUnionMember(dsl, GRAPH, "Holder", "OrgChild", 0);
            seedUnionMember(dsl, GRAPH, "Holder", "OrgSibling", 1);
            seedUnionMember(dsl, GRAPH, "Sited", "OrgCampus", 0);
            seedUnionMember(dsl, GRAPH, "Sited", "OrgSibling", 1);
            seedType(dsl, GRAPH, "HolderFilter", "INPUT_OBJECT");
            seedField(dsl, GRAPH, "HolderFilter", "ofOrg", "ID", false);
            seedReferenceFor(dsl, GRAPH, "HolderFilter", "ofOrg", 0, "OrgChild");
            for (String consumer : new String[]{"holders", "sited"}) {
                seedField(dsl, GRAPH, "Query", consumer,
                    consumer.equals("holders") ? "Holder" : "Sited", true);
                seedArgument(dsl, GRAPH, "Query", consumer, "filter", "HolderFilter");
                seedOccurrencePath(dsl, GRAPH, "Query", consumer, "filter", "HolderFilter",
                    new OccurrenceStep("HolderFilter", "ofOrg", "ID"));
            }

            assertThat(applications(dsl)).containsExactly(
                "INPUT_FIELD HolderFilter.ofOrg#0 at Query.holders -> OrgChild org_child");
        });
    }

    // ===== The graph partition =====

    /** The graph partition holds. */
    @Test
    void aSiblingGraphIsRefusedNothing() {
        withCatalog(dsl -> {
            seedNodeType(dsl, "Org", "org");
            seedTableBinding(dsl, GRAPH, "OrgChild", "org_child");
            seedField(dsl, GRAPH, "Query", "children", "OrgChild", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "children", "ofOrg", "Org");

            derive(dsl);
            assertThat(rowsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Fixture =====

    /**
     * Two families of table, one per verdict. A film/category junction carries the truncated path:
     * one hop reaches the junction and the node type lives one hop past it. An org family carries
     * the type divergence: {@code org.org_code} is the node type's key and reports
     * {@code java.lang.String} the way a converter-backed column does, while two of the tables
     * referencing it spell their own {@code org_code} {@code java.lang.Long} and one spells it
     * {@code java.lang.String}, so the same discovered key diverges at two departures and agrees at
     * the third. The mirror table is the mislanding whose column also diverges: it carries the
     * node's key column name and is not the node's table.
     */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedType(dsl, GRAPH, "ID", "SCALAR");

            for (String table : new String[]{"film", "category", "film_category"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "category", "category_id", 0, "CATEGORY_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "film_id", 0, "FILM_ID");
            seedColumn(dsl, PKG, PUBLIC, "film_category", "category_id", 1, "CATEGORY_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "category", "category_pkey", "category_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film_category", "film_category_pkey",
                "film_id", "category_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_film_id_fkey",
                "film", "film_pkey", "film_id");
            seedForeignKey(dsl, PKG, PUBLIC, "film_category", "film_category_category_id_fkey",
                "category", "category_pkey", "category_id");

            seedTable(dsl, PKG, PUBLIC, "org");
            seedColumn(dsl, PKG, PUBLIC, "org", "org_code", 0, "ORG_CODE", STRING);
            seedPrimaryKey(dsl, PKG, PUBLIC, "org", "org_pkey", "org_code");
            seedTable(dsl, PKG, PUBLIC, "org_mirror");
            seedColumn(dsl, PKG, PUBLIC, "org_mirror", "org_code", 0, "ORG_CODE", STRING);
            seedPrimaryKey(dsl, PKG, PUBLIC, "org_mirror", "org_mirror_pkey", "org_code");

            seedOrgReferrer(dsl, "org_child", LONG);
            seedOrgReferrer(dsl, "org_sibling", LONG);
            seedOrgReferrer(dsl, "org_campus", STRING);
            seedForeignKey(dsl, PKG, PUBLIC, "org_child", "org_child_mirror_fkey",
                "org_mirror", "org_mirror_pkey", "org_code");

            body.accept(dsl);
        });
    }

    /** One table referencing the org key, spelling its own copy of the column as {@code binding}. */
    private static void seedOrgReferrer(DSLContext dsl, String table, String binding) {
        seedTable(dsl, PKG, PUBLIC, table);
        seedColumn(dsl, PKG, PUBLIC, table, table + "_id", 0, table.toUpperCase() + "_ID");
        seedColumn(dsl, PKG, PUBLIC, table, "org_code", 1, "ORG_CODE", binding);
        seedPrimaryKey(dsl, PKG, PUBLIC, table, table + "_pkey", table + "_id");
        seedForeignKey(dsl, PKG, PUBLIC, table, table + "_org_fkey",
            "org", "org_pkey", "org_code");
    }

    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
    }

    /** An argument-site {@code @reference} whose elements each name a key, in written order. */
    private static void seedArgumentPath(DSLContext dsl, String typeName, String fieldName,
                                         String argumentName, String... keyRefs) {
        seedArgumentReference(dsl, GRAPH, typeName, fieldName, argumentName, 0);
        for (int position = 0; position < keyRefs.length; position++) {
            seedArgumentReferenceStep(dsl, GRAPH, typeName, fieldName, argumentName,
                0, position, null, keyRefs[position]);
        }
    }

    private static List<String> verdicts(DSLContext dsl) {
        return rows(dsl).map(NodeIdDecodeLandingDefectTest::render);
    }

    private static Result<Record> rows(DSLContext dsl) {
        derive(dsl);
        return rowsIn(dsl, GRAPH);
    }

    private static Result<Record> rowsIn(DSLContext dsl, String graphName) {
        var v = INTENT_NODE_ID_DECODE_LANDING_DEFECT;
        return dsl.select(v.fields())
            .from(v)
            .where(v.GRAPH_NAME.eq(graphName))
            .orderBy(v.USE_SITE, v.ORIGIN_TABLE, v.VERDICT, v.POSITION)
            .fetch();
    }

    /** The coordinate, the branch, the node type, the verdict, and the operands it quotes. */
    private static String render(Record row) {
        var v = INTENT_NODE_ID_DECODE_LANDING_DEFECT;
        String head = row.get(v.USE_SITE) + " " + row.get(v.ORIGIN_TABLE) + " "
            + row.get(v.NODE_TYPE_NAME) + " " + row.get(v.VERDICT);
        if ("PATH_STOPS_SHORT".equals(row.get(v.VERDICT))) {
            return head + " lands on " + row.get(v.TERMINAL_TABLE)
                + ", not " + row.get(v.TARGET_TABLE);
        }
        return head + " position " + row.get(v.POSITION) + " "
            + row.get(v.TARGET_TABLE) + "." + row.get(v.KEY_COLUMN_NAME) + " "
            + row.get(v.KEY_BINDING_TYPE) + " vs " + row.get(v.ORIGIN_TABLE) + "."
            + row.get(v.LOCAL_COLUMN_NAME) + " " + row.get(v.LOCAL_BINDING_TYPE);
    }

    /** The application resolution the exclusion reads, one line per application and consumer. */
    private static List<String> applications(DSLContext dsl) {
        derive(dsl);
        var a = INTENT_REFERENCE_FOR_APPLICATION;
        return dsl.select(a.fields())
            .from(a)
            .where(a.GRAPH_NAME.eq(GRAPH))
            .orderBy(a.SITE, a.TYPE_NAME, a.FIELD_NAME, a.ORDINAL,
                a.CONSUMING_TYPE_NAME, a.CONSUMING_FIELD_NAME)
            .fetch(row -> row.get(a.SITE) + " " + row.get(a.TYPE_NAME) + "."
                + row.get(a.FIELD_NAME)
                + (row.get(a.ARGUMENT_NAME) == null ? "" : "(" + row.get(a.ARGUMENT_NAME) + ")")
                + "#" + row.get(a.ORDINAL)
                + " at " + row.get(a.CONSUMING_TYPE_NAME) + "." + row.get(a.CONSUMING_FIELD_NAME)
                + " -> " + row.get(a.PARTICIPANT_TYPE_NAME) + " " + row.get(a.TABLE_NAME));
    }
}
