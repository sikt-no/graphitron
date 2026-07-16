package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
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
 * R75 Phase 1: pipeline-tier coverage for single-record DML payloads — plain SDL Object
 * payload types whose single {@code @table}-element data field admits without an authored
 * Java carrier.
 *
 * <p>Per-{@link DmlKind} admission cases run parameterised over INSERT / UPDATE / UPSERT so
 * per-kind divergence shows up immediately. DELETE-with-carrier is rejected at classify time
 * (the row is gone before the response SELECT can read it). Rejection paths share one
 * fixture per case. Cross-path cases verify the trigger is consumer-agnostic and that the
 * data-table-equals-input-table rejection (now backed structurally by
 * {@link no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted}'s compact constructor)
 * fires on mismatches.
 */
@PipelineTier
class SingleRecordPayloadPipelineTest {

    // ===== Trigger admission, parameterised over DmlKind (INSERT / UPDATE / UPSERT) =====

    // R141 retired the single-input + list-data-field admission as a Phase 1 case: the
    // cardinality dispatch in validateReturnType now routes that cell to Invariant #16 and the
    // bulk-input + list-data-field cell to the new MutationBulkDmlRecordField leaf. R141's
    // GraphitronSchemaBuilderTest truth-table holds the admitted-arm coverage for the new leaf;
    // this fixture file keeps single-data-field admission for MutationDmlRecordField only.

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_listDataField_classifiesAsBulkLeaf(DmlKind kind) {
        // UPSERT is deferred to R145 (mutation-cardinality-safety-upsert); the classifier surfaces
        // a deferred-to-R145 rejection rather than constructing the leaf, so the parameterised
        // case excludes UPSERT. R258 splits the UPDATE arm: the payload-returning bulk UPDATE
        // routes onto MutationBulkUpdatePayloadField (walker carrier), while INSERT stays on the
        // record-carrier MutationBulkDmlRecordField.
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        if (kind == DmlKind.UPDATE) {
            assertThat(mutField).isInstanceOf(MutationField.MutationBulkUpdatePayloadField.class);
            var upd = (MutationField.MutationBulkUpdatePayloadField) mutField;
            assertThat(upd.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
            assertThat(upd.returnType().returnTypeName()).isEqualTo("FilmPayload");
            assertThat(upd.inputArg().table().tableName()).isEqualTo("film");
            assertThat(upd.inputArg().list()).isTrue();
        } else {
            assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
            var dmlField = (MutationField.MutationBulkDmlRecordField) mutField;
            assertThat(dmlField.kind()).isEqualTo(kind);
            assertThat(dmlField.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
            assertThat(dmlField.returnType().returnTypeName()).isEqualTo("FilmPayload");
            assertThat(dmlField.tableInputArg().inputTable().tableName()).isEqualTo("film");
            assertThat(dmlField.tableInputArg().list()).isTrue();
        }
    }

    @Test
    void payload_bulkInput_listDataField_upsertDeferredToR145() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.UPSERT, "type FilmPayload { films: [Film!] }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.UPSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("UPSERT", "R145", "not supported");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_listDataField_dataFieldClassifiesAsRecordTableField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var dataField = schema.field("FilmPayload", "films");
        // The former SingleRecordTableField carrier collapsed into BatchedTableField — a
        // source=target re-fetch keyed on the PK read off the produced record(s).
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var rtf = (ChildField.BatchedTableField) dataField;
        assertThat(rtf.returnType()).isInstanceOf(ReturnTypeRef.TableBoundReturnType.class);
        assertThat(rtf.returnType().table().tableName()).isEqualTo("film");
        assertThat(rtf.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
        // SourceKey shape: Reader.ProducedRecordRead, Wrap.Row, single LiftedHop (source=target),
        // PK columns. The bulk (list) data field is per-key cardinality MANY (the held collection).
        var sk = rtf.sourceKey();
        assertThat(rtf.lift()).isInstanceOf(KeyLift.ProducedRecords.class);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(rtf.joinPath()).isEmpty();
        assertThat(rtf.parentCorrelation())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots.class);
        assertThat(((KeyLift.ProducedRecords) rtf.lift()).arity()).isEqualTo(Arity.MANY);
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_singleDataField_dataFieldClassifiesWithCardinalityOne(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind, "type FilmPayload { film: Film }"));

        // UPDATE routes onto MutationUpdatePayloadField; INSERT stays on MutationDmlRecordField.
        // The data field classifies as BatchedTableField (per-key cardinality ONE) for both.
        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(expectedSingleLeaf(kind));
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var rtf = (ChildField.BatchedTableField) dataField;
        assertThat(rtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
    }

    // Payload_atRecordWithNullClassName_classifiesAsSinglePayloadLeaf deleted. It pinned that
    // a bare @record on a single-record DML payload does not change classification; @record is
    // deprecated and ignored, so this is the same fixture as the no-@record single-payload case
    // above, which already pins the SingleRecordTableField (cardinality ONE) leaf.

    // ===== DELETE-with-carrier admission =====

    @Test
    void payload_withDeleteAndTableElement_returnsRejected() {
        // A payload-returning DELETE whose data field is a @table-element is rejected. The
        // row is gone after the statement and RETURNING carries only the primary key, so a full
        // @table projection is impossible; the classifier rejects DELETE -> @table at authoring
        // time and points the author at the ID-typed carrier shape (which echoes the deleted PKs).
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(DmlKind.DELETE, "type FilmPayload { film: Film }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.DELETE));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
    }

    // ===== Structural carrier-shape rejection (R141: unrecognized carrier-field shape) =====

    @Test
    void payload_withMultipleDataFields_returnsRejected() {
        // Two @table-element list-shaped data fields is two data channels — the scan
        // rejects with "declares N data-channel-shaped fields; require exactly one".
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] alsoFilms: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("2 data-channel-shaped fields", "require exactly one");
    }

    @Test
    void payload_withScalarField_returnsRejected() {
        // A scalar (String) on the carrier is not a recognized DML payload data-field
        // shape; the scan rejects naming the offending field and pointing at the extension
        // point.
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] description: String }"));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("description", "not a recognized DML payload data-field shape", "file a roadmap item");
    }

    @Test
    void payload_withInterfaceField_returnsRejected() {
        // An interface-typed field on the carrier is not a recognized DML payload
        // data-field shape (the SDL polymorphic union/interface shape is reserved for the
        // errors channel and requires @error members; an arbitrary interface doesn't match).
        // The scan names the offending field.
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
        assertThat(reason).contains("hits", "not a recognized DML payload data-field shape", "file a roadmap item");
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
        assertThat(schema.field("Film", "title")).isNotInstanceOf(ChildField.BatchedTableField.class);
    }

    @Test
    void payload_recordBackedViaProducer_classifiesAsExistingPath() {
        // A reflection-backed carrier (FilmCarrier binds to the @service producer's return) keeps
        // the existing authored-carrier path: when the SDL shape would otherwise admit as carrier
        // (one @table-element data field), the classifier instead walks the type's fields through
        // classifyChildFieldOnResultType. No SingleRecordTableField is registered on a
        // JavaRecordType / PojoResultType.Backed.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmCarrier {
                films: [Film!]
            }
            type Query {
                carrier: FilmCarrier @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);

        var dataField = schema.field("FilmCarrier", "films");
        assertThat(dataField).isNotInstanceOf(ChildField.BatchedTableField.class);
    }

    @Test
    void payload_dataFieldCarriesAtField_admitsUnderR178() {
        // R178 retired the carrier walk's forbidden-directives HardReject on @field(name:) on
        // non-$source carrier data fields (the SettKvotesporsmal bug's mechanism). With and
        // without the directive, the payload classifies identically; see
        // SettKvotesporsmalShapeRegressionTest for the contract pin.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @field(name: "films_alias") }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
    }

    @Test
    void payload_dataFieldCarriesAtDeprecated_admits() {
        // Bulk-input + list-data-field admits as MutationBulkDmlRecordField; @deprecated
        // on the data field is pure SDL metadata, not on the carrier's forbidden-directive list.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] @deprecated(reason: "use createFilms instead") }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
    }

    // ===== Carrier binding: plain SDL Object binds to its producer's JooqTableRecordType =====

    @Test
    void payload_plainSdlObject_bindsToJooqTableRecordType() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] }"));
        var carrierType = schema.type("FilmPayload");
        // A DML carrier binds to its RETURNING table's record.
        assertThat(carrierType).isInstanceOf(GraphitronType.JooqTableRecordType.class);
    }

    @Test
    void authoredCarrier_atRecordWithClassName_remainsBacked() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmCarrier {
                films: [Film!]
            }
            type Query {
                carrier: FilmCarrier @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var carrierType = schema.type("FilmCarrier");
        // A producer-backed carrier (DummyRecord via @service reflection) routes to a
        // record-backed ResultType (JavaRecordType / PojoResultType.Backed), confirming the
        // carrier-promotion path bound it to a concrete backing.
        assertThat(carrierType).isInstanceOf(GraphitronType.ResultType.class);
    }

    // ===== Cross-paths =====

    @Test
    void payload_returnedFromQueryField_isOrphanWithNoRegistration() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] }
            type Query { wrappedFilms: FilmPayload }
            """);

        // Query-side carriers have no producing mutation, so the per-producer registration
        // site in FieldBuilder is never reached, and the data field carries no fieldRegistry
        // entry. graphql-java only traverses fields whose parent was produced by a fetcher;
        // an unproduced carrier's data field is never reached at runtime, so a missing
        // registration is structurally safe.
        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_dataTableMismatchesInputTable_rejectsAtClassifier(DmlKind kind) {
        // The data field's @table is `actor`, but the mutation's input @table is `film` —
        // ProducerBinding.DmlEmitted's compact constructor rejects the disagreement at fold time.
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
        // Filter-by-default. UPDATE's SET/WHERE partition is derived by the UpdateRowsWalker
        // (PK-or-UK), not @value (retired R266); filmId covers the PK → WHERE, title → SET.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\"), title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\")";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\"), title: String";
        };
    }

    // ===== Dispatch-arm structural regression pin =====

    @Test
    void fetcherEmitter_revertedTwoArms() throws Exception {
        // Source-level structural assertion: FetcherEmitter.dataFetcherValue has reverted the
        // IdentityPassthrough capability arm; NestingField dispatches on its own permit. (R290
        // dissolved ConstructorField; R305 collapsed SingleRecordTableField into BatchedTableField,
        // which dispatches through the DataLoader path in TypeFetcherGenerator, not a bind arm here.)
        var src = Files.readString(Path.of(
            "src/main/java/no/sikt/graphitron/rewrite/generators/FetcherEmitter.java"));
        long identityArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.IdentityPassthrough\\b"));
        long passthroughDataArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.PassthroughDataField\\b"));
        long nestingFieldArms = countMatches(src, Pattern.compile(
            "field\\s+instanceof\\s+ChildField\\.NestingField\\b"));

        assertThat(identityArms)
            .as("IdentityPassthrough capability has been retired; no dispatch arm should remain")
            .isZero();
        assertThat(passthroughDataArms)
            .as("PassthroughDataField permit has been retired; no dispatch arm should remain")
            .isZero();
        assertThat(nestingFieldArms)
            .as("NestingField has its own dispatch arm")
            .isGreaterThanOrEqualTo(1);
    }

    // ===== R75 Phase 2: record-element data fields =====

    @Test
    void payload_recordElement_orphanDataFieldStaysUnregistered() {
        // R178 Phase 4: orphan record-element carriers (no producer mutation consuming the
        // payload) leave the data field unregistered. graphql-java's never-traverse-unproduced-
        // fields guarantee makes the missing registration structurally safe. Record-element
        // identity passthrough is now handled by the unified per-field classifier on producer-
        // bound parents.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmDto { title: String }
            type FilmDtoPayload { film: FilmDto }
            type Query {
                x: FilmDtoPayload
                aFilmDto: FilmDto @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);

        // A carrier-shaped payload that no producer returns (orphan) has no record to bind to
        // and is not nested under a table-backed parent, so the type pass leaves it unclassified
        // (absent from schema.types()); its data field stays unregistered. The field that returns it
        // (Query.x) classifies as UnclassifiedField, surfacing the orphan at the field edge.
        assertThat(schema.type("FilmDtoPayload")).isNull();

        assertThat(schema.field("FilmDtoPayload", "film")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_recordElement_dmlMutationRejectsAtClassifier(DmlKind kind) {
        // @mutation (DML) is restricted to @table-element data. A record-element carrier on a
        // DML mutation would require a "DML row → domain record" conversion step at the
        // emitter, which the spec tracks separately. The mutation classifier rejects at
        // classify time with a per-mismatch message naming the carrier, the data field, and
        // pointing to @service as the right path.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type FilmDto { title: String }
            type FilmDtoPayload { film: FilmDto }
            input FilmInput @table(name: "film") { %s }
            type Query {
                aFilmDto: FilmDto @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type Mutation { %s(in: FilmInput!): FilmDtoPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), mutationName(kind), kind.name());

        var schema = TestSchemaHelper.buildSchema(sdl);

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "'FilmDtoPayload'",
            "record-element data field",
            "@table-element or ID-scalar data field",
            "@service mutation");
    }

    // ===== LocalContext error channel on DML payloads (R12 + structural-scan integration) =====
    //
    // The structural DML-payload scan (BuildContext.scanStructuralDmlPayload) admits a carrier
    // shape with one @table-element or record-backed element data field plus an optional errors-shaped
    // sibling; FieldBuilder.detectStructuralDmlErrorChannel binds the errors-channel transport
    // to ErrorChannel.LocalContext when the carrier has no reflected developer-supplied payload
    // class with an errors slot (its element binds via the DML RETURNING). These tests pin that the resulting
    // MutationDmlRecordField / MutationBulkDmlRecordField carries Optional.of(LocalContext) and
    // the sibling ErrorsField on the payload classifies with Transport.LocalContext — the two
    // halves the emitter's catch arm (TypeFetcherGenerator.catchArm) and the data fetcher
    // (FetcherEmitter.dataFetcherValue's ErrorsField switch) read at emit time.

    private static final String CARRIER_WALK_LOCAL_CONTEXT_ERRORS = """
            type SimpleErr @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
            }
            union CarrierError = SimpleErr
            """;

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_singleInput_withErrorsField_classifiesAsMutationDmlRecordFieldWithLocalContext(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { film: Film errors: [CarrierError!] }"));

        // UPDATE routes onto MutationUpdatePayloadField; INSERT stays on MutationDmlRecordField.
        // The LocalContext error channel is carried on the common WithErrorChannel supertype either way.
        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(expectedSingleLeaf(kind));
        var dml = (no.sikt.graphitron.rewrite.model.WithErrorChannel) mutField;
        assertThat(dml.errorChannel()).isPresent();
        assertThat(dml.errorChannel().get())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ErrorChannel.LocalContext.class);
        assertThat(dml.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name()).containsExactly("SimpleErr");

        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        var ef = (ChildField.ErrorsField) errorsField;
        assertThat(ef.transport()).isInstanceOf(ChildField.Transport.LocalContext.class);

        // Sibling data channel still classifies as SingleRecordTableField (cardinality ONE for
        // single-input form). The validator-mirror allow-list admits this arm; the runtime
        // fetcher honors the null-source short-circuit guard the LocalContext catch path needs.
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var srtf = (ChildField.BatchedTableField) dataField;
        assertThat(srtf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_withErrorsField_classifiesAsMutationBulkDmlRecordFieldWithLocalContext(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { films: [Film!] errors: [CarrierError!] }"));

        // UPDATE routes onto MutationBulkUpdatePayloadField; INSERT stays on MutationBulkDmlRecordField.
        // The LocalContext error channel is carried on the common WithErrorChannel supertype either way.
        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(expectedBulkLeaf(kind));
        var bulk = (no.sikt.graphitron.rewrite.model.WithErrorChannel) mutField;
        assertThat(bulk.errorChannel()).isPresent();
        assertThat(bulk.errorChannel().get())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ErrorChannel.LocalContext.class);
        assertThat(bulk.errorChannel().get().mappedErrorTypes())
            .extracting(et -> et.name()).containsExactly("SimpleErr");

        var errorsField = schema.field("FilmPayload", "errors");
        assertThat(errorsField).isInstanceOf(ChildField.ErrorsField.class);
        assertThat(((ChildField.ErrorsField) errorsField).transport())
            .isInstanceOf(ChildField.Transport.LocalContext.class);

        var dataField = schema.field("FilmPayload", "films");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        assertThat(((ChildField.BatchedTableField) dataField).lift())
            .isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
                pr -> assertThat(pr.arity()).isEqualTo(Arity.MANY));
    }

    @Test
    void payload_withErrorsField_emittedFetcher_dispatchesThroughLocalContextRouter() throws Exception {
        // End-to-end emit pin: the MutationDmlRecordField fetcher's catch arm dispatches through
        // ErrorRouter.dispatchToLocalContext (R12 Phase C2), and the payload's errors-field
        // fetcher reads via env.getLocalContext() (R12 Phase C3 / FetcherEmitter LocalContext
        // arm). The two emissions are the only emit-time consequences of the LocalContext
        // binding; pinning their presence in the generated source guards against silent
        // regressions to the PayloadAccessor transport without a model-level signal.
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(DmlKind.INSERT,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { film: Film errors: [CarrierError!] }"));

        var generated = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE);
        var mutationFetchers = generated.stream()
            .filter(t -> t.name().equals("MutationFetchers"))
            .findFirst().orElseThrow().toString();
        assertThat(mutationFetchers)
            .as("MutationFetchers source")
            .contains("ErrorRouter.dispatchToLocalContext")
            .contains("ErrorMappings.")
            // The sentinel is the 4th argument: a non-null Record1 constructed via
            // DSL.using(SQLDialect.DEFAULT).newRecord(<pk fields>). Required because
            // graphql-java's completeValueForObject skips children on a null parent value;
            // the sentinel keeps the carrier traversable and the data field's null-PK SELECT
            // renders {data: null} via the SELECT's natural empty-result.
            .contains("SQLDialect.DEFAULT")
            .contains("newRecord");

        // The payload's ErrorsField with Transport.LocalContext is now reified onto
        // FilmPayloadFetchers as an env-dependent method (return env.getLocalContext()); the
        // schema-level wiring registers a method reference into it rather than an inline lambda.
        var wirings = no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter.emit(
            schema, DEFAULT_OUTPUT_PACKAGE);
        var filmPayloadWiring = wirings.get("FilmPayload");
        assertThat(filmPayloadWiring).as("FilmPayload wiring present").isNotNull();
        assertThat(filmPayloadWiring.toString())
            .as("FilmPayload.errors registers a method reference into FilmPayloadFetchers")
            .contains("FilmPayloadFetchers::errors");
        var filmPayloadFetchers = generated.stream()
            .filter(t -> t.name().equals("FilmPayloadFetchers"))
            .findFirst().orElseThrow().toString();
        assertThat(filmPayloadFetchers)
            .as("FilmPayloadFetchers.errors reads env.getLocalContext()")
            .contains("env.getLocalContext()");
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
        // UPDATE's SET/WHERE partition is derived by the UpdateRowsWalker (PK-or-UK
        // matched-key membership) — filmId (PK) into WHERE, title into SET — not from @value, which
        // R266 retired entirely. UPSERT is refused upstream before any partition runs.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\"), title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\")";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\"), title: String";
        };
    }

    /** The single-input payload leaf the classifier lands on for {@code kind}.*/
    private static Class<? extends MutationField> expectedSingleLeaf(DmlKind kind) {
        return kind == DmlKind.UPDATE
            ? MutationField.MutationUpdatePayloadField.class
            : MutationField.MutationDmlRecordField.class;
    }

    /** The bulk-input payload leaf the classifier lands on for {@code kind}.*/
    private static Class<? extends MutationField> expectedBulkLeaf(DmlKind kind) {
        return kind == DmlKind.UPDATE
            ? MutationField.MutationBulkUpdatePayloadField.class
            : MutationField.MutationBulkDmlRecordField.class;
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
