package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.model.test.CatalogInput;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.derive.NodeIdDecodeDefects;
import no.sikt.graphitron.model.derive.NodeIdPolymorphicDecodeDefects;

/**
 * The store-backed home of the {@code @nodeId} decode rules at a producer parameter: real SDL
 * captured into a fact store, and the violations {@link NodeIdDecodeDefects} projects from
 * the entry defects at the {@code @nodeId}. This is the tier that says an author's schema reaches
 * the rule in the shape it reads, and that the report a consumer meets is minted from what it
 * finds.
 *
 * <p>What the rule decides is not asked here. That is the rule's own algebra, its three verdicts
 * and the populations it excludes, and it lives in the module whose DDL declares it, in the
 * {@code node-id-decode-defect-slot-verdicts} document under graphitron-model's {@code src/test/resources/facts}. What stands here is the decode: which {@link Rejection} arm each verdict becomes, the prose it
 * carries, the location it points at, and the population this consumer asks about.
 *
 * <p>Every fixture below spells a schema that used to compile. An author annotated an argument
 * {@code @nodeId}, the walk's type gate stood aside for the reason it documents, and the opaque wire
 * string then reached the parameter with nothing in the build saying a word. The cases that draw no
 * violation are here for the same reason: this family strictly adds refusals, so a decode that was
 * being carried out has to still be carried out.
 *
 * <p>Two capture inputs the SDL-only fixtures elsewhere do without are load-bearing here, because
 * the rules read both corpora. The jOOQ catalog is what types a key column, and the classpath census
 * is what types a parameter, so a fixture missing either would assert a stand-aside it did not mean
 * to construct.
 */
@PipelineTier
class NodeIdDecodeDefectsTest {

    private static final String GRAPH = CapturedStore.GRAPH;
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.PublicNodeIdServiceStub";

    /**
     * The catalog, loaded once for the class. The classpath is the module's own test classes rather
     * than the reactor's main ones, that being where the stub these fixtures name lives.
     */
    private static CatalogInput jooq;

    @BeforeAll
    static void loadTheCatalog() {
        var ctx = TestConfiguration.testContext();
        jooq = CatalogInput.of(ctx);
    }

    @TempDir
    Path tmp;

    // ===== The three verdicts =====

    /**
     * A parameter typed as the wire format at a node type keyed on one column: the shape that used to
     * hand a service method the base64 string. The message names both types and the column, because
     * without both it would say a correctly named parameter is wrong.
     */
    @Test
    void aParameterThatCannotTakeTheSoleKeyColumnIsRejectedNamingBothTypes() {
        var violations = detect(schema("Film", "getFilmsByStringKey"));

        assertThat(messages(violations)).containsExactly(
            "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Film\") and the"
            + " producer method declares a parameter 'key' of that name, so the decoded key lands"
            + " there, and its key column 'film_id' jOOQ binds as Integer, but 'key' takes String;"
            + " declare the parameter with the column's own type");
        assertThat(violations.getFirst().rejection())
            .isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(violations.getFirst().location()).isNotNull();
        assertThat(violations.getFirst().location().getSourceName()).endsWith("fixture.graphqls");
    }

    /**
     * A composite key at a parameter holding one value. The count is the whole of what is wrong, so
     * the message quotes it and offers both remedies: the node type's own record takes the tuple, and
     * an {@code argMapping} entry takes one column of it, which is why the columns are named.
     */
    @Test
    void aCompositeKeyAtASingleValuedParameterIsRejectedNamingTheCountAndTheColumns() {
        assertThat(messages(detect(schema("Inventory", "getFilmsByStringKey")))).containsExactly(
            "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Inventory\") and the"
            + " producer method declares a parameter 'key' of that name, so the decoded key lands"
            + " there, but that key is 2 columns and one parameter takes one value; declare 'key' as"
            + " InventoryRecord, or as Record2<Integer, Integer> or Row2<Integer, Integer>, to receive"
            + " the whole tuple, or bind one of its key columns to a parameter with argMapping:"
            + " inventory_id, store_id");
    }

    /**
     * A jOOQ tuple of the key's degree is the key only where its types are the key columns' in key
     * order, so one typed otherwise is refused naming the columns, their types and the tuple wanted.
     */
    @Test
    void aTupleTypedOtherwiseThanTheKeyIsRejectedNamingTheColumnsAndTheirTypes() {
        assertThat(messages(detect(schema("Inventory", "getFilmsByMistypedTuple")))).containsExactly(
            "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Inventory\") and the"
            + " producer method declares a parameter 'key' of that name, so the decoded key lands"
            + " there, and its key columns inventory_id, store_id jOOQ binds as Integer, Integer in key"
            + " order, but 'key', a Row2, is typed otherwise; declare 'key' as Row2<Integer, Integer>");
    }

