package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SourceShape;

import java.util.Set;

/**
 * How a child-field fetcher reads its parent's backing object off the GraphQL source, as one
 * value pairing the narrowing prelude with the source expression. The two halves only mean
 * anything together: a source expression with no prelude reads the wrong object under the
 * {@code Outcome} wrapper transport ({@code ClassCastException} on every request), and a prelude
 * with no repointed source expression narrows for nothing. Minting them as one value makes the
 * split unrepresentable; an emitter that has not obtained a binding has no source expression to
 * write.
 *
 * <p>{@link #of} is the single producer. It splices the binding out of two facts the caller
 * already holds: the parent's {@link SourceShape} (a {@code @table}-backed parent projects its
 * own table row; a class-backed parent hands a producer object) and, on the record arm, whether
 * the parent type owns an errors field on the {@code WrapperArm} transport
 * ({@link FetcherEmitter#hasWrapperArmErrors}, computed once per emitted type in
 * {@link TypeFetcherGenerator#generateTypeSpec}, which every entry point routes through). That
 * per-type predicate keys on the {@code WrapperArm} transport specifically; its classify-time
 * sibling,
 * {@code FieldBuilder.carrierPayloadHasErrorsField} (any errors-shaped payload field, the signal
 * that mints {@link no.sikt.graphitron.rewrite.model.SourceEnvelope} on the two envelope-carrying
 * leaves), answers a broader question on a narrower population. The two agree wherever both
 * apply because the wrapper transport is granted exactly to service-produced payloads whose SDL
 * carries an errors-shaped field; this binding is the type-level derivation of the same fork
 * {@code SourceEnvelope} carries leaf-level.
 *
 * <p>The one thing that varies per consumer is the escape the prelude returns when the parent
 * carries no readable backing on this request ({@code CompletableFuture.completedFuture(null)}
 * in async fetchers, a null-data {@code DataFetcherResult} in sync ones, plain {@code null} in
 * inline reads), so {@link #prelude} takes it as a parameter and everything else is derived.
 * {@code subject} is the expression the GraphQL source arrives as: {@code env.getSource()} in
 * env-dependent fetchers, the {@code source} parameter in {@code LightFetcher} reads.
 */
public sealed interface ParentSourceBinding {

    /**
     * Statements emitted before any read of the parent's backing object: narrows or guards
     * {@code subject}, escaping with {@code return <escape>;} when there is nothing to read.
     * Empty on the {@link TableRow} arm.
     */
    CodeBlock prelude(CodeBlock subject, CodeBlock escape);

    /** The expression the parent's backing object is read from, valid after {@link #prelude}. */
    CodeBlock sourceExpr(CodeBlock subject);

    /**
     * The producer. {@link SourceShape#Table} wins unconditionally: a table-backed parent's
     * source is its own projected row, never null mid-query and never {@code Outcome}-wrapped
     * (the wrapper transport is granted only to service-produced payloads, which classify
     * class-backed). On the {@link SourceShape#Record} arm, {@code outcomeWrapped} forks
     * {@link OutcomeRecord} from {@link DirectRecord}.
     */
    static ParentSourceBinding of(SourceShape parentShape, boolean outcomeWrapped, String outputPackage) {
        if (parentShape == SourceShape.Table) {
            return new TableRow();
        }
        return outcomeWrapped ? new OutcomeRecord(outputPackage) : new DirectRecord();
    }

    /**
     * The parent is this type's own projected table row: never null mid-query, never
     * {@code Outcome}-wrapped. Empty prelude; the backing object is the subject itself.
     */
    record TableRow() implements ParentSourceBinding {
        @Override public CodeBlock prelude(CodeBlock subject, CodeBlock escape) {
            return CodeBlock.of("");
        }
        @Override public CodeBlock sourceExpr(CodeBlock subject) {
            return subject;
        }
    }

    /**
     * A producer-handed backing object with no wrapper transport in play. The null-source guard
     * is load-bearing, not defensive: the {@code LocalContext} errors transport fires the
     * data-channel fetcher with {@code data(null)}, so a consumer that skips the guard throws
     * {@code NullPointerException} on that transport's error arm.
     */
    record DirectRecord() implements ParentSourceBinding {
        @Override public CodeBlock prelude(CodeBlock subject, CodeBlock escape) {
            return CodeBlock.builder()
                .beginControlFlow("if ($L == null)", subject)
                .addStatement("return $L", escape)
                .endControlFlow()
                .build();
        }
        @Override public CodeBlock sourceExpr(CodeBlock subject) {
            return subject;
        }
    }

