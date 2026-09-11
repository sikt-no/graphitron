package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_ARGUMENT_FILTER_ROLE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_CONTAINER_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_CANDIDATE_NODE_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_ENDPOINT;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_ENCODE;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_INSTRUCTION;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
import static no.sikt.graphitron.model.Tables.INTENT_RECORD_SLOT_ASSIGNABLE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldNodeId;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.seedNodeKeyColumnRef;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedRecordSupertypes;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.seedUnionMember;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @nodeId(typeName:)} naming a multitable interface or union: the population it draws, the
 * candidate set it resolves, the destination it reaches at a slot typed as a record supertype, and
 * the five verdicts that refuse it.
 *
 * <p>Four relations are pinned together because each is defined against the others. The instruction
 * relation says which coordinates carry a container at all and admits every site deliberately; the
 * candidate relation says which node types an id there may belong to; the destination says the decode
 * was carried out; and the defect view says what stopped it. A case asserting only one of the four
 * would pass just as well if the widening had reached a coordinate it must not, which is exactly what
 * the population-edge cases below exist to rule out.
 *
 * <p>The negative assertions are the ones the grain decision is answerable for, and they are stated
 * as claims rather than left as hopes: {@code candidates} stays 1 at a polymorphic use site however
 * many members the container has, so a two-member container is still told apart from a two-overload
 * producer; a polymorphic slot draws no row in the incumbent defect view, and the case pins that the
 * quiet comes from the missing key shape rather than from the ambiguity filter; and a container that
 * binds a table itself draws no endpoint row, which is the one place the quiet is a predicate rather
 * than a missing join.
 */
class PolymorphicNodeIdDecodeTest {

    private static final String GRAPH = "g";
    private static final String PKG = "cat";
    private static final String PUBLIC = "public";
    private static final String CLASSES = "classes";
    private static final String SVC = "no.example.Svc";
    private static final String UPDATABLE = "org.jooq.UpdatableRecord";
    private static final String TABLE_RECORD = "org.jooq.TableRecord";

    // ===== The captured ancestry, and the one derivation over it =====

