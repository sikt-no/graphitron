package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R75 Phase 1: pipeline-tier coverage for single-record DML carriers — plain SDL Object
 * payload types whose single {@code @table}-element data field admits without an authored
 * Java carrier.
 *
 * <p>Per-{@link DmlKind} admission cases run parameterised over INSERT / UPDATE / UPSERT so
 * per-kind divergence shows up immediately. DELETE-with-carrier is rejected at classify time
 * (the row is gone before the response SELECT can read it). Rejection paths share one
 * fixture per case. Cross-path cases verify the trigger is consumer-agnostic and that the
 * load-bearing data-table-equals-input-table check rejects mismatches.
 */
@PipelineTier
class SingleRecordCarrierPipelineTest {

    // ===== Trigger admission, parameterised over DmlKind (INSERT / UPDATE / UPSERT) =====

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void carrier_listDataField_classifiesAsMutationDmlRecordField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(MutationField.MutationDmlRecordField.class);
        var dmlField = (MutationField.MutationDmlRecordField) mutField;
        assertThat(dmlField.kind()).isEqualTo(kind);
        assertThat(dmlField.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
        assertThat(dmlField.returnType().returnTypeName()).isEqualTo("FilmPayload");
        assertThat(dmlField.tableInputArg().inputTable().tableName()).isEqualTo("film");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void carrier_listDataField_dataFieldClassifiesAsSingleRecordTableField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        assertThat(srtf.returnType()).isInstanceOf(ReturnTypeRef.TableBoundReturnType.class);
        assertThat(srtf.returnType().table().tableName()).isEqualTo("film");
        assertThat(srtf.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
        // SourceKey shape: Reader.ResultRowWalk, Wrap.Record, empty path, PK columns.
        var sk = srtf.sourceKey();
        assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.ResultRowWalk.class);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Record.class);
        assertThat(sk.path()).isEmpty();
        assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void carrier_singleDataField_dataFieldClassifiesWithCardinalityOne(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind, "type FilmPayload { film: Film }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(MutationField.MutationDmlRecordField.class);
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var srtf = (ChildField.SingleRecordTableField) dataField;
        assertThat(srtf.sourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void carrier_atRecordWithNullClassName_classifiesAsMutationDmlRecordField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload @record { films: [Film!] }"));
        assertThat(schema.field("Mutation", mutationName(kind)))
            .isInstanceOf(MutationField.MutationDmlRecordField.class);
    }

    // ===== DELETE-with-carrier rejection =====

    @Test
    void carrier_withDelete_rejectsAtClassifier() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.DELETE, "type FilmPayload { films: [Film!] }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.DELETE));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "DELETE", "is not supported", "use ID or [ID!]");
    }

    // ===== Trigger rejection (DmlKind-agnostic, one INSERT fixture per case) =====

