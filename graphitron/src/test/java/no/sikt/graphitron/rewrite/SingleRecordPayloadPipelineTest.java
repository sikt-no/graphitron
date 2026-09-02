package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.model.diagnostics.Arity;
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
import static no.sikt.graphitron.rewrite.DmlWriteReads.updateArgOf;

/**
 * Pipeline-tier coverage for single-record DML payloads: plain SDL Object payload types whose
 * single {@code @table}-element data field admits without an authored Java carrier.
 *
 * <p>Per-{@link DmlKind} admission cases run parameterised over INSERT / UPDATE / UPSERT so
 * per-kind divergence shows up immediately. DELETE-with-carrier is rejected at classify time
 * (the row is gone before the response SELECT can read it). Rejection paths share one
 * fixture per case. Cross-path cases verify the trigger is consumer-agnostic and that a
 * {@code @mutation(table:)} naming a different table than the payload's return-derived write
 * target rejects at the classifier (the rung cross-check in
 * {@code FieldBuilder.resolveReturnCapableWriteTarget}).
 */
@PipelineTier
class SingleRecordPayloadPipelineTest {

    // ===== Trigger admission, parameterised over DmlKind (INSERT / UPDATE / UPSERT) =====

    // Single-input + list-data-field admission is not covered here: the cardinality dispatch in
    // validateReturnType routes that cell to Invariant #16 and the bulk-input + list-data-field
    // cell to the MutationBulkDmlRecordField leaf. The GraphitronSchemaBuilderTest truth-table
    // holds the admitted-arm coverage for that leaf; this fixture file keeps single-data-field
    // admission for MutationDmlRecordField only.

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_listDataField_classifiesAsBulkLeaf(DmlKind kind) {
        // UPSERT is excluded: it is not supported under the cardinality-safety regime, and the
        // classifier surfaces a deferred rejection rather than constructing the leaf. The UPDATE
        // arm diverges: the payload-returning bulk UPDATE routes onto
        // a Write.Update arm sourced from the walker carrier, while INSERT stays on the
        // record-carrier MutationBulkDmlRecordField.
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var mutField = schema.field("Mutation", mutationName(kind));
        if (kind == DmlKind.UPDATE) {
            assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
            var upd = (MutationField.MutationBulkDmlRecordField) mutField;
            assertThat(upd.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
            assertThat(upd.returnType().returnTypeName()).isEqualTo("FilmPayload");
            assertThat(updateArgOf(upd).table().tableName()).isEqualTo("film");
            assertThat(upd.write().listInput()).isTrue();
        } else {
            assertThat(mutField).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
            var dmlField = (MutationField.MutationBulkDmlRecordField) mutField;
            assertThat(dmlField.write()).isInstanceOf(OperationMember.Write.Insert.class);
            assertThat(dmlField.returnType()).isInstanceOf(ReturnTypeRef.ResultReturnType.class);
            assertThat(dmlField.returnType().returnTypeName()).isEqualTo("FilmPayload");
            assertThat(dmlField.write().table().tableName()).isEqualTo("film");
            assertThat(dmlField.write().listInput()).isTrue();
        }
    }

    @Test
    void payload_bulkInput_listDataField_upsertDeferred() {
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.UPSERT, "type FilmPayload { films: [Film!] }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.UPSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("UPSERT", "not yet supported");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_listDataField_dataFieldClassifiesAsBatchedTableField(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind, "type FilmPayload { films: [Film!] }"));

        var dataField = schema.field("FilmPayload", "films");
        // The payload data field classifies as a record-sourced BatchedTableField — a
        // source=target re-fetch keyed on the PK read off the produced record(s).
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) dataField;
        assertThat(btf.returnType()).isInstanceOf(ReturnTypeRef.TableBoundReturnType.class);
        assertThat(btf.returnType().table().tableName()).isEqualTo("film");
        assertThat(btf.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
        // SourceKey shape: KeyLift.ProducedRecords lift, Wrap.Row, single LiftedHop (source=target),
        // PK columns. The bulk (list) data field is per-key cardinality MANY (the held collection).
        var sk = btf.sourceKey();
        assertThat(btf.lift()).isInstanceOf(KeyLift.ProducedRecords.class);
        assertThat(sk.wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(btf.joinPath()).isEmpty();
        assertThat(btf.parentCorrelation())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots.class);
        assertThat(((KeyLift.ProducedRecords) btf.lift()).arity()).isEqualTo(Arity.MANY);
        assertThat(sk.columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_singleDataField_dataFieldClassifiesWithCardinalityOne(DmlKind kind) {
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind, "type FilmPayload { film: Film }"));

        // UPDATE folds in with a Write.Update arm; INSERT carries Write.Insert.
        // The data field classifies as BatchedTableField (per-key cardinality ONE) for both.
        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(expectedSingleLeaf(kind));
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) dataField;
        assertThat(btf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
    }

    // No case for a bare @record on a single-record DML payload: @record is deprecated and
    // ignored, so it is the same fixture as the no-@record single-payload case above, which
    // already pins the cardinality-ONE BatchedTableField leaf.

    // ===== DELETE-with-carrier admission =====

    @Test
    void payload_withDeleteAndTableElement_returnsRejected() {
        // A payload-returning DELETE whose data field is a @table-element is rejected. The
        // row is gone after the statement and RETURNING carries only the primary key, so a full
        // @table projection is impossible; the classifier rejects DELETE -> @table at authoring
        // time and points the author at the ID-typed carrier shape (which echoes the deleted PKs).
        // DELETE has no return-derived rung, so the fixture names its write target with
        // @mutation(table:) (supplied by the helper for DELETE).
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(DmlKind.DELETE, "type FilmPayload { film: Film }"));
        var mutField = schema.field("Mutation", mutationName(DmlKind.DELETE));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
    }

