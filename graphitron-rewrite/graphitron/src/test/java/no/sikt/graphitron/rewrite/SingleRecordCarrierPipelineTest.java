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

    // R141 retired the single-input + list-data-field admission as a Phase 1 case: the
    // cardinality dispatch in validateReturnType now routes that cell to Invariant #16 and the
    // bulk-input + list-data-field cell to the new MutationBulkDmlRecordField leaf. R141's
    // GraphitronSchemaBuilderTest truth-table holds the admitted-arm coverage for the new leaf;
    // this fixture file keeps single-data-field admission for MutationDmlRecordField only.

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void carrier_bulkInput_listDataField_classifiesAsMutationBulkDmlRecordField(DmlKind kind) {
        // UPSERT is deferred to R145 (mutation-cardinality-safety-upsert); the classifier surfaces
        // a deferred-to-R145 rejection rather than constructing the leaf, so the parameterised
        // case excludes UPSERT.
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
        var dmlField = (MutationField.MutationBulkDmlRecordField) mutField;
        assertThat(dmlField.kind()).isEqualTo(kind);
        assertThat(dmlField.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
        assertThat(dmlField.returnType().returnTypeName()).isEqualTo("FilmPayload");
        assertThat(dmlField.tableInputArg().inputTable().tableName()).isEqualTo("film");
        assertThat(dmlField.tableInputArg().list()).isTrue();
    }

    @Test
    void carrier_bulkInput_listDataField_upsertDeferredToR145() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.UPSERT, "type FilmPayload { films: [Film!] }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.UPSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("UPSERT", "R145", "not supported");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void carrier_bulkInput_listDataField_dataFieldClassifiesAsSingleRecordTableField(DmlKind kind) {
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
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
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
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void carrier_atRecordWithNullClassName_classifiesAsMutationDmlRecordField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind, "type FilmPayload @record { film: Film }"));
        assertThat(schema.field("Mutation", mutationName(kind)))
            .isInstanceOf(MutationField.MutationDmlRecordField.class);
    }

    // ===== DELETE-with-carrier rejection =====

    @Test
    void carrier_withDelete_rejectsAtClassifier() {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(DmlKind.DELETE, "type FilmPayload { film: Film }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.DELETE));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "DELETE", "is not supported", "use ID or [ID!]");
    }

    // ===== Carrier-walk rejection (R141: no CarrierFieldRole permit match) =====

    @Test
    void carrier_withMultipleDataFields_returnsRejected() {
        // R141: two @table-element list-shaped data fields is two DataChannels — the walk
        // rejects with "declares N DataChannel-shaped fields; require exactly one".
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] alsoFilms: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("2 DataChannel-shaped fields", "require exactly one");
    }

    @Test
    void carrier_withScalarField_returnsRejected() {
        // R141: a scalar (String) on the carrier resolves to no CarrierFieldRole permit; the
        // walk rejects naming the offending field and pointing at the extension point.
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] description: String }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("description", "no CarrierFieldRole permit", "file a roadmap item");
    }

    @Test
    void carrier_withInterfaceField_returnsRejected() {
        // R141: an interface-typed field on the carrier resolves to no CarrierFieldRole permit
        // (the SDL polymorphic union/interface shape is reserved for R12's ErrorChannelRole and
        // requires @error members; an arbitrary interface doesn't match). The walk names the
        // offending field.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            interface Searchable { id: ID! }
            type FilmPayload { films: [Film!] hits: [Searchable!] }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("hits", "no CarrierFieldRole permit", "file a roadmap item");
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
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("carries '@field'");
    }

    @Test
    void carrier_dataFieldCarriesAtDeprecated_admits() {
        // R141: bulk-input + list-data-field admits as MutationBulkDmlRecordField; @deprecated
        // on the data field is pure SDL metadata, not on the carrier's forbidden-directive list.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @deprecated(reason: "use createFilms instead") }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableField.class);
        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
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
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void carrier_dataTableMismatchesInputTable_rejectsAtClassifier(DmlKind kind) {
        // The data field's @table is `actor`, but the mutation's input @table is `film` —
        // the load-bearing mutation-dml-record-field.data-table-equals-input-table check rejects.
        // Uses bulk input + list data field so the carrier admits its shape and the mismatch
        // check fires; the equivalent single-data-field shape would not fire Invariant #16.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String }
            type ActorPayload { actors: [Actor!] }
            input FilmInput @table(name: "film") { %s }
            type Query { x: String }
            type Mutation { %s(in: [FilmInput!]!): ActorPayload @mutation(typeName: %s) }
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
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void directReturn_dmlFetcher_emitsTwoStepShape(DmlKind kind) {
        // Direct-@table-return DML mutations migrate to the two-step shape uniformly with the
        // carrier path: PK-only RETURNING inside dsl.transactionResult(...), follow-up SELECT
        // outside the transaction lambda. Without this pin, a regression to single-statement
        // RETURNING $fields(...) would compile clean and pass the round-trip tests but defeat
        // the durability invariant the reshape exists to establish.
        //
        // JavaPoet's CodeBlock does not expose formatParts() publicly, so a true AST walk isn't
        // available from a test in this package. The pin operates on the rendered body as the
        // call-site fingerprint: count of `transactionResult(` invocations and presence /
        // ordering of `.select(`. These markers are jOOQ DSL method names — a refactor that
        // renames `transactionResult` or `.select` is a real semantic change, not a cosmetic
        // one. Whitespace, identifier renames, and parameter reorderings do not flip the
        // assertion, so the "body-string-compared" ban from the principles doc (no exact
        // source-text match against a hand-written expected) is honoured.
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
        int firstTransactionResult = body.indexOf("transactionResult(");
        int firstSelectAfterTxn = firstTransactionResult < 0
            ? -1
            : body.indexOf(".select(", firstTransactionResult);
        assertThat(transactionResultCalls)
            .as("direct-@table " + kind + " fetcher wraps PK-only RETURNING in exactly one transactionResult(...)")
            .isEqualTo(1);
        assertThat(firstSelectAfterTxn)
            .as("direct-@table " + kind + " fetcher runs a follow-up .select(...) after the transactionResult call site")
            .isGreaterThan(firstTransactionResult);
    }

    private static String directReturnInputBody(DmlKind kind) {
        // R144: filter-by-default for DELETE/UPDATE; @value marks SET-clause columns on UPDATE.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\"), title: String @value";
            case DELETE -> "filmId: Int! @field(name: \"film_id\")";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\"), title: String @value";
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

    // ===== R75 Phase 2: record-element data fields =====

    @Test
    void carrier_recordElement_dataFieldClassifiesAsSingleRecordIdentityField() {
        // Phase 2 widens the trigger's condition #3 to admit record-backed ResultType elements
        // (any ResultType arm with a non-null fqClassName). The data field's element type here
        // is @record(record: {className: ...}) → PojoResultType.Backed; the trigger emits
        // SingleRecordCarrierShape with DataElement.Record, and the schema-builder classifies
        // the data field as SingleRecordIdentityField with the resolved ResultReturnType.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmDto @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                title: String
            }
            type FilmDtoPayload { film: FilmDto }
            type Query { x: FilmDtoPayload }
            """);

        var carrier = schema.type("FilmDtoPayload");
        assertThat(carrier).isInstanceOf(GraphitronType.PojoResultType.NoBacking.class);

        var dataField = schema.field("FilmDtoPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.SingleRecordIdentityField.class);
        var identityField = (ChildField.SingleRecordIdentityField) dataField;
        assertThat(identityField.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
        assertThat(identityField.returnType().returnTypeName()).isEqualTo("FilmDto");
        assertThat(identityField.returnType().fqClassName())
            .isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void carrier_recordElement_dmlMutationRejectsAtClassifier(DmlKind kind) {
        // Phase 2 keeps @mutation (DML) restricted to @table-element data. A record-element
        // carrier (DataElement.Record) on a DML mutation would require a "DML row → domain
        // record" conversion step at the emitter, which the spec tracks separately. The
        // mutation classifier rejects at classify time with a per-mismatch message naming the
        // carrier, the data field, and pointing to @service as the right path.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type FilmDto @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                title: String
            }
            type FilmDtoPayload { film: FilmDto }
            input FilmInput @table(name: "film") { %s }
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): FilmDtoPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), mutationName(kind), kind.name());

        var schema = TestSchemaHelper.buildSchema(sdl);

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "'FilmDtoPayload'",
            "record-element data field",
            "DML mutations require an @table-element data field",
            "@service mutation");
    }

    @Test
    void fetcherEmitter_singleRecordIdentityFieldArm() throws Exception {
        // Phase 2 structural pin: FetcherEmitter.dataFetcherValue dispatches
        // SingleRecordIdentityField as its own arm (identity passthrough — env -> env.getSource()).
        // Overloading ConstructorField with an identity-accessor variant was rejected at spec
        // time per the principles doc's "god accessor" smell; the sibling-permit shape keeps the
        // read-mechanism axis explicit at the type-system level.
        var src = Files.readString(Path.of(
            "src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java"));
        long identityArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.SingleRecordIdentityField\\b"));
        assertThat(identityArms)
            .as("SingleRecordIdentityField has its own dispatch arm")
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
            case UPDATE -> "filmId: Int! @field(name: \"film_id\"), title: String @value";
            case DELETE -> "filmId: Int! @field(name: \"film_id\")";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\"), title: String @value";
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
