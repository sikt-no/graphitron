package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MethodBackedField;
import no.sikt.graphitron.rewrite.model.ServiceField;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R311 pipeline tier: a jOOQ {@code TableRecord} bound <em>directly</em> as a {@code @service} input
 * param (singular {@code Record} or {@code List<Record>}), not as a member of an input bean. The
 * param's SDL input type names jOOQ columns through {@code @field(name:)} and carries a {@code @nodeId}
 * identity that decodes into the record's scalar key, so the param binds on the column axis +
 * scalar-key decode (a {@code CallSiteExtraction.JooqRecord}) and a {@code create<Record>} helper is
 * emitted — never bean-ified on the Java-member axis (the misleading "has no fields matching" the bean
 * path produced on {@code main}).
 *
 * <p>Covers both cardinalities (the consumer's motivating shape is the list), single- and composite-key
 * identity, the regression pin for the original bug, the child-{@code @service} coordinate (the
 * {@code ArgCallEmitter} real arm), and the rejection set. No generated-body string assertions: the
 * presence of the {@code create<Record>} helper plus the carrier's resolved shape is the structural
 * pin. Backed by the real test jOOQ records and {@code TestServiceStub.modifyFilm*} methods.
 */
@PipelineTier
class JooqRecordServiceParamPipelineTest {

    private static final String FILM_RECORD_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";
    private static final String FILM_ACTOR_RECORD_FQN =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmActorRecord";

    private static final String SINGLE_KEY_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
            releaseYear: Int @field(name: "release_year")
        }
        type Query {
            modifyFilm(in: ModifyFilmInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
        }
        """;

    private static final String COMPOSITE_KEY_SDL = """
        type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
        input ModifyFilmActorInput {
            id: ID! @nodeId(typeName: "FilmActor")
        }
        type Query {
            modifyFilmActor(in: ModifyFilmActorInput!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmActorRecord"})
        }
        """;

    private static final String LIST_SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
        }
        type Query {
            modifyFilms(in: [ModifyFilmInput!]!): String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecords"})
        }
        """;

    @Test
    void singularRecordParam_classifiesToJooqRecord_withColumnsAndKeyDecode() {
        // The param classifies to a JooqRecord carrier (not a Direct, not an InputBean): asserting the
        // arm is itself the pin that it did not bean-ify or stay Direct. table, the two column bindings,
        // and the single-key identity decode are all read off the carrier.
        var jr = carrier(SINGLE_KEY_SDL, "modifyFilm", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(jr.columnBindings())
            .as("the two plain @field columns bind on the column axis, each a resolved ColumnRef")
            .extracting(cb -> cb.sdlFieldName() + "->" + cb.column().sqlName())
            .containsExactly("title->title", "releaseYear->release_year");
        assertThat(jr.keyDecode()).as("the @nodeId field is the record's identity").isPresent();
        var kd = jr.keyDecode().get();
        assertThat(kd.sdlFieldName()).as("the decode carries its own Map key").isEqualTo("filmId");
        assertThat(kd.typeId()).isEqualTo("Film");
        assertThat(kd.keyColumns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    @Test
    void singularRecordParam_emitsCreateRecordHelperOnFetchersClass() {
        // The create<Record> helper's presence is the structural signal that the param classified to a
        // JooqRecord (and that emission did not throw): a Direct/bean path emits no such helper.
        var fetchers = findSpec("QueryFetchers", SINGLE_KEY_SDL);
        assertThat(fetchers.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("createFilmRecord");
    }

    @Test
    void compositeKeyRecordParam_keyDecodeCarriesBothColumns() {
        // film_actor PK is (actor_id, film_id): the identity decode carries both, arity 2. The input
        // has no plain @field columns, so columnBindings is empty and the keyDecode satisfies the
        // at-least-one-binding floor on its own.
        var jr = carrier(COMPOSITE_KEY_SDL, "modifyFilmActor", false);
        assertThat(jr.table().recordClass().toString()).isEqualTo(FILM_ACTOR_RECORD_FQN);
        assertThat(jr.columnBindings()).isEmpty();
        assertThat(jr.keyDecode()).isPresent();
        assertThat(jr.keyDecode().get().keyColumns())
            .extracting(c -> c.sqlName())
            .containsExactly("actor_id", "film_id");
        assertThat(findSpec("QueryFetchers", COMPOSITE_KEY_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .contains("createFilmActorRecord");
    }

    @Test
    void listRecordParam_derivesListOfJooqRecordInput_andEmitsBothHelpers() {
        // The consumer's real shape (List<…Record> against [Input!]!). The derived ValueShape is
        // ListOf(JooqRecordInput) — the pin that cardinality is handled, not dropped — and the list
        // call site routes through create<Record>List, which delegates per element to the SAME singular
        // create<Record> (one construction site, mapped). Both helpers land on the class.
        var field = TestSchemaHelper.buildSchema(LIST_SDL).field("Query", "modifyFilms");
        var shape = fromArgShape((ServiceField) field);
        assertThat(shape).as("a List<Record> param derives a ListOf wrap").isInstanceOf(ValueShape.ListOf.class);
        var element = ((ValueShape.ListOf) shape).elementShape();
        assertThat(element).isInstanceOf(ValueShape.JooqRecordInput.class);
        assertThat(((ValueShape.JooqRecordInput) element).carrier().table().recordClass().toString())
            .isEqualTo(FILM_RECORD_FQN);
        assertThat(findSpec("QueryFetchers", LIST_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .as("the list variant plus the singular helper it delegates to")
            .contains("createFilmRecordList", "createFilmRecord");
    }

    @Test
    void listRecordParam_doesNotRegressToHasNoFieldsMatching() {
        // The red-test for the original bug: a List<FilmRecord> param whose input type carries only
        // @nodeId + @field (no Java-member-name overlap) must classify to JooqRecord, never an
        // UnclassifiedField "has no fields matching" — the proof the list shape no longer reaches
        // buildInputBean.
        var field = TestSchemaHelper.buildSchema(LIST_SDL).field("Query", "modifyFilms");
        assertThat(field)
            .as("the List<Record> param binds, it does not bean-ify and reject")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    // ===== Child @service coordinate (ArgCallEmitter real arm) =====

    private static final String CHILD_SDL = """
        type Language implements Node @table(name: "language") @node { id: ID! name: String }
        type Film implements Node @table(name: "film") @node {
            id: ID!
            modifiedLanguage(in: ModifyFilmInput!): Language
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childModifyFilmRecord"})
        }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
        }
        type Query { film: Film }
        """;

    @Test
    void childServiceCoordinate_argClassifiesToJooqRecord_andEmitsHelperViaArgCallEmitter() {
        // The parity pin for the ArgCallEmitter arm: enrich runs for child @service too, so the
        // FilmRecord arg on a @table-parent child field classifies to JooqRecord exactly as the root
        // param does. The createFilmRecord helper on FilmFetchers is the proof that the child rows-
        // method's call site went through the ArgCallEmitter real arm (not a throw, not the old "has no
        // fields matching"). The binding is coordinate-agnostic.
        var field = TestSchemaHelper.buildSchema(CHILD_SDL).field("Film", "modifiedLanguage");
        assertThat(field)
            .as("the child @service field classifies (the arg binds, it does not reject)")
            .isNotInstanceOf(UnclassifiedField.class);
        assertThat(field).isInstanceOf(MethodBackedField.class);
        var jooqArg = ((MethodBackedField) field).method().callParams().stream()
            .map(CallParam::extraction)
            .filter(e -> e instanceof CallSiteExtraction.JooqRecord)
            .map(e -> (CallSiteExtraction.JooqRecord) e)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no JooqRecord arg on the child @service rows-method"));
        assertThat(jooqArg.table().recordClass().toString()).isEqualTo(FILM_RECORD_FQN);
        assertThat(findSpec("FilmFetchers", CHILD_SDL).methodSpecs())
            .extracting(MethodSpec::name)
            .as("the child rows-method's createFilmRecord helper lands on the parent's *Fetchers class")
            .contains("createFilmRecord");
    }

    // ===== Rejections (honest, validate-time UnclassifiedField) =====

    @Test
    void foreignTableNodeId_rejectsWithLiftedMismatchMessage() {
        // The param is a FilmRecord, but @nodeId(typeName: "FilmActor") decodes into a FilmActorRecord.
        // The lifted record-type-mismatch gate rejects, naming both records — never a decode helper
        // whose record type mismatches the param.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! }
            input ModifyMismatchInput {
                filmId: ID! @nodeId(typeName: "FilmActor")
            }
            type Query {
                modifyMismatch(in: ModifyMismatchInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyMismatch");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("FilmRecord")
            .contains("FilmActorRecord")
            .contains("@nodeId(typeName: \"FilmActor\")");
    }

    @Test
    void twoNodeIdFields_reject() {
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyTwoIdInput {
                a: ID! @nodeId(typeName: "Film")
                b: ID! @nodeId(typeName: "Film")
            }
            type Query {
                modifyTwoId(in: ModifyTwoIdInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyTwoId");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("more than one @nodeId");
    }

    @Test
    void fieldResolvingToNoColumn_rejectsWithCandidateHint() {
        // The honest replacement for "has no fields matching": a @field naming a non-existent column
        // rejects naming the field, the binding key, and the table, with a Levenshtein candidate hint.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyBadColInput {
                filmId: ID! @nodeId(typeName: "Film")
                bogus: String @field(name: "nonexistent_col")
            }
            type Query {
                modifyBadCol(in: ModifyBadColInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyBadCol");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason())
            .contains("nonexistent_col")
            .contains("no")
            .contains("column")
            .contains("did you mean");
    }

    @Test
    void singularJavaParam_againstListArg_rejectsAtCardinalityGate() {
        // A singular FilmRecord Java param against a [Input!] SDL arg is rejected at the shared
        // cardinality-parity gate, before any JooqRecord is built — never emitted as a create<Record>
        // call against a List wire value that throws ClassCastException at runtime.
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyFilmInput { filmId: ID! @nodeId(typeName: "Film") }
            type Query {
                modifyFilm(in: [ModifyFilmInput!]): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecord"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyFilm");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason()).contains("cardinalities");
    }

    @Test
    void listJavaParam_againstSingularArg_rejectsAtCardinalityGate() {
        var sdl = """
            type Film implements Node @table(name: "film") @node { id: ID! }
            input ModifyFilmInput { filmId: ID! @nodeId(typeName: "Film") }
            type Query {
                modifyFilms(in: ModifyFilmInput!): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "modifyFilmRecords"})
            }
            """;
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", "modifyFilms");
        assertThat(field).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) field).reason()).contains("cardinalities");
    }

    // ===== Helpers =====

    private static CallSiteExtraction.JooqRecord carrier(String sdl, String queryField, boolean list) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Query", queryField);
        var shape = fromArgShape((ServiceField) field);
        ValueShape.JooqRecordInput jr = list
            ? (ValueShape.JooqRecordInput) ((ValueShape.ListOf) shape).elementShape()
            : (ValueShape.JooqRecordInput) shape;
        return jr.carrier();
    }

    private static ValueShape fromArgShape(ServiceField field) {
        return field.serviceMethodCall().methodArgs().stream()
            .filter(e -> e instanceof MappingEntry.FromArg)
            .map(e -> ((MappingEntry.FromArg) e).shape())
            .findFirst()
            .orElseThrow(() -> new AssertionError("no FromArg on the @service method"));
    }

    private static TypeSpec findSpec(String className, String sdl) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE)
            .stream()
            .filter(t -> t.name().equals(className))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Class not found: " + className));
    }
}
