package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decode at a producer slot: a {@code @nodeId} argument whose value a {@code @service} method
 * parameter receives, matched by the parameter's own name. The subject is the resolution rather than
 * a refusal, which is what makes these cases shaped the way they are: each asserts that the schema
 * <em>builds</em> and that the slot's transform is the decode, because silence at a detection and a
 * red build are indistinguishable at every tier that only asks whether some family reported nothing.
 *
 * <p>Every schema here used to fail. The argument's coercion output is a {@code String} and the
 * parameter takes the key column's own type, so the wire-coercion gate refused the two signatures the
 * refusals themselves prescribe, and the one signature it admitted was the one that received the
 * base64. The gate now stands aside on a {@code @nodeId} leaf and the decode is minted in its place,
 * which is the invariant this directive exists to keep: a consumer neither receives nor supplies the
 * wire format.
 *
 * <p>Both authored spellings are here, and the bare one for the reason the explicit one is: it used
 * to fall through to the same gate, so the author who wrote {@code @nodeId} with no
 * {@code typeName:} was left with the signature that receives the base64 and a refusal prescribing
 * the decode already written. The bare form inherits its target from the table the consuming field's
 * own return type binds, which is the rule the fact model states for it, so the two spellings resolve
 * to one decode and the inference's two absences are refusals naming {@code typeName:}.
 *
 * <p>What the store still owns is unchanged and not re-asserted here: whether the key column's type
 * and the parameter's agree, and whether the key's arity fits a slot holding one value, both being
 * facts the walk cannot read before capture. Those live in
 * {@link no.sikt.graphitron.rewrite.derive.NodeIdDecodeDefectsTest}.
 */