    /**
     * A producer-handed backing object behind the wrapper transport: the source is a non-null
     * {@code Outcome}, the backing object lives in {@code Success.value()}, and the
     * {@code ErrorList} arm escapes (the sibling errors field renders the error list). The
     * narrowed {@code success} local the prelude binds is what {@link #sourceExpr} reads, so the
     * two halves cannot be consumed apart.
     */
    record OutcomeRecord(String outputPackage) implements ParentSourceBinding {
        @Override public CodeBlock prelude(CodeBlock subject, CodeBlock escape) {
            var successClass = ClassName
                .get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME)
                .nestedClass(OutcomeClassGenerator.SUCCESS_CLASS);
            return CodeBlock.builder()
                .beginControlFlow("if (!($L instanceof $T<?> success))", subject, successClass)
                .addStatement("return $L", escape)
                .endControlFlow()
                .build();
        }
        @Override public CodeBlock sourceExpr(CodeBlock subject) {
            return CodeBlock.of("success.value()");
        }
    }

    // -----------------------------------------------------------------------
    // The parent-source posture partition
    // -----------------------------------------------------------------------
    //
    // A second partition over the GraphitronField leaf vocabulary (the first is the dispatch
    // status on TypeFetcherGenerator), declaring for every field leaf how its fetcher relates
    // to the parent's backing object. GeneratorCoverageTest pins it exhaustive and disjoint, so
    // a new field leaf fails the build until its author answers the outcome question.

    /**
     * Leaves whose fetcher reads the parent's backing object through an outcome-answering seam:
     * the batched builders and polymorphic emitters consume this binding (after the deletion of
     * the source-defaulting {@code GeneratorUtils.buildRecordParentKeyExtraction} overload,
     * obtaining a binding is how those builders get a source expression at all, so the compiler
     * enforces consumption); the inline reads arm-switch through
     * {@code FetcherEmitter.bind}'s fork onto the same producer; the two
     * {@link no.sikt.graphitron.rewrite.model.SourceEnvelope}-carrying leaves answer the same
     * question through their classify-time envelope, this binding's leaf-level twin.
     *
     * <p>The degenerate connection arm of {@link ChildField.InterfaceField} /
     * {@link ChildField.UnionField} (the all-unbound participant set) routes to the root-shaped
     * connection emitter, which reads no parent source at all; the leaves stay in this bucket
     * for their parent-reading arms.
     */
    Set<Class<? extends GraphitronField>> BINDING_SEAM_LEAVES = Set.of(
        ChildField.BatchedTableField.class,
        ChildField.BatchedTableInterfaceField.class,
        ChildField.InterfaceField.class,
        ChildField.UnionField.class,
        ChildField.BatchedInterfaceField.class,
        ChildField.BatchedUnionField.class,
        ChildField.RecordReadField.class,
        ChildField.NestingField.class,
        ChildField.SingleRecordIdField.class,
        ChildField.RecordCompositeField.class);

    /**
     * Leaves whose fetcher reads no parent backing object: root fetchers (the source is the
     * root object), the errors field's own transports (they read the {@code Outcome} /
     * localContext error channel itself, which is the outcome-aware read), and input-object
     * leaves (attached to input types; no fetcher is emitted at all).
     */
    Set<Class<? extends GraphitronField>> NO_PARENT_SOURCE_LEAVES = Set.of(
        QueryField.QueryNodeField.class,
        QueryField.QueryNodesField.class,
        QueryField.QueryTableField.class,
        QueryField.QueryServiceTableField.class,
        QueryField.QueryServiceRecordField.class,
        QueryField.QueryServicePolymorphicField.class,
        QueryField.QueryServiceTableInterfaceField.class,
        QueryField.QueryTableInterfaceField.class,
        QueryField.QueryInterfaceField.class,
        QueryField.QueryUnionField.class,
        MutationField.DmlTableField.class,
        MutationField.MutationRoutineWriteField.class,
        MutationField.MutationRoutineWriteRecordField.class,
        MutationField.MutationDmlRecordField.class,
        MutationField.MutationBulkDmlRecordField.class,
        MutationField.MutationServiceTableField.class,
        MutationField.MutationServiceRecordField.class,
        MutationField.MutationServicePolymorphicField.class,
        MutationField.MutationServiceTableInterfaceField.class,
        ChildField.ErrorsField.class,
        InputField.ColumnBackedField.class,
        InputField.ColumnBackedReferenceField.class,
        InputField.NestingField.class,
        InputField.UnboundField.class,
        InputField.ConditionOwnedField.class);

    /**
     * Leaves that read the parent's backing object without an outcome-answering seam and
     * therefore cannot appear beside a {@code WrapperArm} errors field. Most are
     * inventory-absent under a class-backed payload by classifier construction (the
     * table-projection family needs a SELECT-projected parent, so
     * {@code FieldBuilder.classifyChildFieldOnResultType} never mints them); the child
     * {@code @service} leaves and the DML RETURNING id carrier are mintable on record parents
     * but read {@code env.getSource()} unconditionally, so
     * {@code GraphitronSchemaValidator.validateWrapperArmSiblingPosture} turns the combination
     * into a build-time rejection rather than a request-time {@code ClassCastException}.
     * {@link GraphitronField.UnclassifiedField} belongs here for completeness; the
     * {@code PropertyDataFetcher} registration-escape family it heads is rejected by the
     * separate {@code validateOutcomeChildArmSwitch} invariant.
     */
    Set<Class<? extends GraphitronField>> WRAPPER_INADMISSIBLE_LEAVES = Set.of(
        ChildField.ColumnBackedField.class,
        ChildField.ColumnBackedReferenceField.class,
        ChildField.ParticipantColumnReferenceField.class,
        ChildField.TableField.class,
        ChildField.TableInterfaceField.class,
        ChildField.PivotField.class,
        ChildField.BatchedPivotField.class,
        ChildField.PivotSlotField.class,
        ChildField.ComputedField.class,
        ChildField.SingleRecordIdFieldFromReturning.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        GraphitronField.UnclassifiedField.class);
}
