package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R75 Phase 1: pipeline-tier coverage for passthrough payloads — plain SDL Object payload
 * types whose single {@code @table}-element data field admits without an authored carrier.
 *
 * <p>Per-DmlKind admission cases run parameterised over {@link DmlKind} so per-kind divergence
 * (should it ever appear) shows up immediately. Rejection paths share one fixture per case
 * (the rejection logic doesn't fork on kind, so one INSERT is sufficient). Cross-path cases
 * verify the unwrap is consumer-agnostic (queries flow through the same function) and that
 * the load-bearing data-table-equals-input-table check rejects mismatches per-kind.
 *
 * <p>The {@code fetcherEmitter_identityPassthrough_dispatchArmCount} structural assertion
 * pins that {@code FetcherEmitter.dataFetcherValue}'s post-change form has exactly one
 * {@code instanceof ChildField.IdentityPassthrough} arm and zero {@code instanceof} arms
 * against the individual permits — not a body-content assertion, just the dispatch count.
 */
@PipelineTier
class PassthroughPayloadPipelineTest {

    // ===== Trigger admission, parameterised over DmlKind =====

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_listDataField_resolvesAsTableBoundReturn(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(MutationField.DmlTableField.class);
        var rex = ((MutationField.DmlTableField) mutField).returnExpression();
        assertThat(rex).isInstanceOf(DmlReturnExpression.ProjectedList.class);
        assertThat(((DmlReturnExpression.ProjectedList) rex).returnTypeName()).isEqualTo("Film");
    }

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_singleDataField_resolvesAsTableBoundReturn(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { film: Film }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(MutationField.DmlTableField.class);
        var rex = ((MutationField.DmlTableField) mutField).returnExpression();
        assertThat(rex).isInstanceOf(DmlReturnExpression.ProjectedSingle.class);
        assertThat(((DmlReturnExpression.ProjectedSingle) rex).returnTypeName()).isEqualTo("Film");
    }

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_atRecordWithNullClassName_resolvesAsTableBoundReturn(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload @record { films: [Film!] }"));
        assertThat(schema.field("Mutation", mutationName(kind))).isInstanceOf(MutationField.DmlTableField.class);
    }

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_listDataField_dataFieldRegistersAsPassthroughDataField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.PassthroughDataField.class);
        assertThat(dataField).isInstanceOf(ChildField.IdentityPassthrough.class);
        var pdf = (ChildField.PassthroughDataField) dataField;
        assertThat(pdf.returnType()).isInstanceOf(ReturnTypeRef.TableBoundReturnType.class);
        assertThat(pdf.returnType().table().tableName()).isEqualTo("film");
        assertThat(pdf.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
    }

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_listDataField_mutationFieldCarriesProjectedList(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = (MutationField.DmlTableField) schema.field("Mutation", mutationName(kind));
        // The projected returnTypeName is the data field's element type, not the payload type;
        // the table threads through the per-kind emitter via the input @table at emit time.
        assertThat(mutField.returnExpression())
            .isInstanceOf(DmlReturnExpression.ProjectedList.class);
        assertThat(((DmlReturnExpression.ProjectedList) mutField.returnExpression()).returnTypeName())
            .isEqualTo("Film");
        assertThat(mutField.tableInputArg().inputTable().tableName()).isEqualTo("film");
    }

    // ===== Trigger rejection (DmlKind-agnostic, one INSERT fixture per case) =====

    @Test
    void payload_withMultipleDataFields_returnsRejected() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] errors: [String!] }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("declares 2 fields", "Phase 1 admits exactly one data field");
    }

    @Test
    void payload_withScalarField_returnsRejected() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { description: String }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("element type 'String' is not @table-mapped");
    }

    @Test
    void payload_withInterfaceField_returnsRejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            interface Searchable { id: ID! }
            type FilmPayload { hits: [Searchable!] }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "element type 'Searchable' is not @table-mapped",
            "Phase 1 admits @table elements only");
    }

    @Test
    void payload_atTableType_classifiesAsExistingPath() {
        // A @table return type goes through the existing TableBoundReturnType path; the
        // unwrap returns NotCandidate. No PassthroughDataField is registered (the @table type
        // already has its own classified fields), and the mutation field is a normal
        // MutationInsertTableField with ProjectedSingle.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationInsertTableField.class);
        assertThat(((MutationField.MutationInsertTableField) mutField).returnExpression())
            .isInstanceOf(DmlReturnExpression.ProjectedSingle.class);
        // Film.title classifies through the existing TableBackedType arm, not the passthrough arm.
        assertThat(schema.field("Film", "title")).isNotInstanceOf(ChildField.PassthroughDataField.class);
    }

    @Test
    void payload_atRecordWithClassName_classifiesAsExistingPath() {
        // A @record with className keeps the existing authored-carrier path: when the SDL
        // shape would otherwise admit as passthrough (one @table-element data field), the
        // classifier instead walks the type's fields through classifyChildFieldOnResultType.
        // No PassthroughDataField is registered on a JavaRecordType / PojoResultType-with-
        // className type.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmCarrier @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                films: [Film!]
            }
            type Query { carrier: FilmCarrier }
            """);

        var dataField = schema.field("FilmCarrier", "films");
        assertThat(dataField).isNotInstanceOf(ChildField.PassthroughDataField.class);
    }

    @Test
    void payload_dataFieldCarriesAtField_returnsRejected() {
        // @field on the data field signals a column rename, which has no meaning when the
        // wrapper resolves to a @table-element TableBoundReturnType. Rejecting at trigger
        // time is conservative; if @field on a passthrough payload's data field gains a
        // sensible interpretation later, remove it from the forbidden set.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "films_alias") }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("carries '@field'");
    }

    @Test
    void payload_dataFieldCarriesAtDeprecated_admits() {
        // @deprecated is pure metadata — no fetcher impact, no classification arm, just an
        // SDL flag for clients. The trigger admits payloads whose data field carries it; the
        // graphql-java introspection layer surfaces the deprecation downstream untouched.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @deprecated(reason: "use createFilms instead") }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.PassthroughDataField.class);
        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationInsertTableField.class);
    }

    // ===== Cross-paths =====

    @Test
    void payload_returnedFromQueryField_resolvesUniformly() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] }
            type Query { wrappedFilms: FilmPayload }
            """);

        // Query-side use: the data field still registers as PassthroughDataField via the
        // schema-builder's PlainObjectType arm; the unwrap is consumer-agnostic.
        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.PassthroughDataField.class);
    }

    @ParameterizedTest
    @EnumSource(DmlKind.class)
    void payload_dataTableMismatchesInputTable_rejectsAtClassifier(DmlKind kind) {
        // The data field's @table is `actor`, but the mutation's input @table is `film` —
        // jOOQ would reject the RETURNING projection at runtime; the classifier rejects
        // ahead of time via FieldBuilder.requireDataTableMatchesInputTable.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String }
            type ActorPayload { actors: [Actor!] }
            input FilmInput @table(name: "film") { %s }
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): ActorPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), mutationName(kind), kind.name());

        var schema = TestSchemaHelper.buildSchema(sdl);

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "passthrough payload 'ActorPayload'",
            "projecting onto table 'actor'",
            "input @table is 'film'");
    }

    // ===== Dispatch-arm count regression pin =====

    @Test
    void fetcherEmitter_identityPassthrough_dispatchArmCount() throws Exception {
        // Source-level structural assertion: FetcherEmitter.dataFetcherValue has exactly one
        // arm matching ChildField.IdentityPassthrough and zero arms matching the individual
        // permits (ConstructorField, NestingField, PassthroughDataField). The execution-tier
        // sakila fixtures verify the three permits behave identically through graphql-java;
        // this pin is the static counterpart.
        var src = Files.readString(Path.of(
            "src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java"));
        // Strip the import and javadoc references; count only `if (field instanceof ...)` arms.
        long capabilityArms = countMatches(src, Pattern.compile(
            "if\\s*\\(\\s*field\\s+instanceof\\s+ChildField\\.IdentityPassthrough\\b"));
        long constructorArms = countMatches(src, Pattern.compile(
            "if\\s*\\(\\s*field\\s+instanceof\\s+ChildField\\.ConstructorField\\b"));
        long nestingArms = countMatches(src, Pattern.compile(
            "if\\s*\\(\\s*field\\s+instanceof\\s+ChildField\\.NestingField\\b"));
        long passthroughDataArms = countMatches(src, Pattern.compile(
            "if\\s*\\(\\s*field\\s+instanceof\\s+ChildField\\.PassthroughDataField\\b"));

        assertThat(capabilityArms)
            .as("FetcherEmitter.dataFetcherValue should dispatch on the IdentityPassthrough capability "
                + "exactly once (covers ConstructorField, NestingField, PassthroughDataField uniformly)")
            .isEqualTo(1);
        assertThat(constructorArms + nestingArms + passthroughDataArms)
            .as("no per-permit instanceof dispatch should remain in FetcherEmitter — the capability "
                + "subsumes them")
            .isZero();
    }

    // ===== Helpers =====

    private static String mutationName(DmlKind kind) {
        return switch (kind) {
            case INSERT -> "createFilm";
            case UPDATE -> "updateFilm";
            case DELETE -> "deleteFilm";
            case UPSERT -> "upsertFilm";
        };
    }

    private static String inputBody(DmlKind kind) {
        // INSERT can omit @lookupKey; UPDATE / DELETE / UPSERT require @lookupKey on the PK,
        // and UPDATE additionally needs at least one non-@lookupKey field.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\") @lookupKey";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
        };
    }

    private static String payloadDml(DmlKind kind, String payloadType) {
        return """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { %s }
            %s
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): FilmPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), payloadType, mutationName(kind), kind.name());
    }

    private static long countMatches(String src, Pattern pattern) {
        return pattern.matcher(src).results().count();
    }
}