    /**
     * A list argument at a {@code Set} of the very record that would take each id. The element is
     * right and the container is not: the list decode hands over a {@code java.util.List}, so the
     * message names the container the parameter declares and the one it needs.
     */
    @Test
    void aSetParameterAtAListArgumentIsRefusedNamingTheList() {
        assertThat(messages(detect(listSchema("Inventory", "getFilmsByInventoryKeySet"))))
            .containsExactly(
                "Field 'Query.films': argument 'key' carries the @nodeId(typeName: \"Inventory\") and"
                + " the producer method declares a parameter 'key' of that name, so the decoded key"
                + " lands there, and the argument is a list, so the decoded ids are handed over as a"
                + " java.util.List, but 'key' takes Set; declare 'key' as List<InventoryRecord>");
    }

    // ===== What the arms deliberately leave alone =====

    /**
     * The parameter typed as the key column jOOQ binds it as, which is the schema an author writes
     * after reading either message above. Nothing to report, and asserted as an empty report rather
     * than as an absent verdict, because a family that adds refusals must add none here.
     */
    @Test
    void aParameterOfTheKeyColumnsOwnTypeIsNoDefect() {
        assertThat(detect(schema("Film", "getFilmsByIntegerKey")))
            .as("the parameter takes exactly what the sole key column binds as")
            .isEmpty();
    }

    /**
     * The composite key's other remedy: a parameter typed as the node type's own generated record
     * takes the whole tuple, so the arity that refuses a single-valued parameter refuses nothing
     * here. The same two key columns as the arity case above, which is what makes this the remedy for
     * it rather than a different schema.
     */
    @Test
    void aParameterTypedAsTheNodeTypesRecordTakesACompositeKey() {
        assertThat(detect(schema("Inventory", "getFilmsByInventoryKey")))
            .as("a record holds the tuple whatever the key's arity")
            .isEmpty();
    }

    /**
     * A primitive parameter at a one-column key: the type verdict needs both operands and the census
     * reads no class at that position, so nothing is refused and the decode is carried out on arity
     * alone with javac as the backstop. Refusing here would open a second silence rather than close
     * the one this family exists for.
     */
    @Test
    void aParameterNoCensusCanTypeDrawsNoTypeVerdict() {
        assertThat(detect(schema("Film", "getFilmsByPrimitiveKey")))
            .as("a refusal names its operands, and one of these two is unreadable")
            .isEmpty();
    }

    /**
     * The same untypeable parameter at a composite key still refuses, which is the one place the
     * stand-aside rule had to be read rather than copied. What the arity verdict needs is whether the
     * parameter is the tuple's own row type, and a position naming no class is a primitive or a type
     * variable, neither of which is a generated record. So the absence answers the question instead
     * of leaving it unanswered.
     */
    @Test
    void anUntypeableParameterAtACompositeKeyIsStillRefusedOnTheArity() {
        assertThat(messages(detect(schema("Inventory", "getFilmsByPrimitiveKey"))))
            .singleElement(InstanceOfAssertFactories.STRING)
            .contains("that key is 2 columns and one parameter takes one value");
    }

    /**
     * A list argument at a {@code java.util.List} of the node type's own record: each id lands on one
     * element, and the element takes the whole tuple. The shape the store used to refuse by
     * comparing the container with the record.
     */
    @Test
    void aListOfTheNodeTypesRecordTakesACompositeKey() {
        assertThat(detect(listSchema("Inventory", "getFilmsByInventoryKeys")))
            .as("the element is the record, so the arity refuses nothing")
            .isEmpty();
    }

    /** The same at one key column, the element typed as the column jOOQ binds. */
    @Test
    void aListOfTheKeyColumnsOwnTypeIsNoDefect() {
        assertThat(detect(listSchema("Film", "getFilmsByIntegerKeys")))
            .as("each element takes exactly what the sole key column binds as")
            .isEmpty();
    }

    // ===== The polymorphic sibling, over the same captured store =====

    /**
     * A list argument naming a union at a {@code java.util.List} of a record supertype every member's
     * record is: the store half of the admitted polymorphic list shape. Neither view refuses it.
     */
    @Test
    void aListArgumentAtAListOfARecordSupertypeIsNoDefect() {
        String sdl = occupantSchema("[ID!]!", "getOccupantsByUpdatableRecords");
        assertThat(detectPolymorphic(sdl)).as("the polymorphic view").isEmpty();
        assertThat(detect(sdl)).as("and the node-type view").isEmpty();
    }