    @Test
    void carrier_withMultipleDataFields_returnsRejected() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] extras: [String!] }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("declares 2 fields", "Phase 1 admits exactly one data field");
    }

    @Test
    void carrier_withScalarField_returnsRejected() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { description: String }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("element type 'String' is not @table-mapped");
    }

    @Test
    void carrier_withInterfaceField_returnsRejected() {
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
    void directReturn_atTableType_classifiesAsExistingPath() {
        // A @table return type goes through the existing TableBoundReturnType path; the
        // carrier trigger returns NotCandidate. The mutation field is a normal
        // MutationInsertTableField; no SingleRecordTableField is registered.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationInsertTableField.class);
        // Film.title classifies through the existing TableBackedType arm, not the carrier arm.
        assertThat(schema.field("Film", "title")).isNotInstanceOf(ChildField.SingleRecordTableField.class);
    }

    @Test
    void carrier_atRecordWithClassName_classifiesAsExistingPath() {
        // A @record with className keeps the existing authored-carrier path: when the SDL
        // shape would otherwise admit as carrier (one @table-element data field), the
        // classifier instead walks the type's fields through classifyChildFieldOnResultType.
        // No SingleRecordTableField is registered on a JavaRecordType / PojoResultType.Backed.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmCarrier @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                films: [Film!]
            }
            type Query { carrier: FilmCarrier }
            """);

        var dataField = schema.field("FilmCarrier", "films");
        assertThat(dataField).isNotInstanceOf(ChildField.SingleRecordTableField.class);
    }

    @Test
    void carrier_dataFieldCarriesAtField_returnsRejected() {
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
    void carrier_dataFieldCarriesAtDeprecated_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @deprecated(reason: "use createFilms instead") }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationDmlRecordField.class);
    }

    // ===== Carrier promotion: plain SDL Object becomes PojoResultType.NoBacking =====

    @Test
    void carrier_plainSdlObject_promotesToPojoResultTypeNoBacking() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] }"));
        var carrierType = schema.type("FilmPayload");
        assertThat(carrierType).isInstanceOf(GraphitronType.PojoResultType.NoBacking.class);
    }

    @Test
    void authoredCarrier_atRecordWithClassName_remainsBacked() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmCarrier @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                films: [Film!]
            }
            type Query { carrier: FilmCarrier }
            """);
        var carrierType = schema.type("FilmCarrier");
        // @record(className) routes to either JavaRecordType / PojoResultType.Backed; assert the
        // negative — it is NOT a NoBacking arm, so the carrier-promotion path didn't claim it.
        assertThat(carrierType).isNotInstanceOf(GraphitronType.PojoResultType.NoBacking.class);
    }

    // ===== Cross-paths =====

    @Test
    void carrier_returnedFromQueryField_resolvesUniformly() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] }
            type Query { wrappedFilms: FilmPayload }
            """);

        // Query-side use: the data field still classifies as SingleRecordTableField via the
        // schema-builder's NoBacking arm; the trigger is consumer-agnostic.
        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void carrier_dataTableMismatchesInputTable_rejectsAtClassifier(DmlKind kind) {
        // The data field's @table is `actor`, but the mutation's input @table is `film` —
        // the load-bearing mutation-dml-record-field.data-table-equals-input-table check rejects.
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
            "'ActorPayload'",
            "table 'actor'",
            "input table 'film'");
    }

    // ===== Direct-@table two-step emit pin =====

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE", "UPSERT"})
    void directReturn_dmlFetcher_emitsTwoStepShape(DmlKind kind) {
        // Direct-@table-return DML mutations migrate to the two-step shape uniformly with the
        // carrier path: PK-only RETURNING inside dsl.transactionResult(...), follow-up SELECT
        // outside the transaction lambda. Without this pin, a regression to single-statement
        // RETURNING $fields(...) would compile clean and pass the round-trip tests but defeat
        // the durability invariant the reshape exists to establish.
        String sdl = """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { %s }
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): Film @mutation(typeName: %s) }
            """.formatted(directReturnInputBody(kind), mutationName(kind), kind.name());

        var schema = TestSchemaHelper.buildSchema(sdl);
        var mutationFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst()
            .orElseThrow();
        var fetcherMethod = mutationFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals(mutationName(kind)))
            .findFirst()
            .orElseThrow();
        String body = fetcherMethod.code().toString();
        long transactionResultCalls = countMatches(body, Pattern.compile("transactionResult\\("));
        long selectFromCalls = countMatches(body, Pattern.compile("\\.select\\("));
        assertThat(transactionResultCalls)
            .as("direct-@table " + kind + " fetcher wraps PK-only RETURNING in exactly one transactionResult(...)")
            .isEqualTo(1);
        assertThat(selectFromCalls)
            .as("direct-@table " + kind + " fetcher runs a follow-up SELECT outside the transaction")
            .isGreaterThanOrEqualTo(1);
    }

    private static String directReturnInputBody(DmlKind kind) {
        // Direct-@table returns can use single inputs uniformly here; the two-step pin only
        // cares about the emit shape, not the input cardinality.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\") @lookupKey";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
        };
    }

    // ===== Dispatch-arm structural regression pin =====

    @Test
    void fetcherEmitter_revertedTwoArmsPlusSingleRecord() throws Exception {
        // Source-level structural assertion: FetcherEmitter.dataFetcherValue has reverted the
        // IdentityPassthrough capability arm; ConstructorField/NestingField dispatch on their
        // own permits, and SingleRecordTableField has a dedicated arm.
        var src = Files.readString(Path.of(
            "src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java"));
        long identityArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.IdentityPassthrough\\b"));
        long passthroughDataArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.PassthroughDataField\\b"));
        long constructorFieldArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.ConstructorField\\b"));
        long nestingFieldArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.NestingField\\b"));
        long singleRecordArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.SingleRecordTableField\\b"));

        assertThat(identityArms)
            .as("IdentityPassthrough capability has been retired; no dispatch arm should remain")
            .isZero();
        assertThat(passthroughDataArms)
            .as("PassthroughDataField permit has been retired; no dispatch arm should remain")
            .isZero();
        assertThat(constructorFieldArms)
            .as("ConstructorField has its own dispatch arm")
            .isGreaterThanOrEqualTo(1);
        assertThat(nestingFieldArms)
            .as("NestingField has its own dispatch arm")
            .isGreaterThanOrEqualTo(1);
        assertThat(singleRecordArms)
            .as("SingleRecordTableField has its own dispatch arm")
            .isEqualTo(1);
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
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\") @lookupKey";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\") @lookupKey, title: String";
        };
    }

    /** Bulk input ({@code [FilmInput!]!}) → list data field. */
    private static String payloadDml(DmlKind kind, String payloadType) {
        return """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { %s }
            %s
            type Query { x: String }
            type Mutation { %s(in: [FilmInput!]!): FilmPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), payloadType, mutationName(kind), kind.name());
    }

    /** Single input ({@code FilmInput!}) → single data field. */
    private static String payloadDmlSingleInput(DmlKind kind, String payloadType) {
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
