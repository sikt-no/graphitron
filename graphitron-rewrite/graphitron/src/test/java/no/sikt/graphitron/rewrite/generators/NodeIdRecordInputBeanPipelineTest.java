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
 * <p>Backed by the real test jOOQ {@code FilmRecord} (single-column PK {@code film_id}) and the
 * {@link no.sikt.graphitron.rewrite.TestNodeIdRecordBean} fixture
 * ({@code record(FilmRecord film)}); the service method is
 * {@code TestServiceStub.assignFilm(TestNodeIdRecordBean in)}.
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
    void decodeHelper_decodesThenRebuildsRecord_throwingOnTypeMismatch() {
        var decode = method(findSpec("QueryFetchers", HAPPY_SDL), "decodeFilmRecord");
        var body = decode.code().toString();
        assertThat(body)
            .as("decodes via the per-Node helper and rebuilds the record by-name from the key tuple")
            .contains(".decodeFilm(")
            .contains("record.fromMap(key.intoMap())");
        assertThat(body)
            .as("a type-mismatch decode (null key) is an authored-input error, not a silent null")
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

    @Test
    void compositeKeyRecordMember_rejectsAtGenerationTime() {
        var sdl = """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
            input AssignFilmActorInput {
                filmActor: ID! @nodeId(typeName: "FilmActor")
            }
            type Query {
                assignFilmActor(in: AssignFilmActorInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "assignFilmActor"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "assignFilmActor");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).rejection().message())
            .as("the composite-key punt names the field and the deferral")
            .contains("filmActor")
            .contains("composite-key record members");
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
