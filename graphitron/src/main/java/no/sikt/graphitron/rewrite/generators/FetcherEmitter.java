package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.LightFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SourceEnvelope;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Binds a single classified field to its {@code DataFetcher}: the registration value the
 * {@code codeRegistry.dataFetcher(coords, ...)} call receives, paired with the named
 * {@code <Type>Fetchers} method that owns the read (when the read is reified here).
 *
 * <p>Consumed by {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter}
 * (which emits the registration value) and {@link TypeFetcherGenerator} (which collects the
 * reified method onto the owning {@code <Type>Fetchers} class). The fetcher logic is kept in
 * one place so the classifier → registration pipeline stays the only path from schema model
 * to a {@code DataFetcher}.
 *
 * <p>{@link #bind} returns a {@link FetcherBinding}: {@link FetcherBinding.Reified} carries both
 * the method declaration and the registration value (a bare {@code Fetchers::field} reference for
 * env-dependent reads, or a {@code new LightFetcher<>(Fetchers::field)} wrapper for source-only
 * reads so the env-skipping fast path is preserved), so the value and the method cannot drift.
 * {@link FetcherBinding.Inline} carries only the registration value — used for method-backed
 * variants whose method {@link TypeFetcherGenerator}'s switch owns, and for the few shapes not
 * yet reified.
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
     * (the few shapes not yet reified).
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
     * Whether {@code fields} include an errors field on the R244 {@code Outcome} wrapper transport.
     * When true the type is a flipped outcome payload: its fetchers receive a non-null
     * {@code Outcome} as {@code env.getSource()}, so every data-channel sibling must arm-switch on
     * {@code Success}. The signal is the parent's own {@code WrapperArm} errors field, knowable at
     * generation time without re-walking the classifier.
     *
     * <p>Single home for the predicate so the two consumers that fork on it — the registration-site
     * routing in {@code FetcherRegistrationsEmitter} and the DataLoader-method emission in
     * {@code TypeFetcherGenerator} — cannot drift (R268).
     */
    public static boolean hasWrapperArmErrors(List<? extends GraphitronField> fields) {
        return fields.stream().anyMatch(f -> f instanceof ChildField.ErrorsField ef
            && ef.transport() instanceof ChildField.Transport.WrapperArm);
    }

    /**
     * Whether a nested object type owns any fetcher, i.e. any classified field. Every classified
     * field {@link #bind}s to a fetcher (a reified read or a method-backed reference), so a nested
     * type owning one gets its own {@code <Type>Fetchers} class and the registration references
     * into it (R303). Single home for the gate so {@code FetcherRegistrationsEmitter.nestedBody}
     * (which references the class) and {@code TypeFetcherGenerator.collectNestedFetcherClasses}
     * (which emits it) cannot drift.
     */
    public static boolean nestedTypeOwnsFetchers(List<? extends GraphitronField> nestedFields) {
        return nestedFields.stream().anyMatch(f -> !(f instanceof GraphitronField.UnclassifiedField));
    }

    /**
     * Whether {@code field} would resolve to graphql-java's {@code PropertyDataFetcher} (a property
     * read off the source object) rather than a graphitron-emitted fetcher. Under an
     * {@code Outcome} wrapper this is a silent runtime hole: the read would land on the
     * {@code Outcome} object itself rather than arm-switching. The emit-time source (R268) is
     * an {@code ErrorsField} on the {@code PayloadAccessor} transport, which emits
     * {@code PropertyDataFetcher.fetching} in {@link #bindRaw}. The validator consults
     * this predicate so it keys on the emitter's own dispositions rather than re-deriving them.
     * The live case is pinned by {@code FetcherPipelineTest} wiring assertions.
     *
     * <p>This is the {@code PropertyDataFetcher} (registration-escape) family only, the invariant
     * {@code validateOutcomeChildArmSwitch} enforces per R268's spec. It does <em>not</em> claim to
     * catch every non-arm-switching emit path: a {@code ComputedField} or other
     * {@code LightFetcher}-backed leaf would emit a (non-arm-switched) {@code LightDataFetcher}
     * rather than a {@code PropertyDataFetcher}, but such shapes are inventory-absent under a
     * class-backed {@code @service} payload (they need a SELECT-projected parent), which is
     * the scope boundary R268 chose.
     *
     * <p>An {@code UnclassifiedField} (which gets no registration at all, so graphql-java installs
     * its default {@code PropertyDataFetcher}) is the third source, but it is absence-of-registration
     * rather than an emitted value, so the validator checks it separately.
     */
    public static boolean resolvesViaPropertyDataFetcher(
            GraphitronField field, GraphitronType.ResultType resultType) {
        return field instanceof ChildField.ErrorsField ef
                && ef.transport() instanceof ChildField.Transport.PayloadAccessor;
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
     * @param sourceIsOutcome {@code true} when this field is an immediate child of an outcome type
     *                        that has flipped to the {@code Outcome} wrapper transport (R244): its
     *                        fetcher receives an {@code Outcome} as {@code env.getSource()}, so a
     *                        data-channel field's read must unwrap {@code Success} first and resolve
     *                        null on the {@code ErrorList} arm. The errors field itself is exempt
     *                        (it reads {@code ErrorList.errors()} directly via its {@code WrapperArm}
     *                        transport). The caller knows this at generation time from the parent
     *                        type's classified fields.
     */
    public static FetcherBinding bind(
            GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, boolean sourceIsOutcome) {
        // R268: an immediate child of a flipped Outcome payload receives a non-null Outcome as
        // env.getSource(). Three structural roles, only one of which is an inline arm-switch here:
        //   - the errors field reads ErrorList.errors via its WrapperArm transport (the raw emitter
        //     already handles it, so it falls through);
        //   - inline-resolved data fields (record-backed accessor reads, constructor/nesting
        //     passthrough) arm-switch here: narrow Success and point the field's own read at
        //     success.value(), resolving null on the ErrorList arm;
        //   - DataLoader/method-backed data fields (record-sourced BatchedTableField, RecordLookupTableField,
        //     RecordTableMethodField, and the @service/@tableMethod nested-method variants) resolve
        //     to a generated fetcher method that arm-switches internally (TypeFetcherGenerator +
        //     GeneratorUtils source-bound key extraction), so the registration emits the plain
        //     method reference unchanged via bindRaw.
        // The fork is on a structural fact the model already carries (inline value vs. method
        // reference), not a parallel allow-list of "blessed" variants.
        if (sourceIsOutcome && isInlineArmSwitchedDataField(field)) {
            return armSwitchedInlineDataFetcher(field, fetchersClass, resultType, outputPackage);
        }
        return bindRaw(field, fetchersClass, parentTable, resultType, outputPackage);
    }

    /**
     * The inline-resolved data-channel shapes that can appear as an immediate child of a
     * class-backed {@code Outcome} payload. Each is resolved here by {@link #bindRaw} as a read of
     * the field's own source; under the wrapper transport that read is repointed at
     * {@code success.value()} (see {@link #armSwitchedInlineDataFetcher}). Post-R303 the read is
     * reified into a named {@code <Type>Fetchers} method rather than emitted as an inline lambda.
     *
     * <p>The errors field is excluded (it reads {@code ErrorList.errors} via its
     * {@code WrapperArm} transport). DataLoader/method-backed fields are excluded because their
     * generated fetcher method owns the arm-switch; the registration site emits a plain method
     * reference for them. This is not the retired allow-list: it names the shapes whose read is
     * resolved <em>here</em> as a narrowable source read, a structural property of the emit path.
     */
    private static boolean isInlineArmSwitchedDataField(GraphitronField field) {
        return field instanceof ChildField.NestingField
            || field instanceof ChildField.PropertyField
            || field instanceof ChildField.RecordField;
    }

    /**
     * R244/R268: an inline-resolved data-channel child of a flipped outcome type reads off
     * {@code Success.value()} of the non-null {@code Outcome} source and resolves null on the
     * {@code ErrorList} arm. The success-arm read is the field's <em>own</em> read, source-bound to
     * {@code success.value()} instead of {@code env.getSource()} — for record-backed accessors via
     * the shared {@link #recordBackedAccessorRead} (the same helper {@link #propertyOrRecordBinding}
     * uses), so there is no parallel accessor taxonomy.
     */
    private static FetcherBinding armSwitchedInlineDataFetcher(
            GraphitronField field, ClassName fetchersClass,
            GraphitronType.ResultType resultType, String outputPackage) {
        // Statement form (R303): narrow Success up front, then return the field's own read off
        // success.value(); resolve null on the ErrorList arm. Source-only unless the field reads a
        // class-backed accessor that injects the environment.
        boolean envDependent = isEnvDependentAccessorRead(field, resultType);
        CodeBlock subject = envDependent ? ENV_SOURCE : CodeBlock.of("source");
        CodeBlock body = CodeBlock.builder()
            .add("if (!($L instanceof $T<?> success)) return null;\n", subject, successClass(outputPackage))
            .add("return $L;\n", inlineSuccessRead(field, resultType))
            .build();
        return envDependent
            ? envDependent(field.name(), fetchersClass, body)
            : sourceOnly(field.name(), fetchersClass, outputPackage, body);
    }

    /**
     * Whether {@code field}'s read needs the {@code DataFetchingEnvironment}. Only a class-backed
     * accessor that injects the environment (a method with parameters: the full-env or per-argument
     * forms in {@link #methodCallValue}) does; jOOQ-record column reads, field reads, and zero-arg
     * accessors are source-only.
     */
    private static boolean isEnvDependentAccessorRead(
            GraphitronField field, GraphitronType.ResultType resultType) {
        AccessorResolution.Resolved accessor =
            field instanceof ChildField.PropertyField pf ? pf.accessor()
            : field instanceof ChildField.RecordField rf ? rf.accessor()
            : null;
        if (accessor == null) {
            return false;
        }
        if (resultType instanceof GraphitronType.JooqTableRecordType
                || resultType instanceof GraphitronType.JooqRecordType) {
            return false;
        }
        return switch (accessor) {
            case AccessorResolution.FieldRead ignored -> false;
            case AccessorResolution.GetterPrefixed gp -> gp.method().getParameterTypes().length > 0;
            case AccessorResolution.BareName bn -> bn.method().getParameterTypes().length > 0;
        };
    }

    /**
     * The success-arm value expression: the field's own read, source-bound to {@code success.value()}.
     * The read shape follows the field's backing, mirroring {@link #bindRaw} /
     * {@link #propertyOrRecordBinding} so there is no parallel taxonomy: a jOOQ-record column
     * {@code get} (the {@code ((Record) source).get(column)} read a jOOQ-record column field emits,
     * inlined onto {@code success.value()}), a
     * class-backed accessor call, or the nesting source passthrough.
     *
     * <p>The final {@code throw} is a defensive backstop for any field/backing combination that
     * has neither a column nor a resolved accessor and so cannot be projected inline.
     */
    private static CodeBlock inlineSuccessRead(GraphitronField field, GraphitronType.ResultType resultType) {
        if (field instanceof ChildField.NestingField) {
            return CodeBlock.of("success.value()");
        }
        // jOOQ-record-backed column read: the ((Record) source).get(column) read, inlined
        // onto success.value(). Same two arms as propertyOrRecordBinding's jOOQ branches.
        ColumnRef column = field instanceof ChildField.PropertyField pf ? pf.column()
            : field instanceof ChildField.RecordField rf ? rf.column()
            : null;
        String columnName = field instanceof ChildField.PropertyField pf ? pf.columnName()
            : field instanceof ChildField.RecordField rf ? rf.columnName()
            : null;
        if (resultType instanceof GraphitronType.JooqTableRecordType jtrt && column != null && jtrt.table() != null) {
            return CodeBlock.of("(($T) success.value()).get($T.$L.$L)",
                RECORD, jtrt.table().constantsClass(), jtrt.table().javaFieldName(), column.javaName());
        }
        if ((resultType instanceof GraphitronType.JooqTableRecordType
                || resultType instanceof GraphitronType.JooqRecordType) && columnName != null) {
            return CodeBlock.of("(($T) success.value()).get($T.field($S))", RECORD, DSL, columnName);
        }
        // Record-backed (Pojo / JavaRecord) accessor read.
        AccessorResolution.Resolved accessor =
            field instanceof ChildField.PropertyField pf ? pf.accessor()
            : field instanceof ChildField.RecordField rf ? rf.accessor()
            : null;
        String javaBackingFqcn =
            resultType instanceof GraphitronType.JavaRecordType jrt ? jrt.fqClassName()
            : resultType instanceof GraphitronType.PojoResultType.Backed b ? b.fqClassName()
            : null;
        if (accessor != null && javaBackingFqcn != null) {
            return recordBackedAccessorRead(
                ClassName.bestGuess(javaBackingFqcn), accessor, CodeBlock.of("success.value()"));
        }
        throw new IllegalStateException(
            "R268 arm-switch: unsupported inline success-projection field "
            + field.getClass().getSimpleName() + " on backing " + resultType);
    }

    private static ClassName successClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", "Outcome").nestedClass("Success");
    }

    private static ClassName errorListClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", "Outcome").nestedClass("ErrorList");
    }

    private static ClassName outcomeClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", "Outcome");
    }

    /**
     * Emit the {@code source} local for a record-carrier fetcher body, narrowing the R244
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
            // R156: data field on a payload-returning DELETE carrier with an ID-typed data field.
            // The mutation fetcher produced a Record (single) or Result<Record> (bulk) from the
            // PK-only RETURNING; this fetcher reads PK column(s) off each row and runs them
            // through the resolved NodeId encoder. No follow-up SELECT — the row is gone.
            return envDependent(field.name(), fetchersClass,
                buildSingleRecordIdFromReturningFetcherValue(idCarrier));
        }
        if (field instanceof ChildField.SingleRecordIdField serviceIdCarrier) {
            // R275: ID-element data field on an @service source-record carrier. The producer
            // returned the typed XRecord (ONE) or List<XRecord> (MANY) verbatim, optionally
            // wrapped in Outcome (errors-bearing payload); this fetcher reads the node-key
            // column(s) off each in-memory record and runs them through the resolved NodeId
            // encoder. No follow-up SELECT — the records may be deleted rows.
            return envDependent(field.name(), fetchersClass,
                buildSingleRecordIdFetcherValue(serviceIdCarrier, outputPackage));
        }
        if (field instanceof ChildField.RecordCompositeField composite) {
            // R329: the @service record-composite carrier's data field. The producer returned the
            // consumer composite(s) (one Composite for single arrival, List<Composite> for list
            // arrival) verbatim, optionally wrapped in Outcome (errors-bearing payload); this fetcher
            // narrows Outcome.Success then returns the composite(s) straight off env.getSource() — no
            // re-fetch, no DataLoader. graphql-java maps each composite onto the element result type,
            // whose @field-mapped @table children resolve through their own record-backed fetchers.
            return envDependent(field.name(), fetchersClass,
                buildRecordCompositeFetcherValue(composite, outputPackage));
        }
        if (field instanceof ChildField.ErrorsField ef) {
            // Switch on the field's resolved Transport: PayloadAccessor reads the errors list
            // off the parent payload via graphql-java's PropertyDataFetcher (record accessor /
            // JavaBean getter / field); LocalContext reads it off the env's local-context slot,
            // populated by the catch arm of an ErrorChannel.LocalContext-bound carrier. The
            // discriminator rides on the field-level model (resolved at classify time with the
            // parent carrier's channel in scope) so this emission never re-walks the parent.
            return switch (ef.transport()) {
                // PayloadAccessor still resolves via graphql-java's PropertyDataFetcher; the
                // resolved-accessor reification (with the R268 validator reconciliation) lands as
                // a dedicated follow-up commit.
                case ChildField.Transport.PayloadAccessor ignored -> {
                    var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
                    yield new FetcherBinding.Inline(
                        CodeBlock.of("$T.fetching($S)", propertyDataFetcher, field.name()));
                }
                case ChildField.Transport.LocalContext ignored ->
                    envDependent(field.name(), fetchersClass,
                        CodeBlock.of("return env.getLocalContext();\n"));
                // R244/R275: the errors list rides on the Outcome.ErrorList arm of the non-null
                // Outcome source. On the Success arm there are no errors, so resolve null (not
                // List.of()) to honour the errors field's SDL nullability on the wire (admissio
                // parity). The NonNullableErrorsField classify-time rule guarantees the field is
                // nullable, so null is always a legal success-arm value.
                case ChildField.Transport.WrapperArm ignored ->
                    sourceOnly(field.name(), fetchersClass, outputPackage,
                        CodeBlock.of("return source instanceof $T<?> errorList ? errorList.errors() : null;\n",
                            errorListClass(outputPackage)));
            };
        }
        if (field instanceof ChildField.PropertyField pf && resultType != null) {
            return propertyOrRecordBinding(pf, pf.columnName(), pf.column(), resultType,
                pf.accessor(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.RecordField rf && resultType != null) {
            return propertyOrRecordBinding(rf, rf.columnName(), rf.column(), resultType,
                rf.accessor(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.ColumnField cf && parentTable != null) {
            if (cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys enc) {
                // Arity-1 NodeId-encoded projection: read the keyColumn off the source record
                // and pass it through encode<TypeName>. The HelperRef.Encode reference carries
                // both the encoder class and the helper method name so we never reconstruct
                // either from a raw typeId string at emission time.
                var encoderClass = enc.encodeMethod().encoderClass();
                CodeBlock body = CodeBlock.builder()
                    .add("$T r = ($T) source;\n", RECORD, RECORD)
                    .add("return $T.$L(r.get($T.$L.$L));\n",
                        encoderClass, enc.encodeMethod().methodName(),
                        parentTable.constantsClass(), parentTable.javaFieldName(), cf.column().javaName())
                    .build();
                return sourceOnly(field.name(), fetchersClass, outputPackage, body);
            }
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.$L.$L);\n",
                    RECORD, parentTable.constantsClass(), parentTable.javaFieldName(), cf.column().javaName()));
        }
        if (field instanceof ChildField.CompositeColumnField ccf && parentTable != null) {
            // Composite-key NodeId projection: read each keyColumn off the source record and
            // pass them positionally through encode<TypeName>(c1, ..., cN). Compaction is
            // narrowed to NodeIdEncodeKeys at the type level — no plain composite projection
            // exists.
            var enc = ccf.compaction();
            var encoderClass = enc.encodeMethod().encoderClass();
            var body = CodeBlock.builder()
                .add("$T r = ($T) source;\n", RECORD, RECORD)
                .add("return $T.$L(", encoderClass, enc.encodeMethod().methodName());
            for (int i = 0; i < ccf.columns().size(); i++) {
                if (i > 0) body.add(", ");
                body.add("r.get($T.$L.$L)",
                    parentTable.constantsClass(), parentTable.javaFieldName(), ccf.columns().get(i).javaName());
            }
            body.add(");\n");
            return sourceOnly(field.name(), fetchersClass, outputPackage, body.build());
        }
        if (field instanceof ChildField.TableField tf) {
            boolean single = tf.returnType().wrapper() instanceof FieldWrapper.Single;
            if (single) {
                var resultClass = ClassName.get("org.jooq", "Result");
                var resultWildcard = ParameterizedTypeName.get(resultClass, WildcardTypeName.subtypeOf(Object.class));
                CodeBlock body = CodeBlock.builder()
                    .add("Object raw = (($T) source).get($S, $T.class);\n", RECORD, field.name(), resultClass)
                    .add("return raw instanceof $T r && !r.isEmpty() ? r.get(0) : null;\n", resultWildcard)
                    .build();
                return sourceOnly(field.name(), fetchersClass, outputPackage, body);
            }
            return columnByAlias(field.name(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.LookupTableField) {
            return columnByAlias(field.name(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.ComputedField) {
            // Wired by name: TypeClassGenerator.$fields() inlines the developer's method call
            // aliased to the field name; the read picks the result Record up by that alias.
            return columnByAlias(field.name(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.ParticipantColumnReferenceField pcrf) {
            // Cross-table participant field on a TableInterfaceType participant. The interface
            // fetcher (TypeFetcherGenerator) emits a conditional LEFT JOIN per field gated by the
            // participant's discriminator value, and projects the column aliased as
            // pcrf.aliasName(). Read it back from the parent record by alias. The Class<?>
            // parameter on DSL.field carries the column's concrete type so jOOQ's converter
            // returns the right Java value (e.g. enum) when the column is a typed projection
            // rather than a raw SQL identifier.
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.field($T.name($S), $T.class));\n",
                    RECORD, DSL, DSL, pcrf.aliasName(),
                    pcrf.column().columnType()));
        }
        if (field instanceof ChildField.ColumnReferenceField crf
                && crf.compaction() instanceof CallSiteCompaction.Direct) {
            // Direct-compaction scalar @reference: TypeClassGenerator.$fields() projects an aliased
            // correlated subquery; the read picks the value out of the parent Record by alias.
            return columnByAlias(field.name(), fetchersClass, outputPackage);
        }
        if (field instanceof ChildField.CompositeColumnReferenceField ccrf && parentTable != null) {
            // Stubbed variant (validator-rejected before generation): keep the throwing lambda
            // inline rather than minting a named method for an unimplemented shape.
            return new FetcherBinding.Inline(CodeBlock.of(
                "($T env) -> { throw new $T($S); }",
                DATA_FETCHING_ENV, UnsupportedOperationException.class,
                "Rooted-at-parent composite NodeId reference '" + ccrf.parentTypeName() + "." + ccrf.name()
                    + "' requires JOIN-with-projection emission — not yet implemented."));
        }
        // Method-backed variants: TypeFetcherGenerator's switch owns the method; carry the reference.
        return new FetcherBinding.Inline(CodeBlock.of("$T::$L", fetchersClass, field.name()));
    }

    /** Source-only read of an aliased column off the parent record. */
    private static FetcherBinding columnByAlias(String name, ClassName fetchersClass, String outputPackage) {
        return sourceOnly(name, fetchersClass, outputPackage,
            CodeBlock.of("return (($T) source).get($T.field($S));\n", RECORD, DSL, name));
    }

    /**
     * R275 — data-fetcher value for a {@link ChildField.SingleRecordIdField}, the ID-element
     * data field on an {@code @service} source-record carrier. Mirrors
     * the {@link #emitRecordSourceLocal} source handling — the same
     * {@code SourceEnvelope} fork (narrow {@code Outcome.Success} under {@code OUTCOME_SUCCESS},
     * read {@code env.getSource()} verbatim under {@code DIRECT}) via the shared
     * {@link #emitRecordSourceLocal}, binding the same typed {@code XRecord} / {@code List<XRecord>}
     * {@code source} local — but instead of a follow-up SELECT it maps
     * each record through the pre-resolved NodeId encoder, reading the node-key column(s) via
     * the typed {@code Tables.X.COL} constants. No database access: the producer's records may
     * be deleted rows (the opptak {@code fjernSakTagg}/{@code fjernSakTagger} shape), and the
     * encode is total over the in-memory record.
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
                    table.constantsClass(), table.javaFieldName(), col.javaName());
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
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            }
            body.add(");\n");
        }
        return body.build();
    }

    /**
     * R329 — data-fetcher value for a {@link ChildField.RecordCompositeField}: the source-passthrough
     * projection of an {@code @service} carrier's composite record(s). Mirrors
     * {@link #emitRecordSourceLocal}'s envelope fork (narrow {@code Outcome.Success} under
     * {@code OUTCOME_SUCCESS}, read {@code env.getSource()} verbatim under {@code DIRECT}), binding the
     * typed {@code source} local to {@code List<Composite>} (list arrival) or {@code Composite} (single
     * arrival), then returns it unchanged. No database access and no DataLoader: the producer's
     * composite record(s) are already in memory; graphql-java maps each element onto the data field's
     * element result type, whose {@code @field}-mapped {@code @table} children resolve through their
     * own record-backed fetchers. The {@code ErrorList} arm of {@link #emitRecordSourceLocal} falls
     * through to {@code return null}, rendering {@code data: null} on the error arm.
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
     * R156 — data-fetcher value for a {@link ChildField.SingleRecordIdFieldFromReturning}.
     * Reads the resolved PK column(s) off {@code env.getSource()} and runs them through the
     * pre-resolved {@link no.sikt.graphitron.rewrite.model.HelperRef.Encode} encoder helper.
     * Single-shaped wrapper emits {@code (env) -> encode<Type>(record.get(pkCol1), ...)};
     * list-shaped wrapper iterates {@code Result<Record>} and maps each row through the
     * encoder.
     *
     * <p>The encoder reference is pre-resolved at carrier-classify time
     * ({@link no.sikt.graphitron.rewrite.FieldBuilder}'s {@code resolveDeleteIdEncoder}); the
     * emitter reads {@code encodeMethod.encoderClass()}, {@code methodName()}, and the
     * positional {@code paramSignature()} from the {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys}
     * slot directly. No follow-up SELECT runs — the deleted row's PK is the entire post-image
     * and lives in the upstream Record.
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
                    pkColumns.get(i).columnType());
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
                    pkColumns.get(i).columnType());
            }
            body.add(");\n");
        }
        return body.build();
    }

    /**
     * Binding for a {@code PropertyField} / {@code RecordField}. jOOQ-record parents read a column
     * off the source (source-only, wrapped in {@code LightFetcher}); class-backed parents read the
     * pre-resolved accessor — source-only for field reads and zero-arg accessors, env-dependent when
     * the accessor injects the environment. The accessor read itself goes through the shared
     * {@link #recordBackedAccessorRead} (the same helper the R268 arm-switch path uses), so the
     * accessor switch lives in one place.
     */
    private static FetcherBinding propertyOrRecordBinding(
            GraphitronField field, String columnName, ColumnRef column,
            GraphitronType.ResultType resultType, AccessorResolution.Resolved accessor,
            ClassName fetchersClass, String outputPackage) {
        if (resultType instanceof GraphitronType.JooqTableRecordType jtrt
                && column != null && jtrt.table() != null) {
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.$L.$L);\n",
                    RECORD, jtrt.table().constantsClass(), jtrt.table().javaFieldName(), column.javaName()));
        }
        if (resultType instanceof GraphitronType.JooqTableRecordType
                || resultType instanceof GraphitronType.JooqRecordType) {
            return sourceOnly(field.name(), fetchersClass, outputPackage,
                CodeBlock.of("return (($T) source).get($T.field($S));\n", RECORD, DSL, columnName));
        }
        String fqClassName = (resultType instanceof GraphitronType.JavaRecordType jrt)
            ? jrt.fqClassName()
            : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
        var backingClass = ClassName.bestGuess(fqClassName);
        if (isEnvDependentAccessorRead(field, resultType)) {
            return envDependent(field.name(), fetchersClass,
                CodeBlock.of("return $L;\n", recordBackedAccessorRead(backingClass, accessor, ENV_SOURCE)));
        }
        return sourceOnly(field.name(), fetchersClass, outputPackage,
            CodeBlock.of("return $L;\n", recordBackedAccessorRead(backingClass, accessor, CodeBlock.of("source"))));
    }

    /**
     * The value expression reading a class-backed accessor off a source object. The
     * source is supplied as a {@link CodeBlock} ({@code env.getSource()} on the normal path,
     * {@code success.value()} on the R268 outcome arm-switch), so this one helper serves both the
     * normal {@link #propertyOrRecordBinding} lambda and the arm-switch ternary. Field reads emit
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
     * ({@code .name(($T) env.getArgument($S), …)} — uses the candidate method's reflected parameter
     * names as the SDL argument keys, which holds when the consumer compiles with
     * {@code -parameters}). The {@code env} reference for the full-environment and per-argument forms
     * is supplied by the enclosing lambda, independent of where the source object is read from.
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