    /**
     * One id at that same list parameter: the generator classifies the shape and picks the list
     * helper, and the store refuses it by name before emission, on the polymorphic view's own shape
     * verdict and not on the members, so the asymmetry cannot drift into a silent pass.
     */
    @Test
    void aSingleIdAtAListOfARecordSupertypeIsAShapeMismatch() {
        String sdl = occupantSchema("ID!", "getOccupantsByUpdatableRecords");
        assertThat(messages(detectPolymorphic(sdl))).containsExactly(
            "Field 'Query.films': @nodeId(typeName: \"AddressOccupant\") names a union, but parameter"
            + " 'key' takes List, which a list argument fills with one decoded record per id. Declare"
            + " the parameter as a type every implementation's record is (org.jooq.Record,"
            + " TableRecord<?>, or UpdatableRecord<?> where every implementation's table has a"
            + " primary key), or make the argument a list");
        assertThat(detect(sdl)).as("the node-type view stays disjoint").isEmpty();
    }

    // ===== The population this consumer asks about =====

    /**
     * The store judges every occurrence it holds, and holds one for a field no root operation
     * reaches, so the same refusal there fails the build: a decode that cannot be carried out is
     * refused wherever it is written.
     */
    @Test
    void aRefusalOutsideTheClassificationDomainIsStillRefused() {
        assertThat(messages(detect("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query { film: Film }
            type Orphan {
                films(key: ID! @nodeId(typeName: "Film")): [Film!]!
                    @service(service: {className: "%s", method: "getFilmsByStringKey"})
            }
            """.formatted(SERVICE_STUB))))
            .as("the store holds Orphan's occurrence, so the refusal stands there too")
            .containsExactly("Field 'Orphan.films': argument 'key' carries the @nodeId(typeName:"
                + " \"Film\") and the producer method declares a parameter 'key' of that name, so the"
                + " decoded key lands there, and its key column 'film_id' jOOQ binds as Integer, but"
                + " 'key' takes String; declare the parameter with the column's own type");
    }

    // ===== Helpers =====

    /**
     * One schema shape parameterised by the two things the fixtures vary: which node type the
     * argument decodes against, which fixes the key's arity, and which stub method receives it, which
     * fixes the parameter's type. Held as one template because the cases are a matrix over those two
     * and a case that differed in a third thing would not be comparing what it claims to.
     */
    private static String schema(String nodeType, String method) {
        return """
            interface Node { id: ID! }
            type Inventory implements Node @table(name: "inventory")
                    @node(keyColumns: ["inventory_id", "store_id"]) {
                id: ID! @nodeId
            }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query {
                inventory: Inventory
                film: Film
                films(key: ID! @nodeId(typeName: "%s")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(nodeType, SERVICE_STUB, method);
    }

    /** The same template with the argument a list, the shape a {@code java.util.List} parameter takes. */
    private static String listSchema(String nodeType, String method) {
        return schema(nodeType, method).replace("films(key: ID!", "films(key: [ID!]!");
    }

    /**
     * The container sibling of the template: the {@code AddressOccupant} union over two node types,
     * as {@code PolymorphicNodeIdSlotPipelineTest} declares it, with the argument's type the case's
     * to state.
     */
    private static String occupantSchema(String argumentType, String method) {
        return """
            interface Node { id: ID! }
            type Customer implements Node @table(name: "customer") @node(keyColumns: ["customer_id"]) {
                id: ID! @nodeId
            }
            type Staff implements Node @table(name: "staff") @node(keyColumns: ["staff_id"]) {
                id: ID! @nodeId
            }
            union AddressOccupant = Customer | Staff
            type Film @table(name: "film") { title: String }
            type Query {
                films(key: %s @nodeId(typeName: "AddressOccupant")): [Film!]!
                    @service(service: {className: "%s", method: "%s"})
            }
            """.formatted(argumentType, SERVICE_STUB, method);
    }

    /** Captures {@code sdl} against both corpora and runs the polymorphic sibling's detection. */
    private List<ValidationError> detectPolymorphic(String sdl) {
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, jooq,
                TestSchemaHelper.testClasspath())) {
            return NodeIdPolymorphicDecodeDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** Captures {@code sdl} against both corpora and runs the detection over what capture wrote. */
    private List<ValidationError> detect(String sdl) {
        try (var store = CapturedStore.ofCatalogWith(tmp, GRAPH, sdl, jooq,
                TestSchemaHelper.testClasspath())) {
            return NodeIdDecodeDefects.detect(store.dsl(), GRAPH).violations();
        }
    }

    /** The violations' messages, the surface an author actually meets. */
    private static List<String> messages(List<ValidationError> violations) {
        return violations.stream().map(ValidationError::message).toList();
    }
}
