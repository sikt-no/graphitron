package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TypeFetcherRenderTestSupport;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.render.CatalogRefs;
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
 * Pipeline tier: a {@code @service} input bean whose member is a jOOQ {@code *Record} backed by
 * an {@code ID! @nodeId(typeName:)} SDL field is decoded into the record via a generated
 * {@code decode<TypeName>Record} helper, never cast from the wire {@code String}. The rejection half pins
 * that a record-typed member without a handled decode strategy fails the build with a named
 * {@code Rejection} rather than silently falling through to {@code Direct} (the
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

    private static final String COMPOSITE_RECORD_TYPE_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord";

    @Test
    void recordMember_withNodeId_emitsDecodeHelperOnFetchersClass() {
        // The decode helper's presence is the structural signal that the record member classified to
        // a NodeIdDecodeRecord leaf rather than a Direct (FilmRecord) raw.get(...) cast: Direct emits
        // an inline cast and no helper, so a decode<TypeName>Record on the class means no CCE.
        var fetchers = findSpec("QueryFetchers", HAPPY_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the create<Bean> helper and the per-node-type decode<TypeName>Record helper both land on the class")
            .contains("createTestNodeIdRecordBean", "decodeFilmRecord");
    }

    @Test
    void recordMember_classifiesToNodeIdDecodeRecordLeaf_notDirect() {
        // SDL → classified model: the single-key scalar record member resolves to a NodeIdDecodeRecord
        // leaf (not a Direct (FilmRecord) raw.get(...) cast — the CCE), carrying the typeId,
        // single key column, target record type, and SDL non-nullability the emitter materialises from.
        var leaf = decodeRecordLeaf(HAPPY_SDL, "assignFilm", false);
        assertThat(leaf.typeId()).as("typeId resolved from @nodeId(typeName:)").isEqualTo("Film");
        assertThat(leaf.keyColumns()).as("single-PK arity").hasSize(1);
        assertThat(leaf.keyColumns().get(0).sqlName()).isEqualTo("film_id");
        assertThat(CatalogRefs.recordClass(leaf.table()).toString()).isEqualTo(RECORD_TYPE_FQN);
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

    /** Same shape as {@link #COMPOSITE_SDL}, but the SDL pins a typeId the table's metadata disagrees with. */
    private static final String TYPE_ID_OVERRIDE_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node(typeId: "FA46") { id: ID! }
        input AssignFilmActorInput {
            filmActor: ID! @nodeId(typeName: "FilmActor")
        }
        type Query {
            assignFilmActor(in: AssignFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActor"})
        }
        """;

    /** Two {@code @node} types over one table, the member naming the second. */
    private static final String SIBLING_NODE_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        type FilmActorFed implements Node @table(name: "film_actor") @node(typeId: "46") { id: ID! }
        input AssignFilmActorInput {
            filmActor: ID! @nodeId(typeName: "FilmActorFed")
        }
        type Query {
            assignFilmActor(in: AssignFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActor"})
        }
        """;

    /**
     * The same two {@code @node} types over one table, now with one input-bean member <em>each</em>.
     * Both members are {@code FilmActorRecord}s, so a decode identity keyed on the record class
     * collapses them onto one body carrying one type's typeId.
     */
    private static final String SIBLING_MEMBERS_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        type FilmActorFed implements Node @table(name: "film_actor") @node(typeId: "46") { id: ID! }
        input AssignFilmActorSiblingsInput {
            filmActor: ID! @nodeId(typeName: "FilmActor")
            filmActorFed: ID! @nodeId(typeName: "FilmActorFed")
        }
        type Query {
            assignFilmActorSiblings(in: AssignFilmActorSiblingsInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActorSiblings"})
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

    @Test
    void sdlTypeIdOverridingMetadata_readsTheNamedNodesTypeId_notTheTablesMetadata() {
        // film_actor's KjerneJooqGenerator metadata publishes typeId "FilmActor"; this SDL pins "FA46"
        // instead, and BuildContext.resolveTargetKeys must return the reconciled answer the named
        // NodeType carries rather than the table fact underneath it. The distinction is not cosmetic:
        // the typeId is baked into the emitted decodeValues(typeId, nodeId) argument, and the encoder
        // returns null on a prefix mismatch, so the wrong one makes the generated helper throw on
        // every well-formed client id — on an input that builds green. Every other typeId assertion
        // in this class is on a table whose metadata typeId equals its type name (or carries none at
        // all), which is why this shape is the one that discriminates.
        var leaf = decodeRecordLeaf(TYPE_ID_OVERRIDE_SDL, "assignFilmActor", false);
        assertThat(leaf.typeId())
            .as("@node(typeId:) wins over the table's __NODE_TYPE_ID, and the decode arm reads that")
            .isEqualTo("FA46");
        assertThat(leaf.keyColumns())
            .as("the key-column axis rides the same named-node read")
            .extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
    }

    @Test
    void siblingNodeTypesOverOneTable_readTheNamedOnesTypeId() {
        // Two @node types over film_actor, each publishing its own typeId — the federation shape. The
        // table fact cannot answer which one a @nodeId(typeName:) meant, so the read has to go through
        // the name. Here the member names the sibling, whose "46" differs from the "FilmActor" the
        // first type inherited from metadata.
        var leaf = decodeRecordLeaf(SIBLING_NODE_SDL, "assignFilmActor", false);
        assertThat(leaf.typeId())
            .as("the named sibling's typeId, not the metadata typeId its neighbour inherited")
            .isEqualTo("46");
        assertThat(CatalogRefs.recordClass(leaf.table()).toString())
            .as("both siblings are backed by film_actor, so the target record is unchanged")
            .isEqualTo(COMPOSITE_RECORD_TYPE_FQN);
    }

    @Test
    void siblingNodeTypesOverOneTable_eachMemberGetsItsOwnDecodeHelper() {
        // The defect this item removes: two @nodeId input fields over one table used to share one
        // decode helper, named from the record class and carrying whichever type's typeId won the
        // first-wins dedup, so one field rejected its own valid ids and accepted the other's. The
        // helper is a function of the node type, so the class hosts one per named type, named after
        // the type rather than the record class, and neither is ordinal-suffixed.
        var fetchers = findSpec("QueryFetchers", SIBLING_MEMBERS_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("one decode helper per node type, each named after its own type")
            .contains("decodeFilmActorRecord", "decodeFilmActorFedRecord");
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .filteredOn(n -> n.startsWith("decode"))
            .as("the two names are the two type names; nothing is disambiguated with an ordinal")
            .containsExactlyInAnyOrder("decodeFilmActorRecord", "decodeFilmActorFedRecord");

        // SDL → model: the two leaves carry the two type names and the two typeIds, which is what
        // makes the two bodies differ. Reading the names off the leaves rather than off the emitted
        // helpers is the half that pins the identity rather than the spelling.
        var leaves = decodeRecordLeaves(SIBLING_MEMBERS_SDL, "assignFilmActorSiblings");
        assertThat(leaves)
            .extracting(CallSiteExtraction.NodeIdDecodeRecord::typeName)
            .containsExactly("FilmActor", "FilmActorFed");
        assertThat(leaves)
            .extracting(CallSiteExtraction.NodeIdDecodeRecord::typeId)
            .as("each leaf checks the wire id against its own type's typeId")
            .containsExactly("FilmActor", "46");
        assertThat(leaves)
            .extracting(l -> CatalogRefs.recordClass(l.table()).toString())
            .as("and both still decode into the one record class their shared table backs")
            .containsExactly(COMPOSITE_RECORD_TYPE_FQN, COMPOSITE_RECORD_TYPE_FQN);
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

    /**
     * Every {@link CallSiteExtraction.NodeIdDecodeRecord} leaf on a {@code @service} field's single
     * input bean, in member order. The sibling of {@link #decodeRecordLeaf} for the multi-member
     * bean, where the claim is about the members' relationship to each other.
     */
    private static java.util.List<CallSiteExtraction.NodeIdDecodeRecord> decodeRecordLeaves(
            String sdl, String queryField) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", queryField);
        var bean = ((ServiceField) field).serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg fa && fa.shape() instanceof ValueShape.RecordInput)
            .map(e -> (ValueShape.RecordInput) ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no record-input bean arg on " + queryField));
        return bean.fields().stream()
            .map(f -> (CallSiteExtraction.NodeIdDecodeRecord)
                ((ValueShape.Scalar) f.shape()).leafTransform())
            .toList();
    }

    private static TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherRenderTestSupport.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE)
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