    // ===== Structural carrier-shape rejection (unrecognized carrier-field shape) =====

    @Test
    void payload_withMultipleDataFields_returnsRejected() {
        // Two @table-element list-shaped data fields is two data channels — the scan
        // rejects with "declares N data-channel-shaped fields; require exactly one". The broken
        // carrier shape return-derives nothing, so the fixture grounds the write target with
        // @mutation(table:) to reach the payload-shape rejection.
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] alsoFilms: [Film!] }", true));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains("2 data-channel-shaped fields", "require exactly one");
    }

    @Test
    void payload_withScalarField_returnsRejected() {
        // A scalar (String) on the carrier is not a recognized DML payload data-field
        // shape; the scan rejects naming the offending field and pointing at the extension
        // point. The broken carrier shape return-derives nothing, so the fixture grounds the
        // write target with @mutation(table:) to reach the payload-shape rejection.
        var schema = TestSchemaHelper.buildSchema(payloadDml(DmlKind.INSERT,
            "type FilmPayload { films: [Film!] description: String }", true));

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
        // The scan names the offending field. The broken carrier shape return-derives nothing,
        // so the fixture grounds the write target with @mutation(table:).
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            interface Searchable { id: ID! }
            type FilmPayload { films: [Film!] hits: [Searchable!] }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT, table: "film") }
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
        // the direct-return DmlTableField; no payload-carrier BatchedTableField is registered.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var mutField = schema.field("Mutation", "createFilm");
        assertThat(mutField).isInstanceOf(MutationField.DmlTableField.class);
        // Film.title classifies through the existing TableBackedType arm, not the carrier arm.
        assertThat(schema.field("Film", "title")).isNotInstanceOf(ChildField.BatchedTableField.class);
    }

    @Test
    void payload_recordBackedViaProducer_classifiesAsExistingPath() {
        // A reflection-backed carrier (FilmCarrier binds to the @service producer's return) keeps
        // the existing authored-carrier path: when the SDL shape would otherwise admit as carrier
        // (one @table-element data field), the classifier instead walks the type's fields through
        // classifyChildFieldOnResultType. No payload-carrier BatchedTableField is registered on a
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
        // The carrier walk applies no forbidden-directives HardReject on @field(name:) on
        // non-$source carrier data fields. With and without the directive, the payload classifies
        // identically; see SettKvotesporsmalShapeRegressionTest for the contract pin.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
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
            input FilmInput { title: String }
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
    void payloadInsert_groundedCarrierTableEqualsClassifiedWriteTarget_returnDerived() {
        // The divergence enforcer: with @table dropped from the input, the INSERT write target is
        // derived from the payload's @table-element data field (rung 1). The binding grounder and the
        // classifier read that single fact through resolveDmlWriteTableRef, so the grounded carrier
        // table must equal the classified leaf's write target — the drift the shared helper forecloses.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT) }
            """);
        var carrierType = schema.type("FilmsPayload");
        assertThat(carrierType)
            .as("the return-derived payload INSERT still grounds a producer-backed carrier")
            .isInstanceOf(GraphitronType.JooqTableRecordType.class);
        var leaf = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "createFilms");
        assertThat(((GraphitronType.JooqTableRecordType) carrierType).table())
            .as("the grounded carrier table equals the classified leaf's write target (no divergence)")
            .isEqualTo(leaf.write().table());
    }

    @Test
    void payloadInsert_groundedCarrierTableEqualsClassifiedWriteTarget_mutationTableArgAgrees() {
        // The same equality holds when @mutation(table:) (rung 2) names the same table the
        // return derives (rung 1): resolving through either rung yields the identical write
        // target, and the grounded carrier table equals it.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film") }
            """);
        var carrierType = schema.type("FilmsPayload");
        assertThat(carrierType).isInstanceOf(GraphitronType.JooqTableRecordType.class);
        var leaf = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "createFilms");
        assertThat(((GraphitronType.JooqTableRecordType) carrierType).table())
            .isEqualTo(leaf.write().table());
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
    void payload_dataTableMismatchesMutationTableArg_rejectsAtClassifier(DmlKind kind) {
        // The payload data field's @table is `film` (rung 1, the return-derived write target),
        // but @mutation(table:) names `language` (rung 2) — the rung cross-check in
        // FieldBuilder.resolveReturnCapableWriteTarget rejects the disagreement: the RETURNING
        // projection reads from the write target, so the two cannot emit a coherent statement.
        String sdl = """
            type Film @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] }
            input FilmInput { %s }
            type Query { x: String }
            type Mutation { %s(in: [FilmInput!]!): FilmPayload @mutation(typeName: %s, table: "language") }
            """.formatted(inputBody(kind), mutationName(kind), kind.name());

        var schema = TestSchemaHelper.buildSchema(sdl);

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason).contains(
            "derives write target 'film'",
            "@mutation(table:) names a different table 'language'");
    }

    // ===== Direct-@table two-step emit pin =====

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void directReturn_dmlFetcher_emitsTwoStepShape(DmlKind kind) {
        // Direct-@table-return DML mutations emit the two-step shape uniformly with the
        // carrier path: PK-only RETURNING inside dsl.transactionResult(...), follow-up SELECT
        // outside the transaction lambda. Without this pin, a regression to single-statement
        // RETURNING $project(...) would compile clean and pass the round-trip tests but defeat
        // the durability invariant the shape exists to establish.
        //
        // JavaPoet's CodeBlock does not expose formatParts() publicly, so a true AST walk isn't
        // available from a test in this package. The pin operates on the rendered body as the
        // call-site fingerprint: count of `transactionResult(` invocations and presence /
        // ordering of `.select(`. These markers are jOOQ DSL method names; a refactor that
        // renames `transactionResult` or `.select` is a real semantic change, not a cosmetic
        // one. Whitespace, identifier renames, and parameter reorderings do not flip the
        // assertion, so the "body-string-compared" ban from the principles doc (no exact
        // source-text match against a hand-written expected) is honoured.
        String sdl = """
            type Film @table(name: "film") { title: String }
            input FilmInput { %s }
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
        assertThat(transactionResultCalls)
            .as("direct-@table " + kind + " fetcher wraps PK-only RETURNING in exactly one transactionResult(...)")
            .isEqualTo(1);
        // The follow-up SELECT lives in the named reentry rows companion the
        // fetcher calls after the transaction commits — the durability invariant (write commits
        // before any read can fail) survives as call ordering: the rows<Name>(keys, env) call
        // sits after the transactionResult call site, and the companion (not the fetcher) owns
        // the .select(...).
        String rowsName = "rows" + Character.toUpperCase(mutationName(kind).charAt(0))
            + mutationName(kind).substring(1);
        int rowsCallAfterTxn = body.indexOf(rowsName + "(keys, env)", firstTransactionResult);
        assertThat(rowsCallAfterTxn)
            .as("direct-@table " + kind + " fetcher calls the named reentry rows companion after the transactionResult call site")
            .isGreaterThan(firstTransactionResult);
        assertThat(body)
            .as("the fetcher body no longer inlines the follow-up SELECT")
            .doesNotContain(".select(");
        var rowsMethod = mutationFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals(rowsName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("named reentry rows companion " + rowsName + " not emitted"));
        assertThat(rowsMethod.code().toString())
            .as("the companion owns the follow-up .select(...)")
            .contains(".select(");
    }

    private static String directReturnInputBody(DmlKind kind) {
        // Filter-by-default. UPDATE's SET/WHERE partition is derived by the UpdateRowsWalker
        // (PK-or-UK); filmId covers the PK → WHERE, title → SET.
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
        // Source-level structural assertion: FetcherEmitter.dataFetcherValue has no
        // IdentityPassthrough capability arm; NestingField dispatches on its own permit. There is
        // no ConstructorField arm either: the record-sourced payload carrier is a BatchedTableField,
        // which dispatches through the DataLoader path in TypeFetcherGenerator, not a bind arm here.
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

    // ===== Record-element data fields =====

    @Test
    void payload_recordElement_orphanDataFieldStaysUnregistered() {
        // Orphan record-element carriers (no producer mutation consuming the payload) leave the
        // data field unregistered. graphql-java's never-traverse-unproduced-fields guarantee makes
        // the missing registration structurally safe. Record-element identity passthrough is
        // handled by the unified per-field classifier on producer-bound parents.
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
        // emitter, which does not exist. The mutation classifier rejects at classify time with
        // a per-mismatch message naming the carrier, the data field, and pointing to @service
        // as the right path. The record-element payload derives no write target from its
        // return, so @mutation(table:) supplies it; write-target resolution runs before the
        // payload scan, so without it the field rejects at the no-write-target seat instead
        // (payload_recordElementCarrier_withoutTableArg_steersAtServiceProducer pins that steer).
        String sdl = """
            type Film @table(name: "film") { title: String }
            type FilmDto { title: String }
            type FilmDtoPayload { film: FilmDto }
            input FilmInput { %s }
            type Query {
                aFilmDto: FilmDto @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type Mutation { %s(in: FilmInput!): FilmDtoPayload @mutation(typeName: %s, table: "film") }
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

    // ===== LocalContext error channel on DML payloads (structural-scan integration) =====
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
        // The consumer shape from the field report: an errors-as-data channel beside the data
        // field, with no @mutation(table:). The errors field is skipped by the carrier scan, so the
        // payload return-derives its write target off the @table-element data field like any other
        // carrier. This is the regression pin for the grounding-order fix: before it, the grounding
        // pass ran while the ErrorIndex was still empty, the errors field read as a second data
        // channel, no DmlEmitted was minted, and the field rejected with "not yet supported".
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(kind,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { film: Film errors: [CarrierError!] }"));

        // UPDATE folds in with a Write.Update arm; INSERT carries Write.Insert.
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

        // Sibling data channel still classifies as a record-sourced BatchedTableField (lift
        // arity ONE for the single-input form). The validator-mirror allow-list admits this arm; the runtime
        // fetcher honors the null-source short-circuit guard the LocalContext catch path needs.
        var dataField = schema.field("FilmPayload", "film");
        assertThat(dataField).isInstanceOf(ChildField.BatchedTableField.class);
        var btf = (ChildField.BatchedTableField) dataField;
        assertThat(btf.lift()).isInstanceOfSatisfying(KeyLift.ProducedRecords.class,
            pr -> assertThat(pr.arity()).isEqualTo(Arity.ONE));
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_bulkInput_withErrorsField_classifiesAsMutationBulkDmlRecordFieldWithLocalContext(DmlKind kind) {
        // The bulk-input half of the same regression pin: an errors channel does not stop the
        // payload from return-deriving its write target, so no @mutation(table:) is needed.
        var schema = TestSchemaHelper.buildSchema(payloadDml(kind,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { films: [Film!] errors: [CarrierError!] }"));

        // UPDATE folds in with a Write.Update arm; INSERT carries Write.Insert.
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
        // ErrorRouter.dispatchToLocalContext, and the payload's errors-field fetcher reads via
        // env.getLocalContext() (the FetcherEmitter LocalContext arm). The two emissions are the
        // only emit-time consequences of the LocalContext
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

        // The payload's ErrorsField with Transport.LocalContext is reified onto
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

    @Test
    void payload_withErrorsField_explicitTableArg_classifiesAndAgreesOnBothRungs() {
        // The explicit argument must keep working alongside an errors channel, which the three
        // fixtures above no longer cover now that they return-derive. Doubles as the rung-agreement
        // pin on an errors-bearing carrier: @mutation(table:) (rung 2) names the same table the
        // payload's @table-element data field derives (rung 1), so the grounded carrier's table and
        // the classified leaf's write target are the one fact resolveDmlWriteTableRef produces.
        //
        // A standing invariant pin, not a regression pin for the grounding order: on an agreeing
        // fixture both rungs resolve the same TableRef, so the equality also held before the
        // ordering fix (grounding fell to rung 2 while classification took rung 1 — the provenance
        // diverged, the value did not). A disagreeing fixture cannot pin it either, since the
        // classify-phase cross-check rejects one. The flipped fixtures above carry this item's
        // regression weight.
        var schema = TestSchemaHelper.buildSchema(payloadDmlSingleInput(DmlKind.INSERT,
            CARRIER_WALK_LOCAL_CONTEXT_ERRORS
            + "type FilmPayload { film: Film errors: [CarrierError!] }", true));

        var mutField = schema.field("Mutation", mutationName(DmlKind.INSERT));
        assertThat(mutField).isInstanceOf(MutationField.MutationDmlRecordField.class);
        var leaf = (MutationField.MutationDmlRecordField) mutField;
        assertThat(leaf.errorChannel()).isPresent();

        var carrierType = schema.type("FilmPayload");
        assertThat(carrierType).isInstanceOf(GraphitronType.JooqTableRecordType.class);
        assertThat(((GraphitronType.JooqTableRecordType) carrierType).table())
            .as("the grounded carrier table equals the classified leaf's write target")
            .isEqualTo(leaf.write().table());
    }

    // ===== Ungrounded-carrier diagnostics (shape-matched steers) =====
    //
    // A structurally well-formed carrier payload that no DML producer bound is published by the
    // recognizer as CarrierBinding.NotACarrier.UngroundedDmlCarrier, and the rejection wording forks
    // on its element kind. The populations split across two seats, which is why the fixtures below
    // read the two messages: a record- or ID-element carrier resolves no write-target rung, so
    // FieldBuilder.resolveReturnCapableWriteTarget's no-source arm rejects it before any return-type
    // validation runs. The @table-element population reaches
    // MutationInputResolver.validateReturnType's scalar arm instead, and post-fix nothing an SDL
    // fixture can express lands there: a @table-element payload that resolves rung 1 at classify
    // time resolved it at grounding time too, so it grounds. The residual population there is a
    // payload whose table resolved but whose jOOQ record class would not load, which no schema
    // fixture can produce; the flipped errors-bearing fixtures above are the pin that this shape
    // classifies rather than reaching that seat at all.

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_recordElementCarrier_withoutTableArg_steersAtServiceProducer(DmlKind kind) {
        // A record-element carrier's rows come from a producer, so neither of the generic message's
        // two fixes (return the row's @table type, name the table) helps. Naming a table with
        // @mutation(table:) does make it ground, but only onto the per-verb record-element
        // rejection pinned by payload_recordElement_dmlMutationRejectsAtClassifier.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmDto { title: String }
            type FilmDtoPayload { film: FilmDto }
            input FilmInput { %s }
            type Query {
                aFilmDto: FilmDto @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type Mutation { %s(in: FilmInput!): FilmDtoPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), mutationName(kind), kind.name()));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason)
            .contains("'FilmDtoPayload' is carrier-shaped", "data field 'film' is record-backed",
                "producing @service return type")
            .doesNotContain("not yet supported");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_idElementCarrier_withoutTableArg_steersAtDeleteOnlyPermit(DmlKind kind) {
        // The ID-element data field is the DELETE PK-echo permit. On INSERT / UPDATE a table name
        // does not unlock it, so the steer points at the return shape instead.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmIdPayload { id: ID! }
            input FilmInput { %s }
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): FilmIdPayload @mutation(typeName: %s) }
            """.formatted(inputBody(kind), mutationName(kind), kind.name()));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason)
            .contains("'FilmIdPayload' is carrier-shaped", "data field 'id' echoes the primary key",
                "only @mutation(typeName: DELETE) may return")
            .doesNotContain("not yet supported");
    }

    @ParameterizedTest
    @EnumSource(value = DmlKind.class, names = {"INSERT", "UPDATE"})
    void payload_idElementCarrier_withTableArg_stillGroundsAndHitsPerVerbRejection(DmlKind kind) {
        // The counterpart that keeps the per-verb rejection from reading as dead code: naming the
        // table grounds the payload, so it classifies as a ResultReturnType and reaches the
        // PK-echo-permit rejection rather than the ungrounded-carrier steer above.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmIdPayload { id: ID! }
            input FilmInput { %s }
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): FilmIdPayload @mutation(typeName: %s, table: "film") }
            """.formatted(inputBody(kind), mutationName(kind), kind.name()));

        var mutField = schema.field("Mutation", mutationName(kind));
        assertThat(mutField).isInstanceOf(UnclassifiedField.class);
        var reason = ((UnclassifiedField) mutField).rejection().message();
        assertThat(reason)
            .contains("single-record carrier 'FilmIdPayload'", "PK-echo permit")
            .doesNotContain("has no write target");
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
        // matched-key membership): filmId (PK) into WHERE, title into SET. UPSERT is refused
        // upstream before any partition runs.
        return switch (kind) {
            case INSERT -> "title: String";
            case UPDATE -> "filmId: Int! @field(name: \"film_id\"), title: String";
            case DELETE -> "filmId: Int! @field(name: \"film_id\")";
            case UPSERT -> "filmId: Int! @field(name: \"film_id\"), title: String";
        };
    }

    /** The single-input payload leaf: one carrier for every verb since the write-arm fold. */
    private static Class<? extends MutationField> expectedSingleLeaf(DmlKind kind) {
        return MutationField.MutationDmlRecordField.class;
    }

    /** The bulk-input payload leaf: one carrier for every verb since the write-arm fold. */
    private static Class<? extends MutationField> expectedBulkLeaf(DmlKind kind) {
        return MutationField.MutationBulkDmlRecordField.class;
    }

    /**
     * The {@code @mutation(...)} directive for {@code kind}. INSERT / UPDATE derive their write
     * target from a payload whose single data field is a {@code @table}-element, an errors-shaped
     * sibling notwithstanding: the carrier scan skips errors fields, so a payload carrying one
     * return-derives exactly as a bare payload does. Two kinds of fixture still pass
     * {@code tableArg} to name the write target with {@code @mutation(table:)}: one whose payload
     * is a deliberately broken carrier shape (which return-derives nothing, so the explicit
     * argument is what carries classification past write-target resolution to the behavior under
     * test), and one that is testing the explicit argument itself. DELETE has no return-derived
     * rung and always names its table.
     */
    private static String mutationDirective(DmlKind kind, boolean tableArg) {
        String table = (tableArg || kind == DmlKind.DELETE) ? ", table: \"film\"" : "";
        return "@mutation(typeName: " + kind.name() + table + ")";
    }

    /** Bulk input ({@code [FilmInput!]!}) → list data field; return-derived write target. */
    private static String payloadDml(DmlKind kind, String payloadType) {
        return payloadDml(kind, payloadType, false);
    }

    /** Bulk input with an explicit {@code @mutation(table:)} write target when {@code tableArg}. */
    private static String payloadDml(DmlKind kind, String payloadType, boolean tableArg) {
        return """
            type Film @table(name: "film") { title: String }
            input FilmInput { %s }
            %s
            type Query { x: String }
            type Mutation { %s(in: [FilmInput!]!): FilmPayload %s }
            """.formatted(inputBody(kind), payloadType, mutationName(kind), mutationDirective(kind, tableArg));
    }

    /** Single input ({@code FilmInput!}) → single data field; return-derived write target. */
    private static String payloadDmlSingleInput(DmlKind kind, String payloadType) {
        return payloadDmlSingleInput(kind, payloadType, false);
    }

    /** Single input with an explicit {@code @mutation(table:)} write target when {@code tableArg}. */
    private static String payloadDmlSingleInput(DmlKind kind, String payloadType, boolean tableArg) {
        return """
            type Film @table(name: "film") { title: String }
            input FilmInput { %s }
            %s
            type Query { x: String }
            type Mutation { %s(in: FilmInput!): FilmPayload %s }
            """.formatted(inputBody(kind), payloadType, mutationName(kind), mutationDirective(kind, tableArg));
    }

    private static long countMatches(String src, Pattern pattern) {
        return pattern.matcher(src).results().count();
    }
}
