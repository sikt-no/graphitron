package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R195 pipeline tier: a {@code @service} input bean whose member is a jOOQ {@code *Record} backed by
 * an {@code ID! @nodeId(typeName:)} SDL field is decoded into the record via a generated
 * {@code decode<Record>} helper, never cast from the wire {@code String}. The rejection half pins
 * that a record-typed member without a handled decode strategy fails the build with a named
 * {@code Rejection} rather than silently falling through to {@code Direct} (the R150/R195
 * {@code ClassCastException}).
 *
 * <p>Covers every record-member shape: single-column key ({@code FilmRecord}, PK {@code film_id}),
 * composite key ({@code FilmActorRecord}, PK {@code (actor_id, film_id)}), and both as scalar and
 * list-valued members (the {@code [ID!] @nodeId} → {@code List<…Record>} variant), including the
 * {@code List<FilmActorRecord>} corner that exercises both dimensions at once. Backed by the real
 * test jOOQ records and the {@code TestNodeId*Bean} fixtures; the service methods are
 * {@code TestServiceStub.assignFilm} / {@code assignFilmActor} / {@code assignFilmList} /
 * {@code assignFilmActorList}.
 */
@PipelineTier
class NodeIdRecordInputBeanPipelineTest {

    private static final String HAPPY_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input AssignFilmInput {
            film: ID! @nodeId(typeName: "Film")
        }
        type Query {
            assignFilm(in: AssignFilmInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilm"})
        }
        """;

    private static final String RECORD_TYPE_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";

    @Test
    void recordMember_withNodeId_emitsDecodeHelperOnFetchersClass() {
        // The decode helper's presence is the structural signal that the record member classified to
        // a NodeIdDecodeRecord leaf rather than a Direct (FilmRecord) raw.get(...) cast: Direct emits
        // an inline cast and no helper, so a decode<Record> on the class means no R150/R195 CCE.
        var fetchers = findSpec("QueryFetchers", HAPPY_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the create<Bean> helper and the per-record decode<Record> helper both land on the class")
            .contains("createTestNodeIdRecordBean", "decodeFilmRecord");
    }

    @Test
    void recordMember_classifiesToNodeIdDecodeRecordLeaf_notDirect() {
        // SDL → classified model: the single-key scalar record member resolves to a NodeIdDecodeRecord
        // leaf (not a Direct (FilmRecord) raw.get(...) cast — the R150/R195 CCE), carrying the typeId,
        // single key column, target record type, and SDL non-nullability the emitter materialises from.
        var leaf = decodeRecordLeaf(HAPPY_SDL, "assignFilm", false);
        assertThat(leaf.typeId()).as("typeId resolved from @nodeId(typeName:)").isEqualTo("Film");
        assertThat(leaf.keyColumns()).as("single-PK arity").hasSize(1);
        assertThat(leaf.keyColumns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(leaf.table().recordClass().toString()).isEqualTo(RECORD_TYPE_FQN);
        assertThat(leaf.nonNull()).as("ID! is non-null").isTrue();
    }

    @Test
    void decodeHelper_returnsRecordType_takesObjectWire() {
        var decode = method(findSpec("QueryFetchers", HAPPY_SDL), "decodeFilmRecord");
        assertThat(decode.returnType().toString()).isEqualTo(RECORD_TYPE_FQN);
        assertThat(decode.parameters()).hasSize(1);
        assertThat(decode.parameters().get(0).type().toString()).isEqualTo("java.lang.Object");
    }

    @Test
    void decodeHelper_carriesNoSuppressWarnings() {
        // fromArray is the supported, non-deprecated coercion path, so the helper needs no
        // @SuppressWarnings: a deprecation/removal suppression on a helper that lands in the
        // consumer's *Fetchers package would only hide a future hard compile break. Structural
        // assertion on the MethodSpec's annotation list — not on its body. The warning-clean
        // outcome is enforced for real by the graphitron-sakila-example compile tier.
        var decode = method(findSpec("QueryFetchers", HAPPY_SDL), "decodeFilmRecord");
        assertThat(decode.annotations())
            .as("the decode helper emits no @SuppressWarnings — fromArray needs no deprecation suppression")
            .isEmpty();
    }

    @Test
    void recordMember_withoutNodeId_rejectsAtGenerationTime() {
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            input AssignFilmInput {
                film: ID!
            }
            type Query {
                assignFilm(in: AssignFilmInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilm"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "assignFilm");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).rejection().message())
            .as("the rejection names the field, the record type, and the @nodeId remedy")
            .contains("film")
            .contains(RECORD_TYPE_FQN)
            .contains("@nodeId(typeName:)");
    }

    @Test
    void recordMember_typeMismatchesNodeIdTypeName_rejectsAtGenerationTime() {
        // The bean member is a FilmActorRecord, but @nodeId(typeName: "Film") decodes into a
        // FilmRecord (Film's own @table). A NodeId cannot be loaded into a different record type;
        // the classifier rejects this loudly instead of emitting a decode helper whose return type
        // mismatches the bean field (the downstream javac "incompatible types" the gate replaces).
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! title: String }
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
            input AssignMismatchedInput {
                film: ID! @nodeId(typeName: "Film")
            }
            type Query {
                assignMismatched(in: AssignMismatchedInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignMismatchedRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "assignMismatched");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).rejection().message())
            .as("the rejection names the declared record type, the node-table record, and the typeName,"
                + " and points at both remedies — never a silent fall-through")
            .contains("FilmActorRecord")
            .contains("test.jooq.tables.records.FilmRecord")
            .contains("@nodeId(typeName: \"Film\")");
    }

    private static final String COMPOSITE_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        input AssignFilmActorInput {
            filmActor: ID! @nodeId(typeName: "FilmActor")
        }
        type Query {
            assignFilmActor(in: AssignFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActor"})
        }
        """;

    private static final String LIST_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input AssignFilmListInput {
            films: [ID!] @nodeId(typeName: "Film")
        }
        type Query {
            assignFilmList(in: AssignFilmListInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmList"})
        }
        """;

    private static final String LIST_COMPOSITE_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        input AssignFilmActorListInput {
            filmActors: [ID!] @nodeId(typeName: "FilmActor")
        }
        type Query {
            assignFilmActorList(in: AssignFilmActorListInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActorList"})
        }
        """;

    @Test
    void compositeKeyRecordMember_emitsCompositeDecodeHelper() {
        var fetchers = findSpec("QueryFetchers", COMPOSITE_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the composite-key member resolves to a decode helper, not a 'not yet supported' rejection")
            .contains("createTestNodeIdCompositeRecordBean", "decodeFilmActorRecord");
        var leaf = decodeRecordLeaf(COMPOSITE_SDL, "assignFilmActor", false);
        assertThat(leaf.typeId()).isEqualTo("FilmActor");
        assertThat(leaf.keyColumns())
            .as("a composite-PK member carries both key columns (arity is the resolved key-column count)")
            .extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
    }

    @Test
    void listRecordMember_emitsListDecodeHelper() {
        var fetchers = findSpec("QueryFetchers", LIST_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("a list-valued record member emits the list variant plus the scalar helper it delegates to")
            .contains("createTestNodeIdRecordListBean", "decodeFilmRecordList", "decodeFilmRecord");
        assertThat(method(fetchers, "decodeFilmRecordList").returnType().toString())
            .as("the list helper returns List<FilmRecord>")
            .isEqualTo("java.util.List<" + RECORD_TYPE_FQN + ">");
        // SDL → model: list-ness is the member shape being ListOf; the per-element leaf is the same
        // single-key NodeIdDecodeRecord the scalar helper materialises.
        var leaf = decodeRecordLeaf(LIST_SDL, "assignFilmList", true);
        assertThat(leaf.typeId()).isEqualTo("Film");
        assertThat(leaf.keyColumns()).hasSize(1);
    }

    @Test
    void listOfCompositeRecordMember_exercisesBothDimensions() {
        var fetchers = findSpec("QueryFetchers", LIST_COMPOSITE_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the both-dimensions corner: a list variant over a composite-key per-element decode")
            .contains("createTestNodeIdCompositeRecordListBean",
                "decodeFilmActorRecordList", "decodeFilmActorRecord");
        // SDL → model: both dimensions on one leaf — list shape + composite-key arity.
        var leaf = decodeRecordLeaf(LIST_COMPOSITE_SDL, "assignFilmActorList", true);
        assertThat(leaf.typeId()).isEqualTo("FilmActor");
        assertThat(leaf.keyColumns()).hasSize(2);
    }

    // ===== Helpers =====

    /**
     * Navigates the classified model from a {@code @service} Query field down to the
     * {@link CallSiteExtraction.NodeIdDecodeRecord} leaf of its single input-bean record member:
     * {@code field → serviceMethodCall → the record-bean arg → its one field → (list element →) the
     * Scalar leafTransform}. Asserting the leaf is a {@code NodeIdDecodeRecord} (the cast) is itself
     * the structural pin that the member did not fall through to {@code Direct}.
     */
    private static CallSiteExtraction.NodeIdDecodeRecord decodeRecordLeaf(
            String sdl, String queryField, boolean list) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", queryField);
        var bean = ((ServiceField) field).serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg fa && fa.shape() instanceof ValueShape.RecordInput)
            .map(e -> (ValueShape.RecordInput) ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no record-input bean arg on " + queryField));
        ValueShape memberShape = bean.fields().get(0).shape();
        ValueShape elementShape = list ? ((ValueShape.ListOf) memberShape).elementShape() : memberShape;
        return (CallSiteExtraction.NodeIdDecodeRecord) ((ValueShape.Scalar) elementShape).leafTransform();
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
