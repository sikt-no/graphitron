package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        var fetchers = findSpec("QueryFetchers", HAPPY_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the create<Bean> helper and the per-record decode<Record> helper both land on the class")
            .contains("createTestNodeIdRecordBean", "decodeFilmRecord");
    }

    @Test
    void decodeHelper_returnsRecordType_takesObjectWire() {
        var decode = method(findSpec("QueryFetchers", HAPPY_SDL), "decodeFilmRecord");
        assertThat(decode.returnType().toString()).isEqualTo(RECORD_TYPE_FQN);
        assertThat(decode.parameters()).hasSize(1);
        assertThat(decode.parameters().get(0).type().toString()).isEqualTo("java.lang.Object");
    }

    @Test
    void beanHelper_routesRecordMemberThroughDecode_withNoRawCast() {
        var create = method(findSpec("QueryFetchers", HAPPY_SDL), "createTestNodeIdRecordBean");
        var body = create.code().toString();
        assertThat(body)
            .as("the record member is populated through the decode helper, not a wire-String cast")
            .contains("decodeFilmRecord(raw.get(\"film\"))");
        assertThat(body)
            .as("no raw (FilmRecord) raw.get(...) cast — that is the R150/R195 ClassCastException")
            .doesNotContain("(" + RECORD_TYPE_FQN + ") raw.get");
    }

    @Test
    void decodeHelper_decodesValuesThenCopiesIntoRecord_throwingOnTypeMismatch() {
        var decode = method(findSpec("QueryFetchers", HAPPY_SDL), "decodeFilmRecord");
        var body = decode.code().toString();
        assertThat(body)
            .as("decodes the raw key values via decodeValues and copies them straight into the"
                + " target record's key column with a typed set (no throwaway RecordN, no fromMap)")
            .contains(".decodeValues(\"Film\", nodeId)")
            .contains(".getDataType().convert(values[0])")
            .doesNotContain("fromMap")
            .doesNotContain("intoMap");
        assertThat(body)
            .as("a type-mismatch decode (null/wrong-arity values) is an authored-input error, not a silent null")
            .contains("graphql.GraphqlErrorException");
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
    void compositeKeyRecordMember_emitsCompositeDecodeHelper_oneSetPerKeyColumn() {
        var fetchers = findSpec("QueryFetchers", COMPOSITE_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the composite-key member resolves to a decode helper, not a 'not yet supported' rejection")
            .contains("createTestNodeIdCompositeRecordBean", "decodeFilmActorRecord");
        var body = method(fetchers, "decodeFilmActorRecord").code().toString();
        assertThat(body.split("\\.set\\(").length - 1)
            .as("a composite key materialises one typed set per key column (actor_id, film_id)")
            .isEqualTo(2);
        assertThat(method(fetchers, "createTestNodeIdCompositeRecordBean").code().toString())
            .as("the bean member routes through the composite decode helper, not a wire cast")
            .contains("decodeFilmActorRecord(raw.get(\"filmActor\"))");
    }

    @Test
    void listRecordMember_emitsListDecodeHelper_delegatingToScalar() {
        var fetchers = findSpec("QueryFetchers", LIST_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("a list-valued record member emits the list variant plus the scalar helper it delegates to")
            .contains("createTestNodeIdRecordListBean", "decodeFilmRecordList", "decodeFilmRecord");
        var listHelper = method(fetchers, "decodeFilmRecordList");
        assertThat(listHelper.returnType().toString())
            .as("the list helper returns List<FilmRecord>")
            .isEqualTo("java.util.List<" + RECORD_TYPE_FQN + ">");
        assertThat(listHelper.code().toString())
            .as("each element is materialised through the singular helper (throws on a wrong-type element)")
            .contains("decodeFilmRecord(element)");
        assertThat(method(fetchers, "createTestNodeIdRecordListBean").code().toString())
            .as("the list member routes through the list decode helper, not a wire cast")
            .contains("decodeFilmRecordList(raw.get(\"films\"))");
    }

    @Test
    void listOfCompositeRecordMember_exercisesBothDimensions() {
        var fetchers = findSpec("QueryFetchers", LIST_COMPOSITE_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .as("the both-dimensions corner: a list variant over a composite-key per-element decode")
            .contains("createTestNodeIdCompositeRecordListBean",
                "decodeFilmActorRecordList", "decodeFilmActorRecord");
        var scalarBody = method(fetchers, "decodeFilmActorRecord").code().toString();
        assertThat(scalarBody.split("\\.set\\(").length - 1)
            .as("each element materialises both composite key columns")
            .isEqualTo(2);
    }

    // ===== Helpers =====

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