    /**
     * The direct leg: the closure the catalog walk captured above each record class answers the
     * assignability question without the census being reached at all, which is the whole reason the
     * relation is captured rather than derived.
     */
    @Test
    void aSlotTypedAsACapturedSupertypeIsAssignableFromEveryMembersRecord() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(assignable(dsl))
                .contains("Customer " + recordClass("customer") + " " + UPDATABLE,
                    "Staff " + recordClass("staff") + " " + UPDATABLE);
        });
    }

    /**
     * The {@code recordImplements} case, and the claim is that it needs no second census: the catalog
     * walk climbs the live record class, so an interface a consumer's jOOQ configuration puts on the
     * records is a captured row and so is every supertype of that interface. A slot typed at either
     * depth therefore resolves off the one captured closure.
     *
     * <p>The case seeds the two depths separately, which is what discriminates it: a relation holding
     * only the record's direct parents would carry the interface and not the type above it.
     */
    @Test
    void aSlotTypedAboveARecordImplementsInterfaceResolvesOffTheCapturedClosure() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedRecordSupertypes(dsl, PKG, PUBLIC, "customer",
                "no.example.Occupant", "no.example.Identified");
            seedRecordSupertypes(dsl, PKG, PUBLIC, "staff",
                "no.example.Occupant", "no.example.Identified");
            seedProducerSlot(dsl, "occupant", "no.example.Identified");
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(assignable(dsl))
                .as("both depths of the captured closure answer, the interface and its own supertype")
                .contains("Customer " + recordClass("customer") + " no.example.Occupant",
                    "Customer " + recordClass("customer") + " no.example.Identified",
                    "Staff " + recordClass("staff") + " no.example.Identified");
            assertThat(destinations(dsl))
                .as("and the destination follows, both members landing in the declared interface")
                .containsExactly(
                    "Query.occupants(occupant) Customer POLYMORPHIC_RECORD 1",
                    "Query.occupants(occupant) Staff POLYMORPHIC_RECORD 1");
        });
    }

    // ===== The membership rung, total on purpose =====

    /** Every member of every container, with both predicates as columns rather than as a filter. */
    @Test
    void theMemberRelationReportsEveryMemberWithItsTwoFlags() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            // A third member that binds a table and is not a node type, which is the population
            // MEMBER_NOT_NODE_TYPE reads and which a filtered relation could not offer at all.
            seedUnionMember(dsl, GRAPH, "AddressOccupant", "Rental", 3);
            seedTableBinding(dsl, GRAPH, "Rental", "rental");
            // And a fourth binding nothing, the shape that has no record to decode into.
            seedUnionMember(dsl, GRAPH, "AddressOccupant", "Ghost", 4);
            seedType(dsl, GRAPH, "Ghost", "OBJECT");

            assertThat(members(dsl)).containsExactly(
                "AddressOccupant UNION Customer table node",
                "AddressOccupant UNION Ghost - -",
                "AddressOccupant UNION Rental table -",
                "AddressOccupant UNION Staff table node");
        });
    }

    /** A container with no table-bound member at all still draws its rows, flags and all. */
    @Test
    void aContainerWithNoTableMembersStillDrawsItsMembers() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Anything", "UNION");
            seedUnionMember(dsl, GRAPH, "Anything", "Ghost", 1);
            seedType(dsl, GRAPH, "Ghost", "OBJECT");

            assertThat(members(dsl)).containsExactly("Anything UNION Ghost - -");
        });
    }

    // ===== The resolution axis =====

    /**
     * One identity row for a node type and one row per admissible member for a container, which is
     * what makes the destination a branch inside the existing slot arm rather than a second arm.
     */
    @Test
    void theCandidateRelationAnswersForBothKinds() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedUnionMember(dsl, GRAPH, "AddressOccupant", "Rental", 3);
            seedTableBinding(dsl, GRAPH, "Rental", "rental");

            assertThat(candidates(dsl)).containsExactly(
                "AddressOccupant -> Customer",
                "AddressOccupant -> Staff",
                "Customer -> Customer",
                "Staff -> Staff");
        });
    }

    // ===== The instruction population =====

    /**
     * One instruction row per use site, carrying the container and the new kind, on the authored basis
     * a written {@code typeName:} always carries. The grain claim: one row and not one per member, so
     * the slot relation's {@code candidates} still counts slots rather than members.
     */
    @Test
    void aContainerNamingInstructionIsOneRowPerUseSiteOnTheAuthoredBasis() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(instructions(dsl)).containsExactly(
                "ARGUMENT Query.occupants(occupant) EXPLICIT_TYPE_NAME AddressOccupant POLY_CONTAINER");
            assertThat(slotCandidateCounts(dsl))
                .as("a two-member container is still one candidate slot, not two")
                .containsExactly("Query.occupants(occupant) 1");
        });
    }

    /**
     * The kind fork, as a claim rather than a hope. {@code @node} is declared {@code on OBJECT} in
     * SDL, so this shape cannot be authored today; the store's population relation constrains no
     * kind, and the two arms are written to be disjoint against exactly that. Seeding it is what says
     * the disjointness is by construction and not by the SDL happening not to allow it.
     */
    @Test
    void aContainerThatIsItselfANodeTypeResolvesAsANodeTypeInstead() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedNode(dsl, GRAPH, "AddressOccupant");
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(instructions(dsl))
                .as("the node-type arm answers first, so no instruction draws both rows")
                .containsExactly(
                    "ARGUMENT Query.occupants(occupant) EXPLICIT_TYPE_NAME AddressOccupant NODE_TYPE");
            assertThat(candidates(dsl))
                .as("and the resolution axis follows, the member arm excluding such a container")
                .contains("AddressOccupant -> AddressOccupant")
                .doesNotContain("AddressOccupant -> Customer");
        });
    }

    // ===== The destination =====

    /**
     * One {@code POLYMORPHIC_RECORD} row per member, each with that member's own arity, where the
     * store can see the slot's own type. The arity is the member's rather than the container's, which
     * a container resolving no key of its own is what makes load-bearing.
     */
    @Test
    void aSlotTypedAsARecordSupertypeDrawsOneRowPerMember() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(destinations(dsl)).containsExactly(
                "Query.occupants(occupant) Customer POLYMORPHIC_RECORD 1",
                "Query.occupants(occupant) Staff POLYMORPHIC_RECORD 1");
        });
    }

    /**
     * The single-type destinations are unchanged by the join the polymorphic branch added: a node type
     * at the same slot kind draws its own one row through the identity arm of the candidate relation.
     */
    @Test
    void aNodeTypeAtTheSameSlotStillDrawsItsOneSingleTypeRow() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedProducerSlot(dsl, "occupant", recordClass("customer"));
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "Customer");

            assertThat(destinations(dsl)).containsExactly(
                "Query.occupants(occupant) Customer JOOQ_RECORD 1");
        });
    }

    /**
     * A container with one admissible member draws its one row and no verdict, which is the store's
     * half of a rule the walk states the same way: what makes a decode polymorphic is the type the
     * author named, not how many members it has today. No relation along the chain asks a count, so
     * this case is what says that silence is the rule rather than an oversight, and that the editor's
     * completion for such a container leads to a schema the build accepts.
     */
    @Test
    void aContainerWithOneAdmissibleMemberDrawsItsOneRowAndNoVerdict() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "SoleOccupant", "UNION");
            seedUnionMember(dsl, GRAPH, "SoleOccupant", "Customer", 1);
            seedNodeType(dsl, "Customer", "customer", "customer_id");
            seedField(dsl, GRAPH, "Query", "occupants", "Customer", true);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "SoleOccupant");

            assertThat(candidates(dsl))
                .filteredOn(row -> row.startsWith("SoleOccupant "))
                .as("the member arm yields the one member, beside the identity rows")
                .containsExactly("SoleOccupant -> Customer");
            assertThat(destinations(dsl)).containsExactly(
                "Query.occupants(occupant) Customer POLYMORPHIC_RECORD 1");
            assertThat(polymorphicDefects(dsl))
                .as("no verdict refuses a container for its member count")
                .isEmpty();
        });
    }

    /** One member's record failing the ancestry test refuses the whole slot, not just that member. */
    @Test
    void oneMemberFailingTheAncestryTestRefusesTheWholeSlot() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            // staff's table has no primary key in this case's reading, so jOOQ generates a
            // TableRecordImpl for it and its record is not an UpdatableRecord. The captured closure
            // is what says so, which is why the case narrows the ancestry rather than the key.
            narrowToTableRecord(dsl, "staff");
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(destinations(dsl)).isEmpty();
            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Query.occupants(occupant) AddressOccupant SLOT_NOT_SUPERTYPE_OF_MEMBER Staff");
        });
    }

    // ===== The five verdicts =====

    @Test
    void aTableBoundMemberThatIsNotANodeTypeIsNamed() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedUnionMember(dsl, GRAPH, "AddressOccupant", "Rental", 3);
            seedTableBinding(dsl, GRAPH, "Rental", "rental");
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Query.occupants(occupant) AddressOccupant MEMBER_NOT_NODE_TYPE Rental");
        });
    }

    @Test
    void aContainerWithNoTableMembersAtASlotIsRefused() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "Anything", "UNION");
            seedUnionMember(dsl, GRAPH, "Anything", "Ghost", 1);
            seedType(dsl, GRAPH, "Ghost", "OBJECT");
            seedField(dsl, GRAPH, "Query", "occupants", "Ghost", true);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "Anything");

            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Query.occupants(occupant) Anything NO_TABLE_MEMBERS -");
        });
    }

    /**
     * The single-table container, and the one incumbent predicate this slice adds: the container binds
     * a table itself, so the endpoint relation would have drawn a real row whose named type resolves
     * no key, and everything downstream of the endpoint would have read that row as a decode.
     */
    @Test
    void aContainerThatBindsATableIsRefusedAndDrawsNoEndpoint() {
        withCatalog(dsl -> {
            seedType(dsl, GRAPH, "MediaItem", "INTERFACE");
            seedTableBinding(dsl, GRAPH, "MediaItem", "film");
            seedField(dsl, GRAPH, "Query", "occupants", "MediaItem", true);
            seedProducerSlot(dsl, "occupant", UPDATABLE);
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "MediaItem");

            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Query.occupants(occupant) MediaItem SINGLE_TABLE_CONTAINER -");
            assertThat(endpoints(dsl))
                .as("the kind predicate is what keeps a bogus endpoint row off this coordinate")
                .isEmpty();
        });
    }

    // ===== The population edge =====

    /**
     * A container-naming argument on a generated fetch field: no producer parameter is fed from it, so
     * the value binds a table predicate rather than descending into Java, and the polymorphic rule
     * does not reach the coordinate. The widening's reach is a claim here, not a discovery: the same
     * argument draws no {@code NODE_ID} filter role either.
     */
    @Test
    void aContainerAtAReadSideArgumentDrawsTheCoordinateVerdictAndNoFilterRole() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            // The control, seeded first and at its own coordinate: the same argument shape naming a
            // node type does draw NODE_ID at precedence 4, so the container's silence below is the
            // kind predicate rather than this relation drawing nothing at all here.
            seedField(dsl, GRAPH, "Query", "customers", "Customer", true);
            seedArgumentNodeId(dsl, GRAPH, "Query", "customers", "customerId", "Customer");
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Query.occupants(occupant) AddressOccupant CONTAINER_NOT_AT_A_SLOT -");
            assertThat(nodeIdFilterRoles(dsl))
                .as("a container resolves no node key, so it contributes to no filter surface,"
                    + " while the node type beside it still does")
                .containsExactly("Query.customers(customerId)");
            assertThat(destinations(dsl))
                .as("and no decode is carried out at the container's coordinate")
                .noneMatch(row -> row.startsWith("Query.occupants(occupant)"));
        });
    }

    /**
     * The coordinate verdict's own silence, and it is the classpath census's rather than the
     * author's: a {@code @service} field whose producer class no census entry holds leaves the slot
     * relation empty for a reason nobody could act on, so the verdict stands aside. Without this the
     * verdict fires on every graph captured without the entry its services live in, refusing a schema
     * whose other half was simply not read.
     */
    @Test
    void aProducerClassTheCensusNeverReachedDrawsNoCoordinateVerdict() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            // The @service is written and the class is not in the census: no seedClass, no
            // seedMethod, so intent_field_producer_method resolves nothing at this coordinate.
            seedService(dsl, GRAPH, "Query", "occupants", SVC, "get");
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(polymorphicDefects(dsl))
                .as("the absence is the capture's, so no verdict is drawn on it")
                .isEmpty();
        });
    }

    /** An output field encodes rather than decodes, and a container has nothing to encode from. */
    @Test
    void aContainerAtAnOutputFieldDrawsTheCoordinateVerdictAndNoEncode() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            seedField(dsl, GRAPH, "Customer", "occupantId", "ID", false);
            seedFieldNodeId(dsl, GRAPH, "Customer", "occupantId", "AddressOccupant");

            assertThat(polymorphicDefects(dsl)).containsExactly(
                "Customer.occupantId AddressOccupant CONTAINER_NOT_AT_A_SLOT -");
            assertThat(encodes(dsl)).isEmpty();
        });
    }

    // ===== The disjointness the two defect views rest on =====

    /**
     * The incumbent defect view stays quiet on a polymorphic slot, and the case pins <em>why</em>: its
     * join to the key shape misses on the slot's resolved type, and the same use site's
     * {@code candidates} is 1, so the quiet cannot be the ambiguity filter instead.
     */
    @Test
    void aPolymorphicSlotDrawsNoRowInTheIncumbentDefectView() {
        withCatalog(dsl -> {
            seedUnion(dsl);
            // A slot typed as neither a record nor a key column's type, which is precisely the shape
            // the incumbent view refuses at a node type.
            seedProducerSlot(dsl, "occupant", "java.lang.String");
            seedArgumentNodeId(dsl, GRAPH, "Query", "occupants", "occupant", "AddressOccupant");

            assertThat(incumbentDefects(dsl)).isEmpty();
            assertThat(slotCandidateCounts(dsl))
                .as("the quiet is the missing key shape, not the ambiguity filter")
                .containsExactly("Query.occupants(occupant) 1");
            assertThat(polymorphicDefects(dsl))
                .as("and the polymorphic view is where the refusal lands instead")
                .containsExactly(
                    "Query.occupants(occupant) AddressOccupant SLOT_NOT_SUPERTYPE_OF_MEMBER Customer",
                    "Query.occupants(occupant) AddressOccupant SLOT_NOT_SUPERTYPE_OF_MEMBER Staff");
        });
    }

    // ===== Fixture =====

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, CLASSES, "JAR");
            seedGraphSource(dsl, GRAPH, CLASSES);
            for (String table : new String[]{"customer", "staff", "rental", "film"}) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedColumn(dsl, PKG, PUBLIC, "customer", "customer_id", 0, "CUSTOMER_ID");
            seedColumn(dsl, PKG, PUBLIC, "staff", "staff_id", 0, "STAFF_ID");
            seedColumn(dsl, PKG, PUBLIC, "rental", "rental_id", 0, "RENTAL_ID");
            seedColumn(dsl, PKG, PUBLIC, "film", "film_id", 0, "FILM_ID");
            seedPrimaryKey(dsl, PKG, PUBLIC, "customer", "customer_pkey", "customer_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "staff", "staff_pkey", "staff_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "rental", "rental_pkey", "rental_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            // The ancestry a primary-keyed table's generated record carries, which is what admits an
            // UpdatableRecord<?> slot. Stated per case where a case is about its absence.
            for (String table : new String[]{"customer", "staff", "rental", "film"}) {
                seedRecordSupertypes(dsl, PKG, PUBLIC, table,
                    "org.jooq.impl.UpdatableRecordImpl", UPDATABLE, TABLE_RECORD, "org.jooq.Record");
            }
            seedType(dsl, GRAPH, "ID", "SCALAR");
            body.accept(dsl);
        });
    }

    /** The union, its two node-type members, and the field the argument cases sit on. */
    private static void seedUnion(DSLContext dsl) {
        seedType(dsl, GRAPH, "AddressOccupant", "UNION");
        seedUnionMember(dsl, GRAPH, "AddressOccupant", "Customer", 1);
        seedUnionMember(dsl, GRAPH, "AddressOccupant", "Staff", 2);
        seedNodeType(dsl, "Customer", "customer", "customer_id");
        seedNodeType(dsl, "Staff", "staff", "staff_id");
        seedField(dsl, GRAPH, "Query", "occupants", "Customer", true);
    }

    private static void seedNodeType(DSLContext dsl, String typeName, String tableRef,
                                     String keyColumn) {
        seedTableBinding(dsl, GRAPH, typeName, tableRef);
        seedNode(dsl, GRAPH, typeName);
        seedNodeKeyColumnRef(dsl, GRAPH, typeName, 0, keyColumn);
    }

    /**
     * A {@code @service} on {@code Query.occupants} whose method declares one parameter of the
     * argument's own name and the given type: the whole classpath side of a slot in one call.
     */
    private static void seedProducerSlot(DSLContext dsl, String paramName, String paramClass) {
        seedService(dsl, GRAPH, "Query", "occupants", SVC, "get");
        seedClass(dsl, CLASSES, SVC, "CLASS");
        seedMethod(dsl, CLASSES, SVC, "get", "()V");
        seedMethodParameter(dsl, CLASSES, SVC, "get", "()V", 0, paramName,
            Map.of("", paramClass));
    }

    /**
     * Drops the {@code UpdatableRecord} rungs from one table's captured ancestry, leaving the two a
     * primary-key-less table's generated record actually has. The fixture seeds the primary-keyed
     * ancestry for every table, so a case about the keyless shape takes it away rather than a second
     * seeder offering both.
     */
    private static void narrowToTableRecord(DSLContext dsl, String tableName) {
        var st = no.sikt.graphitron.model.Tables.SQL_TABLE_RECORD_SUPERTYPE;
        dsl.deleteFrom(st)
            .where(st.SOURCE_NAME.eq(PKG), st.TABLE_SCHEMA.eq(PUBLIC), st.TABLE_NAME.eq(tableName),
                st.SUPERTYPE_NAME.in(UPDATABLE, "org.jooq.impl.UpdatableRecordImpl"))
            .execute();
    }

    /** The generated record class the catalog fixture names for a table. */
    private static String recordClass(String tableName) {
        return PKG + ".tables.records." + tableName + "Record";
    }

    // ===== Renderers =====

    private static List<String> assignable(DSLContext dsl) {
        derive(dsl);
        var a = INTENT_RECORD_SLOT_ASSIGNABLE;
        return dsl.select(a.fields()).from(a).where(a.GRAPH_NAME.eq(GRAPH))
            .orderBy(a.NODE_TYPE_NAME, a.SLOT_TYPE_NAME)
            .fetch()
            .map(r -> r.get(a.NODE_TYPE_NAME) + " " + r.get(a.RECORD_CLASS) + " "
                + r.get(a.SLOT_TYPE_NAME));
    }

    private static List<String> members(DSLContext dsl) {
        derive(dsl);
        var m = INTENT_NODE_CONTAINER_MEMBER;
        return dsl.select(m.fields()).from(m).where(m.GRAPH_NAME.eq(GRAPH))
            .orderBy(m.CONTAINER_NAME, m.MEMBER_TYPE_NAME)
            .fetch()
            .map(r -> r.get(m.CONTAINER_NAME) + " " + r.get(m.CONTAINER_KIND) + " "
                + r.get(m.MEMBER_TYPE_NAME)
                + " " + (Boolean.TRUE.equals(r.get(m.IS_TABLE_BOUND)) ? "table" : "-")
                + " " + (Boolean.TRUE.equals(r.get(m.IS_NODE_TYPE)) ? "node" : "-"));
    }

    private static List<String> candidates(DSLContext dsl) {
        derive(dsl);
        var c = INTENT_NODE_ID_CANDIDATE_NODE_TYPE;
        return dsl.select(c.fields()).from(c).where(c.GRAPH_NAME.eq(GRAPH))
            .orderBy(c.RESOLVED_TYPE_NAME, c.NODE_TYPE_NAME)
            .fetch()
            .map(r -> r.get(c.RESOLVED_TYPE_NAME) + " -> " + r.get(c.NODE_TYPE_NAME));
    }

    private static List<String> instructions(DSLContext dsl) {
        derive(dsl);
        var i = INTENT_NODE_ID_INSTRUCTION;
        return dsl.select(i.fields()).from(i).where(i.GRAPH_NAME.eq(GRAPH))
            .orderBy(i.SITE, i.USE_SITE, i.BASIS, i.RESOLVED_TYPE_NAME)
            .fetch()
            .map(r -> r.get(i.SITE) + " " + r.get(i.USE_SITE) + " " + r.get(i.BASIS) + " "
                + r.get(i.RESOLVED_TYPE_NAME) + " " + r.get(i.RESOLVED_TYPE_KIND));
    }

    private static List<String> slotCandidateCounts(DSLContext dsl) {
        derive(dsl);
        var s = INTENT_NODE_ID_DECODE_SLOT;
        return dsl.select(s.fields()).from(s).where(s.GRAPH_NAME.eq(GRAPH))
            .orderBy(s.USE_SITE, s.PARAM_NAME)
            .fetch()
            .map(r -> r.get(s.USE_SITE) + " " + r.get(s.CANDIDATES));
    }

    private static List<String> destinations(DSLContext dsl) {
        derive(dsl);
        var d = INTENT_NODE_ID_DECODE;
        return dsl.select(d.fields()).from(d).where(d.GRAPH_NAME.eq(GRAPH))
            .orderBy(d.USE_SITE, d.NODE_TYPE_NAME)
            .fetch()
            .map(r -> r.get(d.USE_SITE) + " " + r.get(d.NODE_TYPE_NAME) + " "
                + r.get(d.DESTINATION) + " " + r.get(d.ARITY));
    }

    private static List<String> polymorphicDefects(DSLContext dsl) {
        derive(dsl);
        var v = INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
        return dsl.select(v.fields()).from(v).where(v.GRAPH_NAME.eq(GRAPH))
            .orderBy(v.USE_SITE, v.VERDICT, v.MEMBER_TYPE_NAME)
            .fetch()
            .map(PolymorphicNodeIdDecodeTest::renderDefect);
    }

    private static String renderDefect(Record row) {
        var v = INTENT_NODE_ID_POLYMORPHIC_DECODE_DEFECT;
        String member = row.get(v.MEMBER_TYPE_NAME);
        return row.get(v.USE_SITE) + " " + row.get(v.CONTAINER_NAME) + " " + row.get(v.VERDICT)
            + " " + (member == null ? "-" : member);
    }

    private static List<String> incumbentDefects(DSLContext dsl) {
        derive(dsl);
        var v = INTENT_NODE_ID_DECODE_DEFECT;
        return dsl.select(v.fields()).from(v).where(v.GRAPH_NAME.eq(GRAPH))
            .orderBy(v.USE_SITE, v.VERDICT)
            .fetch()
            .map(r -> r.get(v.USE_SITE) + " " + r.get(v.VERDICT));
    }

    private static List<String> endpoints(DSLContext dsl) {
        derive(dsl);
        var e = INTENT_NODE_ID_DECODE_ENDPOINT;
        return dsl.select(e.fields()).from(e).where(e.GRAPH_NAME.eq(GRAPH))
            .orderBy(e.USE_SITE, e.NODE_TYPE_NAME)
            .fetch()
            .map(r -> r.get(e.USE_SITE) + " " + r.get(e.NODE_TYPE_NAME));
    }

    private static List<String> encodes(DSLContext dsl) {
        derive(dsl);
        var e = INTENT_NODE_ID_ENCODE;
        return dsl.select(e.fields()).from(e).where(e.GRAPH_NAME.eq(GRAPH))
            .orderBy(e.USE_SITE)
            .fetch()
            .map(r -> r.get(e.USE_SITE) + " " + r.get(e.NODE_TYPE_NAME));
    }

    private static List<String> nodeIdFilterRoles(DSLContext dsl) {
        derive(dsl);
        var f = INTENT_ARGUMENT_FILTER_ROLE;
        return dsl.select(f.fields()).from(f)
            .where(f.GRAPH_NAME.eq(GRAPH), f.ROLE.eq("NODE_ID"))
            .orderBy(f.TYPE_NAME, f.FIELD_NAME, f.ARGUMENT_NAME)
            .fetch()
            .map(r -> r.get(f.TYPE_NAME) + "." + r.get(f.FIELD_NAME) + "("
                + r.get(f.ARGUMENT_NAME) + ")");
    }
}
