package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.generators.util.LightFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ResultKeyAliasedField;
import no.sikt.graphitron.rewrite.model.SourceEnvelope;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.ValueLocator;

import java.util.List;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Binds a single classified field to its {@code DataFetcher}: the registration value the
 * {@code codeRegistry.dataFetcher(coords, ...)} call receives, paired with the named
 * {@code <Type>Fetchers} method that owns the read (when the read is reified here).
 *
 * <p>Consumed by {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter}
 * (emits the registration value) and {@link TypeFetcherGenerator} (collects the reified method
 * onto the owning {@code <Type>Fetchers} class). The fetcher logic lives in one place so the
 * classifier-to-registration pipeline stays the only path from schema model to a
 * {@code DataFetcher}. {@link #bind} returns a {@link FetcherBinding} pairing the registration
 * value with the reified method (when any), so the two cannot drift.
 */
public final class FetcherEmitter {

    private static final ClassName DATA_FETCHING_ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    /** The default source binding for an inline read: {@code env.getSource()}. */
    private static final CodeBlock ENV_SOURCE = CodeBlock.of("env.getSource()");

    private FetcherEmitter() {}

    /**
     * The binding between a classified field and its {@code DataFetcher}. {@link Reified} owns a
     * named {@code <Type>Fetchers} method here; {@link Inline} leaves the method to
     * {@link TypeFetcherGenerator}'s switch (method-backed variants) or carries an inline value
     * (shapes not reified here).
     */
    public sealed interface FetcherBinding {
        /** The expression the {@code codeRegistry.dataFetcher(coords, ...)} call receives. */
        CodeBlock registrationValue();

        /** No method emitted by {@code bind}; the registration value is carried as-is. */
        record Inline(CodeBlock registrationValue) implements FetcherBinding {}

        /**
         * Reified into a named {@code public static} method on {@code <Type>Fetchers}.
         * {@code registrationValue} is either the bare reference {@code Fetchers::field}
         * (env-dependent read) or the light wrapper {@code new LightFetcher<>(Fetchers::field)}
         * (source-only read).
         */
        record Reified(MethodSpec method, CodeBlock registrationValue) implements FetcherBinding {}
    }

    /** Builds a source-only reified binding: a {@code (Object source)} method wrapped in {@code LightFetcher}. */
    private static FetcherBinding sourceOnly(
            String name, ClassName fetchersClass, String outputPackage, CodeBlock body) {
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(Object.class, "source")
            .addCode(body)
            .build();
        var lightFetcher = ClassName.get(outputPackage + ".util", LightFetcherClassGenerator.CLASS_NAME);
        return new FetcherBinding.Reified(method,
            CodeBlock.of("new $T<>($T::$L)", lightFetcher, fetchersClass, name));
    }

    /** Builds an env-dependent reified binding: a {@code (DataFetchingEnvironment env)} method, bare reference. */
    private static FetcherBinding envDependent(String name, ClassName fetchersClass, CodeBlock body) {
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(DATA_FETCHING_ENV, "env")
            .addCode(body)
            .build();
        return new FetcherBinding.Reified(method, CodeBlock.of("$T::$L", fetchersClass, name));
    }

    /**
     * Whether {@code fields} include an errors field on the {@code Outcome} wrapper transport.
     * When true the type is a flipped outcome payload: its fetchers receive a non-null
     * {@code Outcome} as {@code env.getSource()}, so every data-channel sibling must arm-switch on
     * {@code Success}. The signal is the parent's own {@code WrapperArm} errors field, knowable at
     * generation time without re-walking the classifier.
     *
     * <p>Single home for the predicate so its two consumers, the registration-site routing in
     * {@code FetcherRegistrationsEmitter} and the DataLoader-method emission in
     * {@code TypeFetcherGenerator}, cannot drift.
     */
    public static boolean hasWrapperArmErrors(List<? extends GraphitronField> fields) {
        return fields.stream().anyMatch(f -> f instanceof ChildField.ErrorsField ef
            && ef.transport() instanceof ChildField.Transport.WrapperArm);
    }

    /**
     * Whether a nested object type owns any fetcher, i.e. any classified field. Every classified
     * field {@link #bind}s to a fetcher, so a nested type owning one gets its own
     * {@code <Type>Fetchers} class and the registration references into it. The gate's one home
     * is the reach fold ({@link no.sikt.graphitron.rewrite.NestingReach#ownsFetchers}), which
     * the type-unit producer's membership and the registrations emitter read; this is the
     * legacy tree's delegation.
     */
    public static boolean nestedTypeOwnsFetchers(List<? extends GraphitronField> nestedFields) {
        return no.sikt.graphitron.rewrite.NestingReach.ownsFetchers(nestedFields);
    }

    /**
     * Whether {@code field} would resolve to graphql-java's {@code PropertyDataFetcher} (a property
     * read off the source object) rather than a graphitron-emitted fetcher. Under an
     * {@code Outcome} wrapper this is a silent runtime hole: the read would land on the
     * {@code Outcome} object itself rather than arm-switching. The emit-time sources are
     * an {@code ErrorsField} on the {@code PayloadAccessor} transport and a
     * {@code RecordReadField} whose {@link ValueLocator.DefaultRead} locator located nothing
     * (a class-backed parent with no loadable backing), both of which emit
     * {@code PropertyDataFetcher.fetching} in {@link #bindRaw}. The validator consults
     * this predicate so it keys on the emitter's own dispositions rather than re-deriving them.
     * The live case is pinned by {@code FetcherPipelineTest} wiring assertions.
     *
     * <p>Covers only the {@code PropertyDataFetcher} (registration-escape) family, the invariant
     * {@code validateOutcomeChildArmSwitch} enforces, not every non-arm-switching emit path:
     * a {@code ComputedField} or other {@code LightFetcher}-backed leaf needs a SELECT-projected
     * parent and so is inventory-absent under a class-backed {@code @service} payload, which is
     * the scope boundary the arm-switch validator draws.
     *
     * <p>An {@code UnclassifiedField} (no registration at all, so graphql-java installs its
     * default {@code PropertyDataFetcher}) is the third source, but it is absence-of-registration
     * rather than an emitted value, so the validator checks it separately.
     */
    public static boolean resolvesViaPropertyDataFetcher(
            GraphitronField field, GraphitronType.ResultType resultType) {
        return (field instanceof ChildField.ErrorsField ef
                    && ef.transport() instanceof ChildField.Transport.PayloadAccessor)
            || (field instanceof ChildField.RecordReadField rrf
                    && rrf.locator() instanceof ValueLocator.DefaultRead);
    }

    /**
     * Builds the {@code DataFetcher} value expression for {@code field}.
     *
     * @param field         the classified field
     * @param fetchersClass the {@code <TypeName>Fetchers} class that owns the method reference
     *                      for unclassified / catch-all fields; may be {@code null} for nested
     *                      object types without their own fetchers class
     * @param parentTable   the parent type's resolved jOOQ table (for column-backed fields), or
     *                      {@code null} when the parent is not table-backed
     * @param resultType    the parent type's class backing, or {@code null}
     * @param outputPackage the base output package (e.g. {@code no.sikt.graphql})
     * @param parentSource  the parent-source binding minted once per type by
     *                      {@link ParentSourceBinding#of}. On the
     *                      {@link ParentSourceBinding.OutcomeRecord} arm this field is an immediate
     *                      child of an outcome type flipped to the {@code Outcome} wrapper
     *                      transport: its fetcher receives an {@code Outcome} as
     *                      {@code env.getSource()}, so a data-channel field's read must unwrap
     *                      {@code Success} first and resolve null on the {@code ErrorList} arm.
     *                      The errors field itself is exempt (it reads {@code ErrorList.errors()}
     *                      directly via its {@code WrapperArm} transport).
     */
    public static FetcherBinding bind(
            GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, ParentSourceBinding parentSource) {
        // Immediate children of a flipped Outcome payload: inline-resolved data fields arm-switch
        // here; the errors field (WrapperArm transport, handled by bindRaw) and DataLoader/
        // method-backed data fields (their generated fetcher method arm-switches internally) fall
        // through. The fork is on a structural fact the model already carries (inline value vs.
        // method reference), not a parallel allow-list of variants.
        if (parentSource instanceof ParentSourceBinding.OutcomeRecord
                && isInlineArmSwitchedDataField(field)) {
            return armSwitchedInlineDataFetcher(field, fetchersClass, resultType, outputPackage, parentSource);
        }
        return bindRaw(field, fetchersClass, parentTable, resultType, outputPackage);
    }

    /**
     * The dual-source-shape dispatch fetcher for a coordinate reached both as a nesting projection of a
     * {@code @table} parent (source is a generic {@link org.jooq.Record}) and as a field of a class-backed
     * result (source is the producer's reflected backing object). graphql-java wires one datafetcher per
     * coordinate, so the two single-arm reads compose into one reified method that dispatches at run time
     * on {@code source instanceof org.jooq.Record}: the {@code Record} arm is the by-typed-column read
     * against the representative parent table ({@code recordArm}), the else arm is the record-backed
     * accessor read. Pattern {@code instanceof} is valid in the Java 17 output target.
     *
     * <p>Two invariants a dual-shape coordinate carries that a single-arm one does not: it always
     * registers its fetcher even where the accessor arm alone would be equivalent to graphql-java's default
     * {@code PropertyDataFetcher} (the default cannot read a jOOQ {@code Record} column); and it keeps the
     * env-dependent registration form when the accessor arm injects the environment
     * ({@link #isEnvDependentAccessorRead}). The result type is always a class-backed
     * {@code JavaRecordType} / {@code PojoResultType.Backed}: the {@code JooqRecordCarrier} + nesting mix is
     * a validate-time rejection ({@link no.sikt.graphitron.rewrite.model.ReachableSourceShape#REJECTED}),
     * so it never reaches this emitter.
     */
    public static FetcherBinding bindDualShape(
            GraphitronField accessorField, ChildField recordArm,
            ClassName fetchersClass, TableRef nestingParentTable,
            GraphitronType.ResultType resultType, String outputPackage) {
        AccessorResolution.Resolved accessor =
            accessorField instanceof ChildField.RecordReadField rrf
                    && rrf.locator() instanceof ValueLocator.JavaAccessor ja
                ? ja.accessor()
                : null;
        String fqClassName = (resultType instanceof GraphitronType.JavaRecordType jrt)
            ? jrt.fqClassName()
            : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
        var backingClass = ClassName.bestGuess(fqClassName);
        boolean envDependent = isEnvDependentAccessorRead(accessorField);
        CodeBlock accessorRead = recordBackedAccessorRead(backingClass, accessor, CodeBlock.of("source"));
        CodeBlock columnRead = dualShapeRecordArmRead(recordArm, nestingParentTable);
        var body = CodeBlock.builder();
        if (envDependent) {
            body.add("$T source = env.getSource();\n", Object.class);
        }
        body.add("if (source instanceof $T rec) {\n", RECORD)
            .indent().add("return $L;\n", columnRead).unindent()
            .add("}\n")
            .add("return $L;\n", accessorRead);
        return envDependent
            ? envDependent(accessorField.name(), fetchersClass, body.build())
            : sourceOnly(accessorField.name(), fetchersClass, outputPackage, body.build());
    }

    /**
     * The nesting-arm column read for a dual-shape dispatch, off the pattern-bound {@code rec} record:
     * {@code rec.get(Tables.X.COL)}, or the NodeId-encoded projection when the column field carries an
     * encode compaction. Mirrors {@link #bindRaw}'s {@code ColumnBackedField} arm, differing only in the source
     * subject (the narrowed {@code rec} local rather than {@code ((Record) source)}).
     */
    private static CodeBlock dualShapeRecordArmRead(ChildField recordArm, TableRef parentTable) {
        // A pivot-edge representative reads its slot by derived name off the pivot record; the
        // by-name form works identically against a compatible nesting parent's row, so a
        // pivot-first representative serves both generic-Record sources.
        if (recordArm instanceof ChildField.PivotSlotField slot) {
            return CodeBlock.of("rec.get($T.field($T.name($S)))", DSL, DSL, slot.readName());
        }
        var cf = (ChildField.ColumnBackedField) recordArm;
        if (cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys enc) {
            var encode = CodeBlock.builder()
                .add("$T.$L(", enc.encodeMethod().encoderClass(), enc.encodeMethod().methodName());
            for (int i = 0; i < cf.columns().size(); i++) {
                if (i > 0) encode.add(", ");
                encode.add("rec.get($T.$L.$L)",
                    CatalogRefs.constantsClass(parentTable), parentTable.javaFieldName(), cf.columns().get(i).javaName());
            }
            return encode.add(")").build();
        }
        // Direct implies arity 1 (the carrier's constructor invariant).
        return CodeBlock.of("rec.get($T.$L.$L)",
            CatalogRefs.constantsClass(parentTable), parentTable.javaFieldName(), cf.columns().get(0).javaName());
    }

    /**
     * The inline-resolved data-channel shapes that can appear as an immediate child of a
     * class-backed {@code Outcome} payload. Each is resolved here by {@link #bindRaw} as a read of
     * the field's own source; under the wrapper transport that read is repointed at
     * {@code success.value()} (see {@link #armSwitchedInlineDataFetcher}). The read is
     * reified into a named {@code <Type>Fetchers} method rather than emitted as an inline lambda.
     *
     * <p>The errors field is excluded (it reads {@code ErrorList.errors} via its
     * {@code WrapperArm} transport). DataLoader/method-backed fields are excluded because their
     * generated fetcher method owns the arm-switch; the registration site emits a plain method
     * reference for them. A {@link ValueLocator.DefaultRead} record read is excluded because
     * nothing was located, so there is no inline read to repoint at {@code success.value()};
     * that combination under an outcome parent is rejected at validate time
     * ({@code validateOutcomeChildArmSwitch} via {@link #resolvesViaPropertyDataFetcher}). This
     * names the shapes whose read is resolved <em>here</em> as a narrowable source read, a
     * structural property of the emit path, not an allow-list.
     */
    private static boolean isInlineArmSwitchedDataField(GraphitronField field) {
        return field instanceof ChildField.NestingField
            || (field instanceof ChildField.RecordReadField rrf
                && !(rrf.locator() instanceof ValueLocator.DefaultRead));
    }

    /**
     * An inline-resolved data-channel child of a flipped outcome type reads off
     * {@code Success.value()} of the non-null {@code Outcome} source and resolves null on the
     * {@code ErrorList} arm. Both halves come from the {@link ParentSourceBinding} producer: the
     * prelude narrows the subject and escapes with {@code null}, and the success-arm read is the
     * field's <em>own</em> read, source-bound to the binding's source expression instead of the
     * subject; record-backed accessors go via the shared {@link #recordBackedAccessorRead} (the
     * same helper {@link #recordReadBinding} uses), so there is no parallel accessor taxonomy.
     */
    private static FetcherBinding armSwitchedInlineDataFetcher(
            GraphitronField field, ClassName fetchersClass,
            GraphitronType.ResultType resultType, String outputPackage,
            ParentSourceBinding parentSource) {
        boolean envDependent = isEnvDependentAccessorRead(field);
        CodeBlock subject = envDependent ? ENV_SOURCE : CodeBlock.of("source");
        CodeBlock body = CodeBlock.builder()
            .add(parentSource.prelude(subject, CodeBlock.of("null")))
            .add(inlineSuccessReturn(field, resultType, parentSource.sourceExpr(subject)))
            .build();
        return envDependent
            ? envDependent(field.name(), fetchersClass, body)
            : sourceOnly(field.name(), fetchersClass, outputPackage, body);
    }

    /**
     * Whether {@code field}'s read needs the {@code DataFetchingEnvironment}. Only a class-backed
     * accessor that injects the environment (a method with parameters: the full-env or per-argument
     * forms in {@link #methodCallValue}) does; jOOQ-record column reads, field reads, and zero-arg
     * accessors are source-only. The accessor lives on the {@link ValueLocator.JavaAccessor} arm,
     * whose parent-shape compatibility is validate-time-checked, so no parent cross-check is
     * needed here.
     */
    private static boolean isEnvDependentAccessorRead(GraphitronField field) {
        if (!(field instanceof ChildField.RecordReadField rrf)
                || !(rrf.locator() instanceof ValueLocator.JavaAccessor ja)) {
            return false;
        }
        return switch (ja.accessor()) {
            case AccessorResolution.FieldRead ignored -> false;
            case AccessorResolution.GetterPrefixed gp -> gp.method().getParameterTypes().length > 0;
            case AccessorResolution.BareName bn -> bn.method().getParameterTypes().length > 0;
        };
    }

    /**
     * The success-arm value expression: the field's own read, source-bound to {@code sourceExpr}
     * (the {@link ParentSourceBinding}'s source expression, {@code success.value()} on the outcome
     * arm). The read shape is an exhaustive switch over the record-read leaf's {@link ValueLocator},
     * mirroring {@link #bindRaw} / {@link #recordReadBinding} so there is no parallel taxonomy:
     * a jOOQ-record column {@code get}, a class-backed accessor call, or the nesting source
     * passthrough. The parent {@code resultType} is consulted only for the cast target, per
     * arm, under the validate-time-checked arm/parent-shape compatibility.
     *
     * <p>{@code encode} is the leaf's encode compaction or {@code null}, and it changes the
     * by-name read's shape for the same reason it does in {@link #recordReadBinding}: an untyped
     * {@code get} yields {@code Object}, which no {@code encode<TypeName>} signature takes. The
     * wrapping itself is the caller's, through {@link #encodedReadReturn}.
     */
    private static CodeBlock inlineSuccessReturn(
            GraphitronField field, GraphitronType.ResultType resultType, CodeBlock sourceExpr) {
        if (field instanceof ChildField.NestingField) {
            return CodeBlock.of("return $L;\n", sourceExpr);
        }
        var rrf = (ChildField.RecordReadField) field;
        var encode = rrf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys e ? e : null;
        CodeBlock read = switch (rrf.locator()) {
            case ValueLocator.TypedColumn tc -> {
                var table = ((GraphitronType.JooqTableRecordType) resultType).table();
                yield CodeBlock.of("(($T) $L).get($T.$L.$L)",
                    RECORD, sourceExpr, CatalogRefs.constantsClass(table), table.javaFieldName(), tc.column().javaName());
            }
            case ValueLocator.ByName bn -> encode == null
                ? CodeBlock.of("(($T) $L).get($T.field($S))", RECORD, sourceExpr, DSL, bn.sqlName())
                : CodeBlock.of("(($T) $L).get($T.field($S, $T.class))",
                    RECORD, sourceExpr, DSL, bn.sqlName(), encodeKeyType(encode));
            case ValueLocator.JavaAccessor ja -> {
                String javaBackingFqcn =
                    resultType instanceof GraphitronType.JavaRecordType jrt ? jrt.fqClassName()
                    : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
                yield recordBackedAccessorRead(
                    ClassName.bestGuess(javaBackingFqcn), ja.accessor(), sourceExpr);
            }
            // Unreachable: isInlineArmSwitchedDataField excludes DefaultRead reads, and the
            // outcome-parent combination is rejected at validate time
            // (validateOutcomeChildArmSwitch via resolvesViaPropertyDataFetcher).
            case ValueLocator.DefaultRead ignored -> throw new IllegalStateException(
                "inline success-projection arm-switch: DefaultRead locator on "
                + field.qualifiedName() + " has no inline read");
        };
        return encodedReadReturn(read, encode);
    }

    private static ClassName successClass(String outputPackage) {
        return outcomeClass(outputPackage)
            .nestedClass(no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator.SUCCESS_CLASS);
    }

    private static ClassName errorListClass(String outputPackage) {
        return outcomeClass(outputPackage)
            .nestedClass(no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator.ERROR_LIST_CLASS);
    }

    private static ClassName outcomeClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.OutcomeClassGenerator.CLASS_NAME);
    }

    /**
     * Emit the {@code source} local for a record-carrier fetcher body, narrowing the
     * {@code Outcome} wrapper when the producer flipped to it ({@code OUTCOME_SUCCESS}).
     *
     * <p>Both paths are cast-free and warning-free. {@code env.getSource()} is {@code <T> T}, so
     * the typed local drives inference: under DIRECT we bind {@code env.getSource()} straight to
     * {@code elementType}. Under the wrapper we bind it to the typed {@code Outcome<elementType>}
     * first, then pattern-match the <em>concrete</em> {@code Success<elementType>}. Because
     * {@code Success<T> implements Outcome<T>} with the same argument, that type test is checked
     * (not a {@code Success<?>} capture), so {@code success.value()} is already {@code elementType}
     * and needs no cast. The {@code ErrorList} arm falls through to {@code return null}.
     */
    private static void emitRecordSourceLocal(
            CodeBlock.Builder body, TypeName elementType, boolean outcomeWrapped, String outputPackage) {
        if (outcomeWrapped) {
            body.add("    $T outcome = env.getSource();\n",
                ParameterizedTypeName.get(outcomeClass(outputPackage), elementType));
            body.add("    if (!(outcome instanceof $T success)) return null;\n",
                ParameterizedTypeName.get(successClass(outputPackage), elementType));
            body.add("    $T source = success.value();\n", elementType);
        } else {
            body.add("    $T source = env.getSource();\n", elementType);
        }
    }

    private static FetcherBinding bindRaw(
            GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage) {
        if (field instanceof ChildField.NestingField) {
            // Source passthrough: the field value is the source object itself.
            return sourceOnly(field.name(), fetchersClass, outputPackage, CodeBlock.of("return source;\n"));
        }
        if (field instanceof ChildField.SingleRecordIdFieldFromReturning idCarrier) {
            // Payload-returning DELETE carrier: read the PK column(s) off each RETURNING row and
            // encode. No follow-up SELECT; the row is gone.
            return envDependent(field.name(), fetchersClass,
                buildSingleRecordIdFromReturningFetcherValue(idCarrier));
        }
        if (field instanceof ChildField.SingleRecordIdField serviceIdCarrier) {
            // ID-element data field on an @service source-record carrier: read the node-key
            // column(s) off each in-memory record and encode. No follow-up SELECT; the records
            // may be deleted rows.
            return envDependent(field.name(), fetchersClass,
                buildSingleRecordIdFetcherValue(serviceIdCarrier, outputPackage));
        }
        if (field instanceof ChildField.RecordCompositeField composite) {
            // @service record-composite carrier: narrow Outcome.Success then return the producer's
            // composite(s) straight off env.getSource(); no re-fetch, no DataLoader.
            return envDependent(field.name(), fetchersClass,
                buildRecordCompositeFetcherValue(composite, outputPackage));
        }
        if (field instanceof ChildField.ErrorsField ef) {
            // The Transport discriminator rides on the field-level model (resolved at classify
            // time with the parent carrier's channel in scope), so this emission never re-walks
            // the parent. LocalContext is populated by the catch arm of an
            // ErrorChannel.LocalContext-bound carrier.
            return switch (ef.transport()) {
                case ChildField.Transport.PayloadAccessor ignored -> {
                    var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
                    yield new FetcherBinding.Inline(
                        CodeBlock.of("$T.fetching($S)", propertyDataFetcher, field.name()));
                }
                case ChildField.Transport.LocalContext ignored ->
                    envDependent(field.name(), fetchersClass,
                        CodeBlock.of("return env.getLocalContext();\n"));
                // On the Success arm resolve null (not List.of()) to honour the errors field's
                // SDL nullability on the wire. The NonNullableErrorsField classify-time rule
                // guarantees the field is nullable, so null is always a legal success-arm value.
                case ChildField.Transport.WrapperArm ignored ->
                    sourceOnly(field.name(), fetchersClass, outputPackage,
                        CodeBlock.of("return source instanceof $T<?> errorList ? errorList.errors() : null;\n",
                            errorListClass(outputPackage)));
            };
        }
        if (field instanceof ChildField.RecordReadField rrf) {
            return recordReadBinding(rrf, resultType, fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.ColumnBackedField cf && parentTable != null) {
            if (cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys enc) {
                // NodeId-encoded projection: pass each key column positionally through
                // encode<TypeName>(c1, ..., cN), one arity-uniform loop. The HelperRef.Encode
                // reference carries both the encoder class and the helper method name, so neither
                // is reconstructed from a raw typeId string at emission time.
                var encoderClass = enc.encodeMethod().encoderClass();
                var body = CodeBlock.builder()
                    .add("$T r = ($T) source;\n", RECORD, RECORD)
                    .add("return $T.$L(", encoderClass, enc.encodeMethod().methodName());
                for (int i = 0; i < cf.columns().size(); i++) {
                    if (i > 0) body.add(", ");
                    body.add("r.get($T.$L.$L)",
                        CatalogRefs.constantsClass(parentTable), parentTable.javaFieldName(), cf.columns().get(i).javaName());
                }
                body.add(");\n");
                return sourceOnly(field.name(), fetchersClass, outputPackage, body.build());
            }
            // Direct implies arity 1 (the carrier's constructor invariant): a plain typed-column read.
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.$L.$L);\n",
                    RECORD, CatalogRefs.constantsClass(parentTable), parentTable.javaFieldName(), cf.columns().get(0).javaName()));
        }
        if (field instanceof ChildField.TableField tf) {
            boolean single = tf.returnType().wrapper() instanceof FieldWrapper.Single;
            if (single) {
                var resultClass = ClassName.get("org.jooq", "Result");
                var resultWildcard = ParameterizedTypeName.get(resultClass, WildcardTypeName.subtypeOf(Object.class));
                // Env-dependent: the single-record multiset is aliased __rk_<resultKey>, so unwrap
                // the Result read by the runtime result key (aliased duplicates each read their own).
                CodeBlock body = CodeBlock.builder()
                    .add("Object raw = (($T) env.getSource()).get($S + env.getField().getResultKey(), $T.class);\n",
                        RECORD, GeneratorUtils.resultKeyPrefix(tf.aliasOwner()), resultClass)
                    .add("return raw instanceof $T r && !r.isEmpty() ? r.get(0) : null;\n", resultWildcard)
                    .build();
                return envDependent(field.name(), fetchersClass, body);
            }
            return columnByAlias(field.name(), fetchersClass, tf.aliasOwner());
        }
        if (field instanceof ChildField.PivotField pvf) {
            // Inline @pivot: the projection is a single-row multiset aliased __rk_<resultKey>
            // (the pivot multiset arm); unwrap it as the single-cardinality TableField read does.
            // The row always exists (a correlated aggregate over an empty set still yields one
            // row of nulls), so the empty-guard is defensive symmetry, not a semantic fork.
            var resultClass = ClassName.get("org.jooq", "Result");
            var resultWildcard = ParameterizedTypeName.get(resultClass, WildcardTypeName.subtypeOf(Object.class));
            CodeBlock body = CodeBlock.builder()
                .add("Object raw = (($T) env.getSource()).get($S + env.getField().getResultKey(), $T.class);\n",
                    RECORD, GeneratorUtils.resultKeyPrefix(pvf.aliasOwner()), resultClass)
                .add("return raw instanceof $T r && !r.isEmpty() ? r.get(0) : null;\n", resultWildcard)
                .build();
            return envDependent(field.name(), fetchersClass, body);
        }
        if (field instanceof ChildField.PivotSlotField slot) {
            // A projection slot reads its aggregate off the pivot record by its derived read
            // name, the same by-name generic-Record read nesting children emit; that lets one
            // registered fetcher per slot coordinate serve both the pivot subselect's Record and
            // a compatible nesting parent's record.
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.field($T.name($S)));\n",
                    RECORD, DSL, DSL, slot.readName()));
        }
        if (field instanceof ChildField.ComputedField cmp) {
            // Wired by name: the type's $project unit inlines the developer's method call
            // aliased to the result key; the read picks the result Record up by that alias.
            return columnByAlias(field.name(), fetchersClass, cmp.aliasOwner());
        }
        if (field instanceof ChildField.ParticipantColumnReferenceField pcrf) {
            // Cross-table participant field on a TableInterfaceType participant: the interface
            // fetcher (TypeFetcherGenerator) projects the column aliased as pcrf.aliasName();
            // read it back from the parent record by alias. The Class<?> parameter on DSL.field
            // carries the column's concrete type so jOOQ's converter returns the right Java value
            // (e.g. enum) when the column is a typed projection rather than a raw SQL identifier.
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.field($T.name($S), $T.class));\n",
                    RECORD, DSL, DSL, pcrf.aliasName(),
                    CatalogRefs.columnType(pcrf.column())));
        }
        if (field instanceof ChildField.ColumnBackedReferenceField crf
                && crf.compaction() instanceof CallSiteCompaction.Direct) {
            // Direct-compaction scalar @reference: the type's $project unit projects an aliased
            // correlated subquery; read the value out of the parent Record by alias. A
            // NodeIdEncodeKeys instance never reaches emission: the validator rejects it as
            // deferred, and one leaking through falls to the ResultKeyAliasedField guard below
            // and fails loudly.
            return columnByAlias(field.name(), fetchersClass, crf.aliasOwner());
        }
        // A ResultKeyAliasedField reaching here would fall through to a plain method-backed
        // reference and never read its __rk_ alias, silently mis-resolving aliased duplicates.
        // Fail loudly: this is the read half of the membership guard that keeps the write and
        // read alias sets from drifting (the write half is the projection producer's
        // default arm).
        if (field instanceof ResultKeyAliasedField) {
            throw new IllegalStateException(
                "ResultKeyAliasedField '" + field.name() + "' (" + field.getClass().getSimpleName()
                    + ") has no result-key-aware read binding; add one that reads by "
                    + "env.getField().getResultKey(), or drop the ResultKeyAliasedField marker.");
        }
        // Method-backed variants: TypeFetcherGenerator's switch owns the method; carry the reference.
        return new FetcherBinding.Inline(CodeBlock.of("$T::$L", fetchersClass, field.name()));
    }

    /**
     * Env-dependent read of an aliased projection off the parent record, keyed by the runtime
     * result key. The projection is aliased {@code <prefix><resultKey>} on the write side, with
     * the prefix composed from the field's own stamped
     * {@link no.sikt.graphitron.rewrite.model.AliasOwner} through the one mint
     * ({@link GeneratorUtils#resultKeyPrefix}), so two aliases of the same reference
     * ({@code a: ref b: ref}) each read their own SELECT term via {@code env.getField().getResultKey()}
     * rather than colliding on a field-named alias, and two participants of one discriminated
     * interface declaring a same-named field read their own qualified term rather than each
     * other's. This is the read half of {@link ResultKeyAliasedField}; it must move in lockstep
     * with the write arms.
     *
     * <p>The lookup is name-based ({@code DSL.field(DSL.name(...))}), not the plain-SQL
     * {@code DSL.field(String)} form: an owner qualifier puts a {@code $} in the alias, and a
     * quoted-name reference addresses the projected alias whatever characters it carries.
     */
    private static FetcherBinding columnByAlias(String name, ClassName fetchersClass,
            no.sikt.graphitron.rewrite.model.AliasOwner aliasOwner) {
        return envDependent(name, fetchersClass,
            CodeBlock.of("return (($T) env.getSource()).get($T.field($T.name($S + env.getField().getResultKey())));\n",
                RECORD, DSL, DSL, GeneratorUtils.resultKeyPrefix(aliasOwner)));
    }

    /**
     * Data-fetcher value for a {@link ChildField.SingleRecordIdField}, the ID-element data field
     * on an {@code @service} source-record carrier. Uses {@link #emitRecordSourceLocal}'s
     * {@code SourceEnvelope} fork to bind the typed {@code XRecord} / {@code List<XRecord>}
     * {@code source} local, then maps each record through the pre-resolved NodeId encoder,
     * reading the node-key column(s) via the typed {@code Tables.X.COL} constants. No database
     * access: the producer's records may be deleted rows, and the encode is total over the
     * in-memory record.
     */
    private static CodeBlock buildSingleRecordIdFetcherValue(
            ChildField.SingleRecordIdField carrier, String outputPackage) {
        var sk = carrier.sourceKey();
        var table = carrier.table();
        var recordType = ((SourceKey.Wrap.TableRecord) sk.wrap()).className();
        var keyColumns = sk.columns();
        var encoder = carrier.encode().encodeMethod();
        boolean many = carrier.returnType().wrapper().isList();
        boolean outcomeWrapped = carrier.envelope() == SourceEnvelope.OUTCOME_SUCCESS;

        var body = CodeBlock.builder();
        if (many) {
            var javaUtilList = ClassName.get("java.util", "List");
            var stringClass = ClassName.get("java.lang", "String");
            var listOfRecord = ParameterizedTypeName.get(javaUtilList, recordType);
            var listOfString = ParameterizedTypeName.get(javaUtilList, stringClass);
            var arrayListOfString = ParameterizedTypeName.get(
                ClassName.get("java.util", "ArrayList"), stringClass);
            emitRecordSourceLocal(body, listOfRecord, outcomeWrapped, outputPackage);
            body.add("    if (source == null) return null;\n");
            body.add("    $T ids = new $T(source.size());\n", listOfString, arrayListOfString);
            body.add("    for ($T row : source) {\n", recordType);
            body.add("        ids.add($T.$L(", encoder.encoderClass(), encoder.methodName());
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) body.add(", ");
                var col = keyColumns.get(i);
                body.add("row.get($T.$L.$L)",
                    CatalogRefs.constantsClass(table), table.javaFieldName(), col.javaName());
            }
            body.add("));\n");
            body.add("    }\n");
            body.add("    return ids;\n");
        } else {
            emitRecordSourceLocal(body, recordType, outcomeWrapped, outputPackage);
            body.add("    if (source == null) return null;\n");
            body.add("    return $T.$L(", encoder.encoderClass(), encoder.methodName());
            for (int i = 0; i < keyColumns.size(); i++) {
                if (i > 0) body.add(", ");
                var col = keyColumns.get(i);
                body.add("source.get($T.$L.$L)",
                    CatalogRefs.constantsClass(table), table.javaFieldName(), col.javaName());
            }
            body.add(");\n");
        }
        return body.build();
    }

    /**
     * Data-fetcher value for a {@link ChildField.RecordCompositeField}: the source-passthrough
     * projection of an {@code @service} carrier's composite record(s). Uses
     * {@link #emitRecordSourceLocal}'s envelope fork to bind the typed {@code source} local to
     * {@code List<Composite>} or {@code Composite}, then returns it unchanged. No database access
     * and no DataLoader: graphql-java maps each element onto the data field's element result
     * type, whose {@code @field}-mapped {@code @table} children resolve through their own
     * record-backed fetchers. The {@code ErrorList} arm falls through to {@code return null},
     * rendering {@code data: null} on the error arm.
     */
    private static CodeBlock buildRecordCompositeFetcherValue(
            ChildField.RecordCompositeField field, String outputPackage) {
        boolean isList = field.returnType().wrapper().isList();
        boolean outcomeWrapped =
            field.envelope() == SourceEnvelope.OUTCOME_SUCCESS;
        ClassName compositeClass = ClassName.bestGuess(field.returnType().fqClassName());
        TypeName sourceType = isList
            ? ParameterizedTypeName.get(ClassName.get("java.util", "List"), compositeClass)
            : compositeClass;
        var body = CodeBlock.builder();
        emitRecordSourceLocal(body, sourceType, outcomeWrapped, outputPackage);
        body.add("    return source;\n");
        return body.build();
    }

    /**
     * Data-fetcher value for a {@link ChildField.SingleRecordIdFieldFromReturning}: reads the
     * resolved PK column(s) off {@code env.getSource()} and runs them through the pre-resolved
     * {@link no.sikt.graphitron.rewrite.model.HelperRef.Encode} helper, mapping each row of the
     * {@code Result<Record>} when the wrapper is list-shaped. The encoder reference is
     * pre-resolved at carrier-classify time ({@link no.sikt.graphitron.rewrite.FieldBuilder}'s
     * {@code resolveDeleteIdEncoder}) and read from the
     * {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys} slot directly.
     * No follow-up SELECT runs: the deleted row's PK is the entire post-image and lives in the
     * upstream Record.
     */
    private static CodeBlock buildSingleRecordIdFromReturningFetcherValue(
            ChildField.SingleRecordIdFieldFromReturning carrier) {
        var encoder = carrier.encode().encodeMethod();
        var encoderClass = encoder.encoderClass();
        var encoderMethod = encoder.methodName();
        var pkColumns = encoder.paramSignature();
        var jooqRecord = ClassName.get("org.jooq", "Record");
        var jooqResult = ClassName.get("org.jooq", "Result");
        boolean isList = carrier.returnType().wrapper().isList();
        var body = CodeBlock.builder();
        if (isList) {
            var resultOfRecord = ParameterizedTypeName.get(jooqResult, jooqRecord);
            var stringClass = ClassName.get("java.lang", "String");
            var arrayListOfString = ParameterizedTypeName.get(
                ClassName.get("java.util", "ArrayList"), stringClass);
            var listOfString = ParameterizedTypeName.get(
                ClassName.get("java.util", "List"), stringClass);
            body.add("    $T source = env.getSource();\n", resultOfRecord);
            body.add("    if (source == null) return null;\n");
            body.add("    $T ids = new $T(source.size());\n", listOfString, arrayListOfString);
            body.add("    for ($T row : source) {\n", jooqRecord);
            body.add("        ids.add($T.$L(", encoderClass, encoderMethod);
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("row.get($T.field($S, $T.class))",
                    DSL, pkColumns.get(i).sqlName(),
                    CatalogRefs.columnType(pkColumns.get(i)));
            }
            body.add("));\n");
            body.add("    }\n");
            body.add("    return ids;\n");
        } else {
            body.add("    $T source = env.getSource();\n", jooqRecord);
            body.add("    if (source == null) return null;\n");
            body.add("    return $T.$L(", encoderClass, encoderMethod);
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("source.get($T.field($S, $T.class))",
                    DSL, pkColumns.get(i).sqlName(),
                    CatalogRefs.columnType(pkColumns.get(i)));
            }
            body.add(");\n");
        }
        return body.build();
    }

    /**
     * Binding for a {@code RecordReadField}: an exhaustive switch over the leaf's
     * {@link ValueLocator}. jOOQ-record parents read a column off the source (source-only,
     * wrapped in {@code LightFetcher}); class-backed parents read the pre-resolved accessor:
     * source-only for field reads and zero-arg accessors, env-dependent when the accessor
     * injects the environment; a {@link ValueLocator.DefaultRead} registers graphql-java's own
     * {@code PropertyDataFetcher} explicitly. The accessor read itself goes through the shared
     * {@link #recordBackedAccessorRead} (the same helper the arm-switch path uses), so the
     * accessor switch lives in one place. Each arm's parent-shape cast is guaranteed by the
     * validator's record-read gating rule, not construction-site coincidence.
     *
     * <p>An encode compaction wraps whichever value the arm read, through
     * {@link #encodedReadReturn}. Two arms change shape to feed it rather than being wrapped
     * as-is: the by-name read asks for the key column's type instead of returning {@code Object},
     * and the default read has to run graphql-java's property machinery itself rather than handing
     * the registration a bare {@code PropertyDataFetcher}, there being nowhere else to apply the
     * encode.
     */
    private static FetcherBinding recordReadBinding(
            ChildField.RecordReadField field, GraphitronType.ResultType resultType,
            ClassName fetchersClass, String outputPackage) {
        var encode = field.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys e ? e : null;
        return switch (field.locator()) {
            case ValueLocator.TypedColumn tc -> {
                var table = ((GraphitronType.JooqTableRecordType) resultType).table();
                var read = CodeBlock.of("(($T) source).get($T.$L.$L)",
                    RECORD, CatalogRefs.constantsClass(table), table.javaFieldName(), tc.column().javaName());
                yield sourceOnly(field.name(), fetchersClass, outputPackage,
                    encodedReadReturn(read, encode));
            }
            case ValueLocator.ByName bn -> {
                var read = encode == null
                    ? CodeBlock.of("(($T) source).get($T.field($S))", RECORD, DSL, bn.sqlName())
                    : CodeBlock.of("(($T) source).get($T.field($S, $T.class))",
                        RECORD, DSL, bn.sqlName(), encodeKeyType(encode));
                yield sourceOnly(field.name(), fetchersClass, outputPackage,
                    encodedReadReturn(read, encode));
            }
            case ValueLocator.JavaAccessor ja -> {
                String fqClassName = (resultType instanceof GraphitronType.JavaRecordType jrt)
                    ? jrt.fqClassName()
                    : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
                var backingClass = ClassName.bestGuess(fqClassName);
                if (isEnvDependentAccessorRead(field)) {
                    yield envDependent(field.name(), fetchersClass, encodedReadReturn(
                        recordBackedAccessorRead(backingClass, ja.accessor(), ENV_SOURCE), encode));
                }
                yield sourceOnly(field.name(), fetchersClass, outputPackage, encodedReadReturn(
                    recordBackedAccessorRead(backingClass, ja.accessor(), CodeBlock.of("source")), encode));
            }
            // Graphitron located nothing: register graphql-java's default property machinery
            // explicitly, keyed on the located name (mirrors the ErrorsField PayloadAccessor arm).
            case ValueLocator.DefaultRead dr -> {
                var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
                if (encode == null) {
                    yield new FetcherBinding.Inline(
                        CodeBlock.of("$T.fetching($S)", propertyDataFetcher, dr.name()));
                }
                yield throwingEnvDependent(field.name(), fetchersClass, encodedReadReturn(
                    CodeBlock.of("($T) $T.fetching($S).get(env)",
                        encodeKeyType(encode), propertyDataFetcher, dr.name()),
                    encode));
            }
        };
    }

    /**
     * The {@code return} statement for a record read, encoded or not. The encoded form binds the
     * read to a local of the key column's own declared type, which is what makes the four arms
     * feed one {@code encode<TypeName>} signature: a boxing or widening conversion applies at the
     * assignment, and a read that genuinely cannot feed the encode is a javac error naming both
     * types at the one line that names them. The null test is on the value rather than the id: a
     * read yielding nothing is an absent field, never an id encoding the absence.
     */
    private static CodeBlock encodedReadReturn(CodeBlock read, CallSiteCompaction.NodeIdEncodeKeys encode) {
        if (encode == null) {
            return CodeBlock.of("return $L;\n", read);
        }
        return CodeBlock.builder()
            .add("$T key = $L;\n", encodeKeyType(encode), read)
            .add("return key == null ? null : $T.$L(key);\n",
                encode.encodeMethod().encoderClass(), encode.encodeMethod().methodName())
            .build();
    }

    /**
     * The sole key column's type on a read-side encode. Single-key by the carrier's constructor
     * invariant, a read yielding one value.
     */
    private static no.sikt.graphitron.javapoet.TypeName encodeKeyType(CallSiteCompaction.NodeIdEncodeKeys encode) {
        return CatalogRefs.columnType(encode.encodeMethod().paramSignature().get(0));
    }

    /**
     * An env-dependent reified binding whose body may throw: the default-read encode calls
     * {@code PropertyDataFetcher.get}, which declares {@code Exception}, and {@code DataFetcher.get}
     * declares it too, so the method reference is still a {@code DataFetcher}.
     */
    private static FetcherBinding throwingEnvDependent(String name, ClassName fetchersClass, CodeBlock body) {
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(DATA_FETCHING_ENV, "env")
            .addException(Exception.class)
            .addCode(body)
            .build();
        return new FetcherBinding.Reified(method, CodeBlock.of("$T::$L", fetchersClass, name));
    }

    /**
     * The value expression reading a class-backed accessor off a source object. The
     * source is supplied as a {@link CodeBlock} ({@code env.getSource()} on the normal path,
     * {@code success.value()} on the outcome arm-switch), so this one helper serves both the
     * normal {@link #recordReadBinding} lambda and the arm-switch ternary. Field reads emit
     * {@code (($T) src).field}; method accessors delegate to {@link #methodCallValue} for the
     * zero-arg / full-environment / per-argument injection forms.
     */
    private static CodeBlock recordBackedAccessorRead(
            ClassName backingClass, AccessorResolution.Resolved accessor, CodeBlock sourceExpr) {
        return switch (accessor) {
            case AccessorResolution.GetterPrefixed gp -> methodCallValue(backingClass, gp.method(), sourceExpr);
            case AccessorResolution.BareName bn -> methodCallValue(backingClass, bn.method(), sourceExpr);
            case AccessorResolution.FieldRead fr ->
                CodeBlock.of("(($T) $L).$L", backingClass, sourceExpr, fr.field().getName());
        };
    }

    /**
     * Emits the method-call value expression for a resolved accessor, read off {@code sourceExpr}.
     * Three injection forms: zero-arg ({@code .name()}), full-environment ({@code .name(env)} when
     * the method takes a single {@code DataFetchingEnvironment}), or per-argument
     * ({@code .name(($T) env.getArgument($S), …)}, using the method's reflected parameter names
     * as the SDL argument keys, which holds when the consumer compiles with {@code -parameters}).
     * The {@code env} reference for the full-environment and per-argument forms is supplied by
     * the enclosing lambda, independent of where the source object is read from.
     */
    private static CodeBlock methodCallValue(
            ClassName backingClass, java.lang.reflect.Method method, CodeBlock sourceExpr) {
        var paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return CodeBlock.of("(($T) $L).$L()", backingClass, sourceExpr, method.getName());
        }
        if (paramTypes.length == 1 && "graphql.schema.DataFetchingEnvironment".equals(paramTypes[0].getName())) {
            return CodeBlock.of("(($T) $L).$L(env)", backingClass, sourceExpr, method.getName());
        }
        var parameters = method.getParameters();
        var argsBuilder = CodeBlock.builder();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) argsBuilder.add(", ");
            if (!parameters[i].isNamePresent()) {
                throw new IllegalStateException(
                    "Cannot emit per-argument injection for " + method
                    + ": compile the backing class with -parameters so SDL argument names are preserved.");
            }
            argsBuilder.add("($T) env.getArgument($S)",
                ClassName.get(parameters[i].getType()), parameters[i].getName());
        }
        return CodeBlock.of("(($T) $L).$L($L)",
            backingClass, sourceExpr, method.getName(), argsBuilder.build());
    }
}
