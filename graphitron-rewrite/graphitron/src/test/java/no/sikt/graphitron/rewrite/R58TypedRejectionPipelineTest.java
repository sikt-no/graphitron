package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ConditionJoinReportable;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R58 Phase D: end-to-end coverage for migrated producer sites that now emit
 * {@link Rejection.AuthorError.UnknownName} carrying a non-empty {@code candidates()} list,
 * not just rendered prose. Each test exercises one factory by building a representative SDL
 * fragment whose classifier path resolves through the migrated producer; the assertion is that
 * the rejection pattern-matches {@code UnknownName} with the expected {@code attemptKind} and a
 * populated candidate list. This pins the typed-shape contract that R18 (LSP fix-its) and other
 * downstream consumers consume.
 *
 * <p>Out of scope: producer sites whose carrier widening is tracked under R66
 * ({@code ArgumentRef.ScalarArg.UnboundArg.reason}, {@code ParsedPath.errorMessage},
 * {@code InputFieldResolution.Unresolved.reason}, {@code EnumValidation.Mismatch} message-list,
 * {@code keyColumnErrors} list); those still flatten to {@link Rejection.AuthorError.Structural}.
 */
@PipelineTier
class R58TypedRejectionPipelineTest {

    @Test
    void unknownColumn_onScalarFieldFiltersTypedShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                bogusColumn: String @field(name: "bogus_column")
            }
            """);

        var field = schema.field("Film", "bogusColumn");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("bogus_column");
        assertThat(unknown.candidates()).isNotEmpty();
        assertThat(unknown.candidates()).anyMatch(c -> c.equalsIgnoreCase("title"));
    }

    @Test
    void unknownTypeName_onNodeIdReferenceTypedShape() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                refToBogus: ID @nodeId(typeName: "BogusNonExistentType")
            }
            """);

        var field = schema.field("Film", "refToBogus");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.TYPE_NAME);
        assertThat(unknown.attempt()).isEqualTo("BogusNonExistentType");
        assertThat(unknown.candidates()).isNotEmpty();
    }

    @Test
    void unknownServiceMethod_typedShapeSurvivesWrapperPrefix() {
        // The wrapper site in ServiceDirectiveResolver prefixes "service method could not be
        // resolved — " onto the producer's typed UnknownName via Rejection.prefixedWith;
        // assert the typed components survive that wrap.
        var schema = TestSchemaHelper.buildSchema("""
            type Query {
                noSuch: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "bogusNonExistentMethod"})
            }
            """);

        var field = schema.field("Query", "noSuch");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.SERVICE_METHOD);
        assertThat(unknown.attempt()).isEqualTo("bogusNonExistentMethod");
        assertThat(unknown.candidates()).isNotEmpty();
        assertThat(unknown.message()).startsWith("service method could not be resolved — ");
    }

    @Test
    void directiveConflict_serviceMutationCarriesTypedDirectivesList() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { x: String }
            type Mutation {
                bothDirectives(in: String): String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                    @mutation(typeName: INSERT)
            }
            """);

        var field = schema.field("Mutation", "bothDirectives");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);

        var conflict = (Rejection.InvalidSchema.DirectiveConflict) rejection;
        assertThat(conflict.directives()).containsExactly("service", "mutation");
    }

    @Test
    void directiveConflict_typeLevelTableRecordMutualExclusion() {
        // @table and @record on the same type are pairwise mutually exclusive; the
        // detectTypeDirectiveConflict helper now returns a typed DirectiveConflict, and the
        // TypeBuilder caller projects it through unchanged (no Rejection.structural rewrap).
        var schema = TestSchemaHelper.buildSchema("""
            type Query { x: String }
            type Conflicted @table(name: "film") @record(record: {className: "no.sikt.graphitron.rewrite.TestDtoStub"}) {
                title: String
            }
            """);

        var type = schema.type("Conflicted");
        assertThat(type).isInstanceOf(UnclassifiedType.class);

        var rejection = ((UnclassifiedType) type).rejection();
        assertThat(rejection).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);

        var conflict = (Rejection.InvalidSchema.DirectiveConflict) rejection;
        assertThat(conflict.directives()).containsExactly("table", "record");
    }

    @Test
    void conditionJoinReportable_implementedByExpectedFourVariants() {
        // Phase G: the ConditionJoinReportable capability replaces the four-overload
        // SplitRowsMethodEmitter.unsupportedReason and the matching 4-arm
        // validateVariantIsImplemented chain. The implementing set is exactly the four ChildField
        // variants whose join paths can carry a condition step; assert the seal stays accurate so
        // a future leaf addition can't silently bypass the validator gate.
        assertThat(ConditionJoinReportable.class).isAssignableFrom(ChildField.SplitTableField.class);
        assertThat(ConditionJoinReportable.class).isAssignableFrom(ChildField.SplitLookupTableField.class);
        assertThat(ConditionJoinReportable.class).isAssignableFrom(ChildField.RecordTableField.class);
        assertThat(ConditionJoinReportable.class).isAssignableFrom(ChildField.RecordLookupTableField.class);
        // Other ChildField leaves should NOT carry this capability (LookupTableField has a join
        // path but its emitter doesn't share the condition-join predicate).
        assertThat(ConditionJoinReportable.class.isAssignableFrom(ChildField.LookupTableField.class)).isFalse();
        assertThat(ConditionJoinReportable.class.isAssignableFrom(ChildField.TableField.class)).isFalse();
    }

    @Test
    void unclassifiedField_validationErrorPreservesUnknownNameThroughPrefixedWith() {
        // Phase I + E + D combined: when validateUnclassifiedField projects a typed UnknownName
        // through prefixedWith("Field 'X.y': "), the resulting ValidationError.rejection() still
        // pattern-matches UnknownName so the candidates list survives onto the validator log.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                bogusColumn: String @field(name: "bogus_column")
            }
            """);
        var validationErrors = new GraphitronSchemaValidator().validate(schema);

        var matched = validationErrors.stream()
            .filter(e -> e.coordinate() != null && e.coordinate().equals("Film.bogusColumn"))
            .toList();
        assertThat(matched).isNotEmpty();
        var ve = matched.get(0);
        assertThat(ve.rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) ve.rejection();
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("bogus_column");
        assertThat(unknown.candidates()).isNotEmpty();
        assertThat(ve.message()).startsWith("Field 'Film.bogusColumn': ");
        assertThat(ve.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
    }

    @Test
    void unknownColumn_throughNestedRewrapPreservesTypedShape() {
        // Phase E: classifyChildFieldOnTableType rewraps a nested UnclassifiedField with a
        // "nested type 'X' field 'Y': " prefix. Pre-Phase-E this collapsed any inner
        // UnknownName to a Structural; Phase E uses Rejection.prefixedWith so the typed
        // components (attempt, candidates, attemptKind) survive the rewrap. Without this,
        // an LSP fix-it on a nested column miss would have to re-derive candidates by
        // re-running the classifier.
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films: [Film] }
            type Film @table(name: "film") {
                title: String
                outerWrapper: NestedTitleHolder
            }
            type NestedTitleHolder {
                bogusInnerColumn: String @field(name: "bogus_inner_column")
            }
            """);

        var field = schema.field("Film", "outerWrapper");
        assertThat(field).isInstanceOf(UnclassifiedField.class);

        var rejection = ((UnclassifiedField) field).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.UnknownName.class);

        var unknown = (Rejection.AuthorError.UnknownName) rejection;
        assertThat(unknown.attemptKind()).isEqualTo(Rejection.AttemptKind.COLUMN);
        assertThat(unknown.attempt()).isEqualTo("bogus_inner_column");
        assertThat(unknown.candidates()).isNotEmpty();
        // The rendered message carries the nested-rewrap prefix prepended to the inner summary;
        // the candidateHint suffix sits at the end (rendered by UnknownName.message()).
        assertThat(unknown.message())
            .startsWith("nested type 'NestedTitleHolder' field 'bogusInnerColumn': ")
            .contains("could not be resolved")
            .contains("did you mean:");
    }
}
