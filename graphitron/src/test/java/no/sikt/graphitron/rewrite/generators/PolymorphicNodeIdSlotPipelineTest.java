package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline tier: {@code @nodeId(typeName:)} naming a multitable interface or union at a
 * {@code @service} slot, decoded into a slot typed as a jOOQ record supertype. The positive cases
 * assert that the schema <em>builds</em> and that the slot's transform is the polymorphic decode
 * carrying the candidates in member order, because a silent detection and a red build look alike
 * from above; the dispatch the emitted helper performs is behaviour and is pinned at the execution
 * tier rather than as a string here.
 *
 * <p>The refusals are the other half and each names one fact: a slot typed as one candidate's own
 * record (the single-type spelling the author declined), a scalar slot (nowhere to carry which
 * implementation the id was), a container with a {@code @table} member that is not a node type (a
 * candidate set with a hole in it would reject one member's ids at runtime), a single-table
 * {@code @discriminate} container (one record class, so the single-type decode is what the author
 * wants), and a candidate whose table has no primary key under an {@code UpdatableRecord<?>} slot
 * (jOOQ generates a {@code TableRecordImpl} there, which the slot's own declared ancestry says).
 */
@PipelineTier
class PolymorphicNodeIdSlotPipelineTest {

    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String PRODUCER_STUB = "no.sikt.graphitron.rewrite.PublicNodeIdServiceStub";

    /** The two node types the union holds, plus the {@code Node} interface both publish. */
    private static final String OCCUPANTS = """
        interface Node { id: ID! }
        type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
            id: ID! @nodeId
        }
        type Staff implements Node @table(name: "staff") @node(keyColumns: ["staff_id"]) {
            id: ID! @nodeId
        }
        union AddressOccupant = Customer | Staff
        """;

    private static String beanSchema(String member, String method) {
        return OCCUPANTS + """
            input AssignOccupantInput {
                %s
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(member, SERVICE_STUB, method);
    }

    private static String producerSchema(String container, String method) {
        return OCCUPANTS + """
            type Film @table(name: "film") { title: String }
            type Query {
                films(key: ID! @nodeId(typeName: "%s")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(container, PRODUCER_STUB, method);
    }

    // ===== The slot takes the container's members =====

    @Test
    void anUpdatableRecordMemberTakesEveryMembersDecode() {
        var leaf = beanLeaf(beanSchema("occupant: ID! @nodeId(typeName: \"AddressOccupant\")",
            "assignOccupant"), false);
        assertThat(leaf.containerName()).isEqualTo("AddressOccupant");
        assertThat(leaf.slotType().typeName().toString())
            .as("the helper returns the slot's own declared supertype")
            .isEqualTo("org.jooq.UpdatableRecord<?>");
        assertThat(leaf.candidates())
            .as("one candidate per @table member, in the union's own member order")
            .extracting(CallSiteExtraction.PolymorphicCandidate::typeName)
            .containsExactly("Customer", "Staff");
        assertThat(leaf.candidates())
            .extracting(CallSiteExtraction.PolymorphicCandidate::typeId)
            .containsExactly("Customer", "Staff");
        assertThat(leaf.candidates().getFirst().keyColumns())
            .as("each candidate carries its own key, resolved through the single-type path")
            .extracting(c -> c.sqlName())
            .containsExactly("customer_id");
        assertThat(leaf.nonNull()).as("ID! is non-null").isTrue();
    }

    @Test
    void aTableRecordMemberIsAdmittedToo() {
        assertThat(beanLeaf(beanSchema("occupant: ID! @nodeId(typeName: \"AddressOccupant\")",
            "assignOccupantTableRecord"), false).slotType().typeName().toString())
            .isEqualTo("org.jooq.TableRecord<?>");
    }

    @Test
    void aBareRecordMemberIsAdmittedToo() {
        assertThat(beanLeaf(beanSchema("occupant: ID! @nodeId(typeName: \"AddressOccupant\")",
            "assignOccupantPlainRecord"), false).slotType().typeName().toString())
            .as("Record declares no type parameter, so it is admitted unparameterized")
            .isEqualTo("org.jooq.Record");
    }

    @Test
    void aListMemberDecodesEachElementOnItsOwnPrefix() {
        var leaf = beanLeaf(beanSchema("occupants: [ID!] @nodeId(typeName: \"AddressOccupant\")",
            "assignOccupantList"), true);
        assertThat(leaf.candidates())
            .extracting(CallSiteExtraction.PolymorphicCandidate::typeName)
            .containsExactly("Customer", "Staff");
    }

    @Test
    void theContainerHelperAndItsPerMemberHelpersLandOnTheFetchersClass() {
        var fetchers = findSpec("QueryFetchers",
            beanSchema("occupant: ID! @nodeId(typeName: \"AddressOccupant\")", "assignOccupant"));
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the container helper plus one null-returning helper per candidate")
            .contains("decodeAddressOccupantRecord",
                "decodeAddressOccupantRecordCustomer", "decodeAddressOccupantRecordStaff");
        assertThat(method(fetchers, "decodeAddressOccupantRecord").returnType().toString())
            .as("the container helper returns the admitted slot type")
            .isEqualTo("org.jooq.UpdatableRecord<?>");
    }

    @Test
    void theListMemberEmitsTheListVariantAndTheScalarItDelegatesTo() {
        var fetchers = findSpec("QueryFetchers",
            beanSchema("occupants: [ID!] @nodeId(typeName: \"AddressOccupant\")",
                "assignOccupantList"));
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("decodeAddressOccupantRecordList", "decodeAddressOccupantRecord");
        assertThat(method(fetchers, "decodeAddressOccupantRecordList").returnType().toString())
            .isEqualTo("java.util.List<org.jooq.UpdatableRecord<?>>");
    }

    @Test
    void theSameHoldsAtAProducerParameter() {
        var leaf = producerLeaf(producerSchema("AddressOccupant", "getOccupantByUpdatableRecord"));
        assertThat(leaf.containerName()).isEqualTo("AddressOccupant");
        assertThat(leaf.candidates())
            .extracting(CallSiteExtraction.PolymorphicCandidate::typeName)
            .containsExactly("Customer", "Staff");
        assertThat(leaf.slotType().typeName().toString()).isEqualTo("org.jooq.UpdatableRecord<?>");
    }

    @Test
    void aProducerListParameterTakesTheListVariant() {
        var fetchers = findSpec("QueryFetchers",
            producerSchema("AddressOccupant", "getOccupantsByUpdatableRecords"));
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("decodeAddressOccupantRecordList", "decodeAddressOccupantRecord");
    }

    /**
     * An interface container resolves the same way a union does: the candidate set is the schema's
     * own implementation list, which is the population {@code intent_poly_member} unions.
     */
    @Test
    void anInterfaceContainerResolvesItsImplementations() {
        String sdl = """
            interface Node { id: ID! }
            interface Occupant { name: String }
            type Customer implements Node & Occupant @table(name: "customer")
                    @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
                name: String @field(name: "first_name")
            }
            type Staff implements Node & Occupant @table(name: "staff")
                    @node(keyColumns: ["staff_id"]) {
                id: ID! @nodeId
                name: String @field(name: "first_name")
            }
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "Occupant")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "assignOccupant"})
            }
            """.formatted(SERVICE_STUB);
        assertThat(beanLeaf(sdl, false).candidates())
            .extracting(CallSiteExtraction.PolymorphicCandidate::typeName)
            .containsExactly("Customer", "Staff");
    }

    // ===== Refusals =====

    @Test
    void aMemberTypedAsOneCandidatesRecordIsRefused() {
        assertThat(rejection(beanSchema("occupant: ID! @nodeId(typeName: \"AddressOccupant\")",
            "assignOccupantOneMember")))
            .as("names the candidates, the candidate whose record it is, and the shared supertypes")
            .contains("Customer, Staff")
            .contains("CustomerRecord")
            .contains("org.jooq.UpdatableRecord")
            .contains("point typeName: at that type");
    }

    @Test
    void aScalarProducerParameterIsRefused() {
        assertThat(rejection(producerSchema("AddressOccupant", "getFilmsByStringKey")))
            .as("a scalar slot has no column meaning the key across tables and nowhere to carry the type")
            .contains("decodes into a record, not into a column value")
            .contains("org.jooq.UpdatableRecord");
    }

    /**
     * The misleading refusal this item removes: an {@code UpdatableRecord<?>} parameter used to fall
     * through to the one-value projection and be refused by the store on the key's arity, a message
     * about a mistake the author did not make. It now resolves, so the arity verdict cannot fire.
     */
    @Test
    void anUpdatableRecordProducerParameterNoLongerFallsThroughToTheOneValueProjection() {
        assertThat(producerTransform(producerSchema("AddressOccupant", "getOccupantByUpdatableRecord")))
            .isNotInstanceOf(CallSiteExtraction.ThrowOnMismatch.class)
            .isInstanceOf(CallSiteExtraction.NodeIdDecodePolymorphicRecord.class);
    }

    @Test
    void aProducerParameterTypedAsOneCandidatesRecordIsRefused() {
        assertThat(rejection(producerSchema("AddressOccupant", "getOccupantByCustomerRecord")))
            .contains("Customer, Staff")
            .contains("CustomerRecord");
    }

    @Test
    void aContainerWithANonNodeTableMemberIsRefusedNamingIt() {
        String sdl = """
            interface Node { id: ID! }
            type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
            }
            type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
            union AddressOccupant = Customer | Staff
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "AddressOccupant")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "assignOccupant"})
            }
            """.formatted(SERVICE_STUB);
        assertThat(rejection(sdl))
            .as("the member with the hole is named, and so is the remedy")
            .contains("'Staff' is not a @node type")
            .contains("@node");
    }

    @Test
    void aContainerWithNoTableMembersIsRefused() {
        String sdl = """
            interface Node { id: ID! }
            type Plain { name: String }
            type Other { name: String }
            union Anything = Plain | Other
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "Anything")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "assignOccupant"})
            }
            """.formatted(SERVICE_STUB);
        assertThat(rejection(sdl)).contains("no @table implementations");
    }

    @Test
    void aSingleTableDiscriminatedContainerIsRefusedWithBothRemedies() {
        String sdl = """
            interface MediaItem @table(name: "film") @discriminate(on: "text_rating") {
                title: String
            }
            type PgFilm implements MediaItem @table(name: "film") @discriminator(value: "PG") {
                title: String
            }
            type GFilm implements MediaItem @table(name: "film") @discriminator(value: "G") {
                title: String
            }
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "MediaItem")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "assignOccupant"})
            }
            """.formatted(SERVICE_STUB);
        assertThat(rejection(sdl))
            .as("both remedies: name an object type, or make the interface a @node type")
            .contains("single-table container")
            .contains("Name one of its object types instead")
            .contains("a @node type");
    }

    /**
     * jOOQ generates a {@code TableRecordImpl} for a primary-key-less table, so such a candidate's
     * record is not an {@code UpdatableRecord} and the slot has to be one rung wider. The rule is the
     * record class's own declared ancestry rather than a primary-key guess about codegen, which is
     * what this case discriminates: the same schema at {@code TableRecord<?>} resolves.
     */
    @Test
    void aPrimaryKeylessCandidateRefusesAnUpdatableRecordSlotAndAdmitsATableRecordSlot() {
        String sdl = """
            interface Node { id: ID! }
            type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
            }
            type FilmList implements Node @table(name: "film_list") @node(keyColumns: ["title"]) {
                id: ID! @nodeId
            }
            union Listed = Customer | FilmList
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "Listed")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "%s"})
            }
            """;
        assertThat(rejection(sdl.formatted(SERVICE_STUB, "assignOccupant")))
            .as("FilmListRecord is a TableRecordImpl, so an UpdatableRecord<?> slot is refused")
            .contains("FilmListRecord")
            .contains("org.jooq.TableRecord");
        assertThat(beanLeaf(sdl.formatted(SERVICE_STUB, "assignOccupantTableRecord"), false)
            .slotType().typeName().toString())
            .as("one rung wider and the same schema resolves")
            .isEqualTo("org.jooq.TableRecord<?>");
    }

    /**
     * The read side keeps its refusal and says why the coordinate is the wrong one. The store states
     * the same fact as {@code CONTAINER_NOT_AT_A_SLOT}, whose rows are pinned in the fact-store tier;
     * what this case pins is the wording that is supposed to agree with them, so the author who wrote
     * the container one coordinate too far out reads the remedy rather than "is not @table-annotated",
     * a mistake they did not make.
     */
    @Test
    void aContainerAtAReadSideArgumentIsRefusedForTheCoordinateRatherThanForItsKind() {
        String sdl = OCCUPANTS + """
            type Query {
                customers(occupantId: ID @nodeId(typeName: "AddressOccupant")): [Customer!]!
            }
            """;
        var field = (GraphitronField.UnclassifiedField)
            TestSchemaHelper.buildSchema(sdl).field("Query", "customers");
        assertThat(field.rejection().message())
            .as("the coordinate is named, and so is where the polymorphic spelling belongs")
            .contains("names a polymorphic container")
            .contains("occupantId")
            .contains("@service")
            .doesNotContain("is not @table-annotated");
    }

    /**
     * The reorder is answer-preserving: every named type that is neither a container nor a
     * {@code @table} object still meets the single-type path's own refusal, with its own wording. The
     * container arm sits ahead of that test now, so this is what says it did not swallow a shape.
     *
     * <p>{@code @node} is declared {@code on OBJECT}, so the one shape the reorder actually moves, a
     * container carrying {@code @node} itself, cannot be spelled in SDL at all; the ordering rule
     * stands as the walk's spelling of the store's disjointness predicate rather than as a live fork,
     * and closing that gap is the pre-existing question the roadmap item reports.
     */
    @Test
    void aNamedTypeThatIsNoContainerStillMeetsTheSingleTypeRefusal() {
        String sdl = """
            interface Node { id: ID! }
            type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
            }
            type Plain { name: String }
            input AssignOccupantInput {
                occupant: ID! @nodeId(typeName: "Plain")
            }
            type Query {
                assignOccupant(in: AssignOccupantInput!): String
                    @service(service: {className: "%s", method: "assignOccupant"})
            }
            """.formatted(SERVICE_STUB);
        assertThat(rejection(sdl))
            .as("the single-type path's own message, unchanged by the reorder")
            .contains("is not @table-annotated");
    }

    // ===== Helpers =====

    /** The polymorphic leaf on the one input-bean member of {@code Query.assignOccupant}. */
    private static CallSiteExtraction.NodeIdDecodePolymorphicRecord beanLeaf(String sdl, boolean list) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "assignOccupant");
        if (field instanceof GraphitronField.UnclassifiedField unclassified) {
            throw new AssertionError("the schema did not build: " + unclassified.rejection().message());
        }
        var bean = ((ServiceField) field).serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg fa && fa.shape() instanceof ValueShape.RecordInput)
            .map(e -> (ValueShape.RecordInput) ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no record-input bean arg on Query.assignOccupant"));
        ValueShape memberShape = bean.fields().getFirst().shape();
        ValueShape elementShape = list ? ((ValueShape.ListOf) memberShape).elementShape() : memberShape;
        return (CallSiteExtraction.NodeIdDecodePolymorphicRecord)
            ((ValueShape.Scalar) elementShape).leafTransform();
    }

    /** The transform on the one argument-fed parameter of {@code Query.films}. */
    private static CallSiteExtraction producerTransform(String sdl) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "films");
        if (field instanceof GraphitronField.UnclassifiedField unclassified) {
            throw new AssertionError("the schema did not build: " + unclassified.rejection().message());
        }
        var entries = ((ServiceField) field).serviceMethodCall().methodArgs();
        assertThat(entries).singleElement().isInstanceOf(MappingEntry.FromArg.class);
        var shape = ((MappingEntry.FromArg) entries.getFirst()).shape();
        return ((ValueShape.Scalar) shape).leafTransform();
    }

    private static CallSiteExtraction.NodeIdDecodePolymorphicRecord producerLeaf(String sdl) {
        return (CallSiteExtraction.NodeIdDecodePolymorphicRecord) producerTransform(sdl);
    }

    /** The rejection message of whichever of the two {@code @service} coordinates the SDL declares. */
    private static String rejection(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        var field = schema.field("Query", "assignOccupant") != null
                && !(schema.field("Query", "assignOccupant") instanceof ServiceField)
            ? schema.field("Query", "assignOccupant")
            : schema.field("Query", "films");
        assertThat(field)
            .as("a refused schema classifies the coordinate as unclassified")
            .isInstanceOf(GraphitronField.UnclassifiedField.class);
        return ((GraphitronField.UnclassifiedField) field).rejection().message();
    }

    private static TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE)
            .stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + name + " on " + spec.name()));
    }
}