@PipelineTier
class NodeIdProducerSlotDecodePipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.PublicNodeIdServiceStub";
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";

    @Test
    void aParameterOfTheKeyColumnsOwnTypeReceivesTheDecodedColumn() {
        var leaf = slotTransform(schema("Film", "getFilmsByIntegerKey"));

        assertThat(leaf)
            .as("a one-column key at a single-valued parameter decodes to that column's value")
            .isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
        var decode = ((CallSiteExtraction.NodeIdDecodeKeys) leaf).decodeMethod();
        assertThat(decode.methodName()).isEqualTo("decodeFilm");
        assertThat(decode.outputColumnShape()).extracting(ColumnRef::sqlName)
            .containsExactly("film_id");
    }

    @Test
    void aParameterTypedAsTheNodeTypesRecordReceivesTheWholeTuple() {
        var leaf = slotTransform(schema("Inventory", "getFilmsByInventoryKey"));

        assertThat(leaf)
            .as("the record slot takes the tuple, so the arity that refuses one value refuses nothing")
            .isInstanceOf(CallSiteExtraction.NodeIdDecodeRecord.class);
        var rec = (CallSiteExtraction.NodeIdDecodeRecord) leaf;
        assertThat(rec.typeId()).isEqualTo("Inventory");
        assertThat(rec.keyColumns()).extracting(ColumnRef::sqlName)
            .containsExactly("inventory_id", "store_id");
        assertThat(rec.table().recordClass().simpleName()).isEqualTo("InventoryRecord");
    }

    /**
     * The parameter typed as the wire format still classifies, and it classifies to the decode rather
     * than to a pass-through. The build is red here, on the store's type verdict, and that is the
     * point: what the walk must not do is resolve this coordinate as the base64 it used to hand over.
     */
    @Test
    void aParameterTypedAsTheWireFormatStillGetsTheDecodeAndNotThePassThrough() {
        assertThat(slotTransform(schema("Film", "getFilmsByStringKey")))
            .isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
    }

    /**
     * An untypeable parameter is not a reason to withhold the decode. The refusal needs two known
     * types and the census reads no class at a primitive, so the decode is emitted on the key's arity
     * alone with {@code javac} as the backstop; withholding it would reopen the silence rather than
     * closing it.
     */
    @Test
    void aParameterNoCensusCanTypeStillReceivesTheDecode() {
        assertThat(slotTransform(schema("Film", "getFilmsByPrimitiveKey")))
            .isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
    }

    /**
     * The bare spelling of the same directive resolves the same way. There is no {@code typeName:} to
     * read, so the target is inherited from the table the consuming field's own return type binds,
     * which is the rule the fact model states as its {@code TARGET_TABLE_NODE_TYPE} basis. Both
     * spellings therefore reach the decode, which is what "one rule, two spellings" has to mean here:
     * a bare directive used to fall through to the wire-coercion gate, whose admitted signature took
     * the base64 and whose refused one drew a message prescribing the decode already written.
     */
    @Test
    void aBareDirectiveInheritsItsTargetFromTheFieldsOwnReturnTable() {
        var leaf = slotTransform("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query {
                film: Film
                films(key: ID! @nodeId): [Film!]!
                    @service(service: {className: "%s", method: "getFilmsByIntegerKey"})
            }
            """.formatted(STUB));

        assertThat(leaf).isInstanceOf(CallSiteExtraction.ThrowOnMismatch.class);
        var decode = ((CallSiteExtraction.NodeIdDecodeKeys) leaf).decodeMethod();
        assertThat(decode.methodName()).isEqualTo("decodeFilm");
        assertThat(decode.outputColumnShape()).extracting(ColumnRef::sqlName)
            .containsExactly("film_id");
    }

    /**
     * The inference's ambiguity, at this coordinate. Two node types over the return type's table are
     * two different key tuples, so the answer is the one the author has to give rather than a pick,
     * and the message names {@code typeName:} as the way to give it.
     */
    @Test
    void aBareDirectiveOverATableTwoNodeTypesShareNamesTypeNameAsTheFix() {
        var field = TestSchemaHelper.buildSchema("""
            interface Node { id: ID! }
            type FilmA implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type FilmB implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
            }
            type Query {
                filmB: FilmB
                films(key: ID! @nodeId): [FilmA!]!
                    @service(service: {className: "%s", method: "getFilmsByIntegerKey"})
            }
            """.formatted(STUB)).field("Query", "films");

        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).rejection().message())
            .contains("is ambiguous")
            .contains("FilmA, FilmB")
            .contains("Specify typeName: explicitly");
    }

    /**
     * The third absence, and the one that is this coordinate's own rather than the inference's: the
     * consuming field returns a scalar, so there is no table to inherit a target from. A refusal
     * rather than a fall-through, because the directive is written and the gate below would answer it
     * by prescribing the decode the schema already asked for.
     */
    @Test
    void aBareDirectiveOnAFieldReturningNoTableIsRefusedRatherThanHandedTheOpaqueId() {
        var field = TestSchemaHelper.buildSchema("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            type Query {
                film: Film
                title(key: ID! @nodeId): String
                    @service(service: {className: "%s", method: "getTitleByStringKey"})
            }
            """.formatted(STUB)).field("Query", "title");

        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).rejection().message())
            .contains("cannot infer node type")
            .contains("binds no table to inherit a target from")
            .contains("Add typeName: explicitly");
    }

    /**
     * Non-regression on the gate that used to answer here: a parameter bound to an argument carrying
     * no {@code @nodeId} still goes through the wire-coercion check and still lands on
     * {@code Direct}. The stand-aside is keyed on the directive and not on the site.
     */
    @Test
    void anArgumentWithoutTheDirectiveKeepsItsWireCoercionCheckedDirectRead() {
        var leaf = slotTransform("""
            type Film @table(name: "film") {
                title: String
            }
            type Query {
                films(key: String): [Film!]!
                    @service(service: {className: "%s", method: "getFilmsByStringKey"})
            }
            """.formatted(STUB));

        assertThat(leaf).isInstanceOf(CallSiteExtraction.Direct.class);
    }

    /**
     * The other slot coordinate, one step inside a value handed to a parameter: a {@code @nodeId}
     * member of a consumer bean whose Java type is not a jOOQ record. The decode does not emit there
     * yet, and the coordinate is refused as deferred rather than resolved, which is the whole of what
     * changed: the member used to reach {@code Direct} and hand the bean the opaque id with nothing in
     * the build saying so. The message carries both remedies, because an author meeting it has two.
     */
    @Test
    void aSingleValuedBeanMemberIsDeferredRatherThanHandedTheOpaqueId() {
        var field = TestSchemaHelper.buildSchema("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            input TestInputBean {
                title: ID @nodeId(typeName: "Film")
                rating: TestInputBeanEnum
                nested: [TestInputNested!]
            }
            input TestInputNested { name: String }
            enum TestInputBeanEnum { G PG }
            type Query {
                film: Film
                run(input: TestInputBean): String
                    @service(service: {className: "%s", method: "runWithInputBean"})
            }
            """.formatted(SERVICE_STUB)).field("Query", "run");

        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        var rejection = ((GraphitronField.UnclassifiedField) field).rejection();
        assertThat(rejection)
            .as("an owed emitter, not an author error: the schema is one graphitron means to support")
            .isInstanceOf(Rejection.Deferred.class);
        assertThat(rejection.message())
            .contains("carries @nodeId")
            .contains("does not emit yet")
            .contains("generated record of that node type's own table")
            .contains("the producer's own parameter");
    }

    // ===== Helpers =====

    /**
     * The same schema shape {@code NodeIdDecodeDefectsTest} uses, so a case here and a case there
     * differ in what they assert about one coordinate rather than in which coordinate they are about.
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
            """.formatted(nodeType, STUB, method);
    }

    /**
     * The transform on the one argument-fed parameter of {@code Query.films}. Asserts the field
     * classified on the way through, so a case reading a transform can never be reading one off a
     * schema that failed to build: an {@code UnclassifiedField} fails here with its own rejection in
     * the message rather than as a cast error further down.
     */
    private static CallSiteExtraction slotTransform(String sdl) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "films");
        if (field instanceof GraphitronField.UnclassifiedField unclassified) {
            throw new AssertionError("the schema did not build: " + unclassified.rejection().message());
        }
        var entries = ((ServiceField) field).serviceMethodCall().methodArgs();
        assertThat(entries).singleElement().isInstanceOf(MappingEntry.FromArg.class);
        var shape = ((MappingEntry.FromArg) entries.getFirst()).shape();
        assertThat(shape).isInstanceOf(ValueShape.Scalar.class);
        return ((ValueShape.Scalar) shape).leafTransform();
    }
}
