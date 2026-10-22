package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.render.FetcherResult;
import no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ErrorArmAnswer;
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
 * apply because the wrapper transport is granted exactly to producer-returned payloads (root
 * {@code @service}, DML and {@code @routine} carriers) whose SDL carries an errors-shaped field;
 * this binding is the type-level derivation of the same fork
 * {@code SourceEnvelope} carries leaf-level.
 *
 * <p>What varies per consumer is the escape the prelude returns when the parent carries no
 * readable backing on this request. Its envelope is the fetcher's return shape
 * ({@link EscapeForm}: a plain value in inline reads, a {@code DataFetcherResult} in sync
 * fetchers, a completed future in async ones), and on the {@link OutcomeRecord} arm its value is
 * the field's {@link ErrorArmAnswer}: {@code null} for a nullable data field, the empty list for
 * a non-null plain list. {@link #prelude} takes both and renders the escape itself, so no caller
 * spells the escape value. {@code subject} is the expression the GraphQL source arrives as:
 * {@code env.getSource()} in env-dependent fetchers, the {@code source} parameter in
 * {@code LightFetcher} reads.
 */
public sealed interface ParentSourceBinding {

    /**
     * Statements emitted before any read of the parent's backing object: narrows or guards
     * {@code subject}, escaping with {@code return <escape>;} when there is nothing to read.
     * Empty on the {@link TableRow} arm. The escape is rendered in {@code form}; only the
     * {@link OutcomeRecord} arm reads {@code answer} (the field at {@code coordinate}'s
     * {@link ErrorArmAnswer}), since {@link ErrorArmAnswer#NONE} is legal on every other arm.
     */
    CodeBlock prelude(CodeBlock subject, String coordinate, ErrorArmAnswer answer, EscapeForm form);

    /** The expression the parent's backing object is read from, valid after {@link #prelude}. */
    CodeBlock sourceExpr(CodeBlock subject);

    /**
     * The producer. {@link SourceShape#Table} wins unconditionally: a table-backed parent's
     * source is its own projected row, never null mid-query and never {@code Outcome}-wrapped
     * (the wrapper transport is granted only to producer-returned payloads, which classify
     * class-backed or as producer-bound record carriers). On the {@link SourceShape#Record} arm, {@code outcomeWrapped} forks
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
        @Override public CodeBlock prelude(CodeBlock subject, String coordinate, ErrorArmAnswer answer,
                EscapeForm form) {
            return CodeBlock.of("");
        }
        @Override public CodeBlock sourceExpr(CodeBlock subject) {
            return subject;
        }
    }

    /**
     * A producer-handed backing object with no wrapper transport in play. The null-source guard
     * covers a producer that hands down no backing object for this child (a list element or
     * nested object the producer left null), so a consumer reading straight off the source does
     * not throw {@code NullPointerException}. The escape is {@code null} in the form's envelope
     * whatever the field's answer: without the wrapper transport there is no error arm.
     */
    record DirectRecord() implements ParentSourceBinding {
        @Override public CodeBlock prelude(CodeBlock subject, String coordinate, ErrorArmAnswer answer,
                EscapeForm form) {
            return CodeBlock.builder()
                .beginControlFlow("if ($L == null)", subject)
                .addStatement("return $L", form.nullValue())
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
     * {@code ErrorList} arm escapes with the field's {@link ErrorArmAnswer} (the sibling errors
     * field renders the error list). A {@code Success} whose value is {@code null} (a producer
     * that returned {@code null}) escapes the same way, which is {@link DirectRecord}'s
     * null-source guard on the wrapped arm: nothing was produced either way. The narrowed
     * {@code success} local the prelude binds is what {@link #sourceExpr} reads, so the two halves
     * cannot be consumed apart.
     */
    record OutcomeRecord(String outputPackage) implements ParentSourceBinding {
        @Override public CodeBlock prelude(CodeBlock subject, String coordinate, ErrorArmAnswer answer,
                EscapeForm form) {
            var successClass = ClassName
                .get(outputPackage + ".schema", OutcomeClassGenerator.CLASS_NAME)
                .nestedClass(OutcomeClassGenerator.SUCCESS_CLASS);
            return CodeBlock.builder()
                .beginControlFlow("if (!($L instanceof $T<?> success) || success.value() == null)",
                    subject, successClass)
                .addStatement("return $L", errorArmEscape(coordinate, answer, form))
                .endControlFlow()
                .build();
        }
        @Override public CodeBlock sourceExpr(CodeBlock subject) {
            return CodeBlock.of("success.value()");
        }
    }

    /**
     * The value a data field of an outcome payload answers when nothing was produced, in
     * {@code form}'s envelope: {@code null} for {@link ErrorArmAnswer#NULL}, the empty list for
     * {@link ErrorArmAnswer#EMPTY_LIST}. Shared by {@link OutcomeRecord#prelude} and the leaf-level
     * envelope narrowing in {@code FetcherEmitter}, so the error arm has one spelling.
     * {@link ErrorArmAnswer#NONE} throws: the classifier rejects a data field without an answer
     * ({@code FieldBuilder}'s {@code NonNullableSuccessProjectionField} check), so a leaf reaching
     * here with one is a generator bug.
     */
    static CodeBlock errorArmEscape(String coordinate, ErrorArmAnswer answer, EscapeForm form) {
        return switch (answer) {
            case NULL -> form.nullValue();
            case EMPTY_LIST -> form.wrap(CodeBlock.of("$T.of()", ClassName.get("java.util", "List")));
            case NONE -> throw new IllegalStateException(
                "Graphitron generator bug (outcome error arm): data field '" + coordinate
                + "' has no error-arm answer; a non-null single or connection beside an errors"
                + " field is rejected at classify time");
        };
    }

    /**
     * The envelope a fetcher returns its escape in, matching its declared return type. Each arm
     * renders the field's data value into that envelope ({@link #wrap}) and names the envelope's
     * null escape ({@link #nullValue}), which is the spelling each family used before the escape
     * moved into the binding.
     */
    sealed interface EscapeForm {
        /** {@code data} in this envelope. */
        CodeBlock wrap(CodeBlock data);

        /** The envelope's escape for a field that answers {@code null}. */
        CodeBlock nullValue();

        /** A plain value: the inline reads and leaf-level envelope fetchers. */
        record Plain() implements EscapeForm {
            @Override public CodeBlock wrap(CodeBlock data) {
                return data;
            }
            @Override public CodeBlock nullValue() {
                return CodeBlock.of("null");
            }
        }

        /** A synchronous {@code DataFetcherResult<V>}, null data on the null escape. */
        record Sync(TypeName valueType) implements EscapeForm {
            @Override public CodeBlock wrap(CodeBlock data) {
                return CodeBlock.of("$T.<$T>newResult().data($L).build()",
                    ClassName.get("graphql.execution", "DataFetcherResult"),
                    FetcherResult.boxed(valueType), data);
            }
            @Override public CodeBlock nullValue() {
                return wrap(CodeBlock.of("null"));
            }
        }

        /**
         * An asynchronous {@code CompletableFuture<DataFetcherResult<V>>}. The null escape is a
         * future completed with {@code null}, which graphql-java renders as a {@code null} field.
         */
        record Async(TypeName valueType) implements EscapeForm {
            @Override public CodeBlock wrap(CodeBlock data) {
                return CodeBlock.of("$T.completedFuture($L)",
                    ClassName.get("java.util.concurrent", "CompletableFuture"), new Sync(valueType).wrap(data));
            }
            @Override public CodeBlock nullValue() {
                return CodeBlock.of("$T.completedFuture(null)",
                    ClassName.get("java.util.concurrent", "CompletableFuture"));
            }
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
     * {@code FetcherEmitter.bind}'s fork onto the same producer; the three
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
        ChildField.SingleRecordIdFieldFromReturning.class,
        ChildField.RecordCompositeField.class);

    /**
     * Leaves whose fetcher reads no parent backing object: root fetchers (the source is the
     * root object), the errors field's own transports (they read the {@code Outcome} error
     * channel itself, which is the outcome-aware read), and input-object
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
     * {@code @service} leaves are mintable on record parents but read {@code env.getSource()}
     * unconditionally, so
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
        ChildField.SameRowTableField.class,
        ChildField.TableInterfaceField.class,
        ChildField.PivotField.class,
        ChildField.BatchedPivotField.class,
        ChildField.PivotSlotField.class,
        ChildField.ComputedField.class,
        ChildField.ServiceTableField.class,
        ChildField.ServiceRecordField.class,
        GraphitronField.UnclassifiedField.class);
}
