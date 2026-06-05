package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Builds the {@code DataFetcher} value expression for a single classified field.
 *
 * <p>Consumed by {@link no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter},
 * which wraps the value in {@code codeRegistry.dataFetcher(FieldCoordinates.coordinates(type,
 * name), ...)} calls on a {@code GraphQLCodeRegistry.Builder}. The fetcher logic is kept in
 * one place so the classifier → registration pipeline stays the only path from schema model
 * to a {@code DataFetcher}.
 *
 * <p>The returned {@link CodeBlock} starts with no leading whitespace and contains only the
 * value expression: a method reference, lambda, or {@code new ColumnFetcher<>(...)}
 * instantiation.
 */
public final class FetcherEmitter {

    private static final ClassName DATA_FETCHING_ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    /** The default source binding for an inline read: {@code env.getSource()}. */
    private static final CodeBlock ENV_SOURCE = CodeBlock.of("env.getSource()");

    private FetcherEmitter() {}

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
     * Whether {@code field} would resolve to graphql-java's {@code PropertyDataFetcher} (a property
     * read off the source object) rather than a graphitron-emitted fetcher. Under an
     * {@code Outcome} wrapper this is a silent runtime hole: the read would land on the
     * {@code Outcome} object itself rather than arm-switching. The emit-time source (R268) is
     * an {@code ErrorsField} on the {@code PayloadAccessor} transport, which emits
     * {@code PropertyDataFetcher.fetching} in {@link #dataFetcherValueRaw}. The validator consults
     * this predicate so it keys on the emitter's own dispositions rather than re-deriving them.
     * The live case is pinned by {@code FetcherPipelineTest} wiring assertions.
     *
     * <p>This is the {@code PropertyDataFetcher} (registration-escape) family only, the invariant
     * {@code validateOutcomeChildArmSwitch} enforces per R268's spec. It does <em>not</em> claim to
     * catch every non-arm-switching emit path: a {@code ComputedField} or other
     * {@code ColumnFetcher}-backed leaf would emit a (non-arm-switched) {@code LightDataFetcher}
     * rather than a {@code PropertyDataFetcher}, but such shapes are inventory-absent under a
     * {@code @record}-backed {@code @service} payload (they need a SELECT-projected parent), which is
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
     * @param resultType    the parent type's {@code @record} backing, or {@code null}
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
    public static CodeBlock dataFetcherValue(
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
        //   - DataLoader/method-backed data fields (RecordTableField, RecordLookupTableField,
        //     RecordTableMethodField, and the @service/@tableMethod nested-method variants) resolve
        //     to a generated fetcher method that arm-switches internally (TypeFetcherGenerator +
        //     GeneratorUtils source-bound key extraction), so the registration emits the plain
        //     method reference unchanged via dataFetcherValueRaw.
        // The fork is on a structural fact the model already carries (inline value vs. method
        // reference), not a parallel allow-list of "blessed" variants.
        if (sourceIsOutcome && isInlineArmSwitchedDataField(field)) {
            return armSwitchedInlineDataFetcher(field, resultType, outputPackage);
        }
        return dataFetcherValueRaw(field, fetchersClass, parentTable, resultType, outputPackage);
    }

    /**
     * The inline-resolved data-channel shapes that can appear as an immediate child of a
     * {@code @record}-backed {@code Outcome} payload. Each resolves to an inline value expression in
     * {@link #dataFetcherValueRaw} that reads {@code env.getSource()}; under the wrapper transport
     * that read is repointed at {@code success.value()} (see {@link #armSwitchedInlineDataFetcher}).
     *
     * <p>The errors field is excluded (it reads {@code ErrorList.errors} via its
     * {@code WrapperArm} transport). DataLoader/method-backed fields are excluded because their
     * generated fetcher method owns the arm-switch; the registration site emits a plain method
     * reference for them. This is not the retired allow-list: it names the shapes whose value is
     * emitted <em>here</em> as a narrowable inline read, a structural property of the emit path.
     */
    private static boolean isInlineArmSwitchedDataField(GraphitronField field) {
        return field instanceof ChildField.ConstructorField
            || field instanceof ChildField.NestingField
            || field instanceof ChildField.PropertyField
            || field instanceof ChildField.RecordField;
    }

    /**
     * R244/R268: an inline-resolved data-channel child of a flipped outcome type reads off
     * {@code Success.value()} of the non-null {@code Outcome} source and resolves null on the
     * {@code ErrorList} arm. The success-arm read is the field's <em>own</em> read, source-bound to
     * {@code success.value()} instead of {@code env.getSource()} — for record-backed accessors via
     * the shared {@link #recordBackedAccessorRead} (the same helper {@link #propertyOrRecordValue}
     * uses), so there is no parallel accessor taxonomy.
     */
    private static CodeBlock armSwitchedInlineDataFetcher(
            GraphitronField field, GraphitronType.ResultType resultType, String outputPackage) {
        return CodeBlock.of("($T env) -> env.getSource() instanceof $T<?> success ? $L : null",
            DATA_FETCHING_ENV, successClass(outputPackage), inlineSuccessRead(field, resultType));
    }

    /**
     * The success-arm value expression: the field's own read, source-bound to {@code success.value()}.
     * The read shape follows the field's backing, mirroring {@link #dataFetcherValueRaw} /
     * {@link #propertyOrRecordValue} so there is no parallel taxonomy: a jOOQ-record column
     * {@code get} (what {@code ColumnFetcher} does, inlined onto {@code success.value()}), a
     * {@code @record}-Java accessor call, or the constructor/nesting source passthrough.
     *
     * <p>The final {@code throw} is a defensive backstop for any field/backing combination that
     * has neither a column nor a resolved accessor and so cannot be projected inline.
     */
    private static CodeBlock inlineSuccessRead(GraphitronField field, GraphitronType.ResultType resultType) {
        if (field instanceof ChildField.ConstructorField || field instanceof ChildField.NestingField) {
            return CodeBlock.of("success.value()");
        }
        // jOOQ-record-backed column read: ColumnFetcher's ((Record) source).get(column), inlined
        // onto success.value(). Same two arms as propertyOrRecordValue's jOOQ branches.
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
        // @record-Java-backed accessor read.
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

    private static CodeBlock dataFetcherValueRaw(
            GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage) {
        if (field instanceof ChildField.ConstructorField || field instanceof ChildField.NestingField) {
            return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
        }
        if (field instanceof ChildField.SingleRecordTableField srtf) {
            // R75 Phase 1: data field on a single-record DML carrier. The mutation fetcher
            // populated env.getSource() with a Result<RecordN<...>> (Cardinality.MANY) or a
            // RecordN<...> (Cardinality.ONE) from a PK-only RETURNING inside a tight
            // transaction. Run the response SELECT here, outside that transaction; jOOQ field
            // errors during this read or during nested traversal cannot undo the DML.
            return buildSingleRecordTableFetcherValue(srtf, outputPackage);
        }
        if (field instanceof ChildField.SingleRecordIdFieldFromReturning idCarrier) {
            // R156: data field on a payload-returning DELETE carrier with an ID-typed data field.
            // The mutation fetcher produced a Record (single) or Result<Record> (bulk) from the
            // PK-only RETURNING; this fetcher reads PK column(s) off each row and runs them
            // through the resolved NodeId encoder. No follow-up SELECT — the row is gone.
            return buildSingleRecordIdFromReturningFetcherValue(idCarrier);
        }
        if (field instanceof ChildField.SingleRecordTableFieldFromReturning tableCarrier) {
            // R156: data field on a payload-returning DELETE carrier with an @table-element data
            // field. Synthesizes per source row a new jOOQ Record over the element @table with PK
            // columns copied from the RETURNING Record; non-PK column slots remain null so
            // the per-field ColumnFetcher on the element type returns null for NonPkNullable
            // arms. The PkResolution projection list on the carrier pinpoints which columns to
            // copy; no other field shape (FK reference, @service, child collection) reaches
            // this emitter — BuildContext.classifyDeleteTableProjection rejects them before the
            // carrier is constructed.
            return buildSingleRecordTableFromReturningFetcherValue(tableCarrier);
        }
        if (field instanceof ChildField.ErrorsField ef) {
            // Switch on the field's resolved Transport: PayloadAccessor reads the errors list
            // off the parent payload via graphql-java's PropertyDataFetcher (record accessor /
            // JavaBean getter / field); LocalContext reads it off the env's local-context slot,
            // populated by the catch arm of an ErrorChannel.LocalContext-bound carrier. The
            // discriminator rides on the field-level model (resolved at classify time with the
            // parent carrier's channel in scope) so this emission never re-walks the parent.
            return switch (ef.transport()) {
                case ChildField.Transport.PayloadAccessor ignored -> {
                    var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
                    yield CodeBlock.of("$T.fetching($S)", propertyDataFetcher, field.name());
                }
                case ChildField.Transport.LocalContext ignored ->
                    CodeBlock.of("($T env) -> env.getLocalContext()", DATA_FETCHING_ENV);
                // R244/R275: the errors list rides on the Outcome.ErrorList arm of the non-null
                // Outcome source. On the Success arm there are no errors, so resolve null (not
                // List.of()) to honour the errors field's SDL nullability on the wire (admissio
                // parity). The NonNullableErrorsField classify-time rule guarantees the field is
                // nullable, so null is always a legal success-arm value. The ternary reads cleaner
                // than an if/return here because both arms are single expressions over the same check.
                case ChildField.Transport.WrapperArm ignored ->
                    CodeBlock.of("($T env) -> env.getSource() instanceof $T<?> errorList ? errorList.errors() : null",
                        DATA_FETCHING_ENV, errorListClass(outputPackage));
            };
        }
        if (field instanceof ChildField.PropertyField pf && resultType != null) {
            return propertyOrRecordValue(pf.columnName(), pf.column(), resultType, pf.accessor(), outputPackage);
        }
        if (field instanceof ChildField.RecordField rf && resultType != null) {
            return propertyOrRecordValue(rf.columnName(), rf.column(), resultType, rf.accessor(), outputPackage);
        }
        if (field instanceof ChildField.ColumnField cf && parentTable != null) {
            if (cf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys enc) {
                // Arity-1 NodeId-encoded projection: read the keyColumn off the source record
                // and pass it through encode<TypeName>. The HelperRef.Encode reference carries
                // both the encoder class and the helper method name so we never reconstruct
                // either from a raw typeId string at emission time.
                var encoderClass = enc.encodeMethod().encoderClass();
                var recordClass = ClassName.get("org.jooq", "Record");
                return CodeBlock.builder()
                    .add("($T env) -> {\n", DATA_FETCHING_ENV)
                    .add("    $T r = ($T) env.getSource();\n", recordClass, recordClass)
                    .add("    return $T.$L(r.get($T.$L.$L));\n",
                        encoderClass, enc.encodeMethod().methodName(),
                        parentTable.constantsClass(), parentTable.javaFieldName(), cf.column().javaName())
                    .add("}")
                    .build();
            }
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.$L.$L)",
                columnFetcherClass, parentTable.constantsClass(),
                parentTable.javaFieldName(), cf.column().javaName());
        }
        if (field instanceof ChildField.CompositeColumnField ccf && parentTable != null) {
            // Composite-key NodeId projection: read each keyColumn off the source record and
            // pass them positionally through encode<TypeName>(c1, ..., cN). Compaction is
            // narrowed to NodeIdEncodeKeys at the type level — no plain composite projection
            // exists.
            var enc = ccf.compaction();
            var encoderClass = enc.encodeMethod().encoderClass();
            var recordClass = ClassName.get("org.jooq", "Record");
            var body = CodeBlock.builder()
                .add("($T env) -> {\n", DATA_FETCHING_ENV)
                .add("    $T r = ($T) env.getSource();\n", recordClass, recordClass)
                .add("    return $T.$L(", encoderClass, enc.encodeMethod().methodName());
            for (int i = 0; i < ccf.columns().size(); i++) {
                if (i > 0) body.add(", ");
                body.add("r.get($T.$L.$L)",
                    parentTable.constantsClass(), parentTable.javaFieldName(), ccf.columns().get(i).javaName());
            }
            body.add(");\n").add("}");
            return body.build();
        }
        if (field instanceof ChildField.TableField tf) {
            boolean single = tf.returnType().wrapper() instanceof FieldWrapper.Single;
            if (single) {
                var recordClass = ClassName.get("org.jooq", "Record");
                var resultClass = ClassName.get("org.jooq", "Result");
                var resultWildcard = ParameterizedTypeName.get(resultClass, WildcardTypeName.subtypeOf(Object.class));
                return CodeBlock.of(
                    "($T env) -> { Object raw = (($T) env.getSource()).get($S, $T.class); return raw instanceof $T r && !r.isEmpty() ? r.get(0) : null; }",
                    DATA_FETCHING_ENV, recordClass, field.name(), resultClass, resultWildcard);
            }
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.LookupTableField) {
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.ComputedField) {
            // Wired by name: TypeClassGenerator.$fields() inlines the developer's method call
            // aliased to the field name; ColumnFetcher reads the result Record by that alias.
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.ParticipantColumnReferenceField pcrf) {
            // Cross-table participant field on a TableInterfaceType participant. The interface
            // fetcher (TypeFetcherGenerator) emits a conditional LEFT JOIN per field gated by the
            // participant's discriminator value, and projects the column aliased as
            // pcrf.aliasName(). Read it back from the parent record by alias. The Class<?>
            // parameter on DSL.field carries the column's concrete type so jOOQ's converter
            // returns the right Java value (e.g. enum) when the column is a typed projection
            // rather than a raw SQL identifier.
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.field($T.name($S), $T.class))",
                columnFetcherClass, DSL, DSL, pcrf.aliasName(),
                ClassName.bestGuess(pcrf.column().columnClass()));
        }
        if (field instanceof ChildField.ColumnReferenceField crf
                && crf.compaction() instanceof CallSiteCompaction.Direct) {
            // Direct-compaction scalar @reference: TypeClassGenerator.$fields() projects an aliased
            // correlated subquery; the DataFetcher reads the value out of the parent Record by
            // alias via ColumnFetcher.
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.CompositeColumnReferenceField ccrf && parentTable != null) {
            // Composite-key rooted-at-parent NodeId reference — same runtime stub as the
            // single-column reference above. Composite reference projection is always a NodeId
            // encode call (compaction is type-narrowed to NodeIdEncodeKeys).
            return CodeBlock.of(
                "($T env) -> { throw new $T($S); }",
                DATA_FETCHING_ENV, UnsupportedOperationException.class,
                "Rooted-at-parent composite NodeId reference '" + ccrf.parentTypeName() + "." + ccrf.name()
                    + "' requires JOIN-with-projection emission — not yet implemented.");
        }
        return CodeBlock.of("$T::$L", fetchersClass, field.name());
    }

    /**
     * R75 Phase 1 / R141: data-fetcher value for a {@link ChildField.SingleRecordTableField}.
     * Reads {@code env.getSource()} typed by {@code SourceKey.wrap × columns} and runs the
     * response SELECT keyed by the PK columns. The upstream value is whatever the mutation's
     * two-step fetcher returned: a single {@code RecordN<...>} when the input was single
     * ({@code Cardinality.ONE}) or a {@code Result<RecordN<...>>} when the input was bulk
     * ({@code Cardinality.MANY}).
     *
     * <p>Single-PK tables (the sakila fixtures) emit {@code where(PK.eq(value))} or
     * {@code where(PK.in(getValues(PK)))}; composite-PK tables emit {@code where(row(PK1,...).
     * in(...))}. The data field's table equals the input table by construction (the
     * {@link no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted} compact constructor
     * enforces this structurally), so the PK columns here are exactly what the upstream
     * RETURNING projected.
     *
     * <p><b>Order preservation (R141, {@code Cardinality.MANY} arm).</b> The bulk arm builds
     * a PK-keyed map of the SELECT result, then iterates {@code source.getValues(PK)} (the
     * upstream Result's PK list, which the mutation fetcher accumulated in input order) to
     * project rows in input order. This makes {@code output.data[i] = input[i]} a property
     * of the emitted Java code, not of any Postgres scan-order accident. The keying scheme is
     * the PK value for single-PK tables and {@code List.of(pk1, pk2, ...)} for composite-PK
     * tables, matching the WHERE-clause shape above. UPDATE no-match rows (the upstream
     * fetcher already rejects these with {@code IllegalStateException}) and INSERT
     * RETURNING-skipped rows (none, structurally) would surface here as {@code null} map
     * lookups, which the loop skips; the upstream contract is that every PK in {@code source}
     * resolves, so a skipped slot is a programming error, not a data condition.
     */
    private static CodeBlock buildSingleRecordTableFetcherValue(
            ChildField.SingleRecordTableField srtf, String outputPackage) {
        var sk = srtf.sourceKey();
        return switch (sk.wrap()) {
            case SourceKey.Wrap.Record r -> buildSingleRecordTableFetcherValueRecordWrap(srtf, outputPackage);
            case SourceKey.Wrap.TableRecord tr -> buildSingleRecordTableFetcherValueTableRecordWrap(srtf, tr, outputPackage);
            case SourceKey.Wrap.Row r -> throw new IllegalStateException(
                "SingleRecordTableField: SourceKey.Wrap.Row is rejected by the compact "
                + "constructor for Reader.ResultRowWalk; this case is unreachable.");
        };
    }

    /**
     * R75 Phase 1 / R141 — Wrap.Record arm of {@link #buildSingleRecordTableFetcherValue}. The
     * upstream DML mutation fetcher produced {@code Result<RecordN<PK>>} (MANY) or
     * {@code RecordN<PK>} (ONE) with the PK columns from {@code RETURNING}; the source cast is
     * typed against {@code RecordN<...>}. The MANY WHERE uses {@code source.getValues(<PK>)}
     * and the ONE WHERE uses {@code source.value1()} — both jOOQ {@code Result} /
     * {@code RecordN} APIs that the {@code Wrap.TableRecord} arm replaces with positional
     * {@code record.get(<Table.PK>)} reads.
     */
    private static CodeBlock buildSingleRecordTableFetcherValueRecordWrap(
            ChildField.SingleRecordTableField srtf, String outputPackage) {
        var table = srtf.returnType().table();
        var typeClass = ClassName.get(outputPackage + ".types", srtf.returnType().returnTypeName());
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var graphitronContextClass = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var sk = srtf.sourceKey();
        var rowType = sk.keyElementType();
        var pkColumns = sk.columns();
        boolean many = sk.cardinality() == SourceKey.Cardinality.MANY;

        var body = CodeBlock.builder().add("($T env) -> {\n", DATA_FETCHING_ENV);
        if (many) {
            var resultClass = ClassName.get("org.jooq", "Result");
            var resultOfRow = ParameterizedTypeName.get(resultClass, rowType);
            body.add("    $T source = ($T) env.getSource();\n", resultOfRow, resultOfRow);
            body.add("    if (source.isEmpty()) return source;\n");
        } else {
            body.add("    $T source = ($T) env.getSource();\n", rowType, rowType);
            body.add("    if (source == null) return null;\n");
        }
        body.add("    $T dsl = (($T) env.getGraphQlContext().get($T.class)).getDslContext(env);\n",
            dslContextClass, graphitronContextClass, graphitronContextClass);
        if (many) {
            var orgJooqRecord = ClassName.get("org.jooq", "Record");
            var javaUtilMap = ClassName.get("java.util", "Map");
            var javaUtilHashMap = ClassName.get("java.util", "HashMap");
            var javaUtilList = ClassName.get("java.util", "List");
            var javaUtilArrayList = ClassName.get("java.util", "ArrayList");
            var keyType = pkColumns.size() == 1
                ? ClassName.bestGuess(pkColumns.get(0).columnClass())
                : ParameterizedTypeName.get(javaUtilList, WildcardTypeName.subtypeOf(Object.class));
            var orgJooqResult = ClassName.get("org.jooq", "Result");
            var resultOfRecord = ParameterizedTypeName.get(orgJooqResult, orgJooqRecord);
            body.add("    $T fetched = dsl.select($T.$$fields(env.getSelectionSet(), $T.$L, env))\n",
                resultOfRecord, typeClass, table.constantsClass(), table.javaFieldName());
            body.add("        .from($T.$L)\n", table.constantsClass(), table.javaFieldName());
            body.add("        .where(");
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("$T.$L.$L.in(source.getValues($T.$L.$L))",
                    table.constantsClass(), table.javaFieldName(), col.javaName(),
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("$T.$L.$L", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(").in(source.stream().map(r -> $T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("r.get($T.$L.$L)", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")).toList())");
            }
            body.add(")\n");
            body.add("        .fetch();\n");
            // R141: PK-keyed-map indirection. The SELECT returns rows in Postgres's scan order
            // (no SQL ordering guarantee); re-key by PK then walk source's input-ordered PK
            // list to project in input order. See class-Javadoc above for the contract.
            body.add("    $T<$T, $T> byPk = new $T<>(fetched.size());\n",
                javaUtilMap, keyType, orgJooqRecord, javaUtilHashMap);
            body.add("    for ($T row : fetched) byPk.put(",
                orgJooqRecord);
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("row.get($T.$L.$L)",
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.of(", javaUtilList);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("row.get($T.$L.$L)",
                        table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")");
            }
            body.add(", row);\n");
            body.add("    $T<$T> ordered = new $T<>(source.size());\n",
                javaUtilList, orgJooqRecord, javaUtilArrayList);
            body.add("    for ($T sourceRow : source) {\n",
                rowType);
            body.add("        $T key = ", keyType);
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("sourceRow.get($T.$L.$L)",
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.of(", javaUtilList);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("sourceRow.get($T.$L.$L)",
                        table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")");
            }
            body.add(";\n");
            body.add("        $T match = byPk.get(key);\n", orgJooqRecord);
            body.add("        if (match != null) ordered.add(match);\n");
            body.add("    }\n");
            body.add("    return ordered;\n");
        } else {
            body.add("    return dsl.select($T.$$fields(env.getSelectionSet(), $T.$L, env))\n",
                typeClass, table.constantsClass(), table.javaFieldName());
            body.add("        .from($T.$L)\n", table.constantsClass(), table.javaFieldName());
            body.add("        .where(");
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("$T.$L.$L.eq(source.value1())",
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("$T.$L.$L", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(").eq($T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("source.get($T.$L.$L)", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add("))");
            }
            body.add(")\n");
            body.add("        .fetchOne();\n");
        }
        body.add("}");
        return body.build();
    }

    /**
     * R158 — Wrap.TableRecord arm of {@link #buildSingleRecordTableFetcherValue}. The upstream
     * {@code @service} mutation method returned {@code List<XRecord>} (MANY) or {@code XRecord}
     * (ONE) verbatim; the source cast is typed against the developer-declared {@code XRecord}
     * class, pinned to the data table's record class by R178 step 3's structural strict-return
     * check on the {@code @service} payload's single {@code @table}-typed data field and by
     * {@code ProducerBinding.ServiceEmitted}'s structural ground (the method's reflected return-
     * element class must equal the inner table's record class). PK extraction goes through the
     * typed {@code record.get(<Table.PK_FIELD>)} accessors, paralleling the {@code Wrap.Record}
     * arm's map-key shape but typed against {@code XRecord} instead of {@code RecordN}.
     */
    private static CodeBlock buildSingleRecordTableFetcherValueTableRecordWrap(
            ChildField.SingleRecordTableField srtf,
            SourceKey.Wrap.TableRecord tableRecordWrap,
            String outputPackage) {
        var table = srtf.returnType().table();
        var typeClass = ClassName.get(outputPackage + ".types", srtf.returnType().returnTypeName());
        var dslContextClass = ClassName.get("org.jooq", "DSLContext");
        var graphitronContextClass = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var sk = srtf.sourceKey();
        var recordType = tableRecordWrap.className();
        var pkColumns = sk.columns();
        boolean many = sk.cardinality() == SourceKey.Cardinality.MANY;
        var javaUtilList = ClassName.get("java.util", "List");
        // R275: read the source envelope off the field's SourceKey (recorded at classification).
        // Under OUTCOME_SUCCESS the @service producer wrapped its returned record in a non-null
        // Outcome, so narrow Success and read the record off success.value(), resolving null on the
        // ErrorList arm; under DIRECT the record is env.getSource() verbatim. The SourceKey
        // invariant pins OUTCOME_SUCCESS to this (Wrap.TableRecord) arm, so the Record (DML) arm
        // needs no envelope branch.
        boolean outcomeWrapped = ((SourceKey.Reader.ResultRowWalk) srtf.sourceKey().reader()).envelope()
            == SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS;
        CodeBlock sourceExpr = outcomeWrapped
            ? CodeBlock.of("success.value()")
            : CodeBlock.of("env.getSource()");

        var body = CodeBlock.builder().add("($T env) -> {\n", DATA_FETCHING_ENV);
        if (outcomeWrapped) {
            body.add("    if (!(env.getSource() instanceof $T<?> success)) return null;\n",
                successClass(outputPackage));
        }
        if (many) {
            var listOfRecord = ParameterizedTypeName.get(javaUtilList, recordType);
            body.add("    $T source = ($T) $L;\n", listOfRecord, listOfRecord, sourceExpr);
            body.add("    if (source.isEmpty()) return source;\n");
        } else {
            body.add("    $T source = ($T) $L;\n", recordType, recordType, sourceExpr);
            body.add("    if (source == null) return null;\n");
        }
        body.add("    $T dsl = (($T) env.getGraphQlContext().get($T.class)).getDslContext(env);\n",
            dslContextClass, graphitronContextClass, graphitronContextClass);
        if (many) {
            var orgJooqRecord = ClassName.get("org.jooq", "Record");
            var javaUtilMap = ClassName.get("java.util", "Map");
            var javaUtilHashMap = ClassName.get("java.util", "HashMap");
            var javaUtilArrayList = ClassName.get("java.util", "ArrayList");
            var keyType = pkColumns.size() == 1
                ? ClassName.bestGuess(pkColumns.get(0).columnClass())
                : ParameterizedTypeName.get(javaUtilList, WildcardTypeName.subtypeOf(Object.class));
            var orgJooqResult = ClassName.get("org.jooq", "Result");
            var resultOfRecord = ParameterizedTypeName.get(orgJooqResult, orgJooqRecord);
            body.add("    $T fetched = dsl.select($T.$$fields(env.getSelectionSet(), $T.$L, env))\n",
                resultOfRecord, typeClass, table.constantsClass(), table.javaFieldName());
            body.add("        .from($T.$L)\n", table.constantsClass(), table.javaFieldName());
            body.add("        .where(");
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("$T.$L.$L.in(source.stream().map(r -> r.get($T.$L.$L)).toList())",
                    table.constantsClass(), table.javaFieldName(), col.javaName(),
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("$T.$L.$L", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(").in(source.stream().map(r -> $T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("r.get($T.$L.$L)", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")).toList())");
            }
            body.add(")\n");
            body.add("        .fetch();\n");
            // R141 order-preservation: PK-keyed-map indirection. Mirrors the Wrap.Record arm
            // exactly; only the source row type differs (XRecord vs RecordN), and the PK
            // accessors are positional record.get(<Table.PK>) reads in both arms.
            body.add("    $T<$T, $T> byPk = new $T<>(fetched.size());\n",
                javaUtilMap, keyType, orgJooqRecord, javaUtilHashMap);
            body.add("    for ($T row : fetched) byPk.put(",
                orgJooqRecord);
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("row.get($T.$L.$L)",
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.of(", javaUtilList);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("row.get($T.$L.$L)",
                        table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")");
            }
            body.add(", row);\n");
            body.add("    $T<$T> ordered = new $T<>(source.size());\n",
                javaUtilList, orgJooqRecord, javaUtilArrayList);
            body.add("    for ($T sourceRow : source) {\n",
                recordType);
            body.add("        $T key = ", keyType);
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("sourceRow.get($T.$L.$L)",
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.of(", javaUtilList);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("sourceRow.get($T.$L.$L)",
                        table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(")");
            }
            body.add(";\n");
            body.add("        $T match = byPk.get(key);\n", orgJooqRecord);
            body.add("        if (match != null) ordered.add(match);\n");
            body.add("    }\n");
            body.add("    return ordered;\n");
        } else {
            body.add("    return dsl.select($T.$$fields(env.getSelectionSet(), $T.$L, env))\n",
                typeClass, table.constantsClass(), table.javaFieldName());
            body.add("        .from($T.$L)\n", table.constantsClass(), table.javaFieldName());
            body.add("        .where(");
            if (pkColumns.size() == 1) {
                var col = pkColumns.get(0);
                body.add("$T.$L.$L.eq(source.get($T.$L.$L))",
                    table.constantsClass(), table.javaFieldName(), col.javaName(),
                    table.constantsClass(), table.javaFieldName(), col.javaName());
            } else {
                body.add("$T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("$T.$L.$L", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add(").eq($T.row(", DSL);
                for (int i = 0; i < pkColumns.size(); i++) {
                    if (i > 0) body.add(", ");
                    var col = pkColumns.get(i);
                    body.add("source.get($T.$L.$L)", table.constantsClass(), table.javaFieldName(), col.javaName());
                }
                body.add("))");
            }
            body.add(")\n");
            body.add("        .fetchOne();\n");
        }
        body.add("}");
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
        var body = CodeBlock.builder().add("($T env) -> {\n", DATA_FETCHING_ENV);
        if (isList) {
            var resultOfRecord = ParameterizedTypeName.get(jooqResult, jooqRecord);
            var stringClass = ClassName.get("java.lang", "String");
            var arrayListOfString = ParameterizedTypeName.get(
                ClassName.get("java.util", "ArrayList"), stringClass);
            var listOfString = ParameterizedTypeName.get(
                ClassName.get("java.util", "List"), stringClass);
            body.add("    $T source = ($T) env.getSource();\n", resultOfRecord, resultOfRecord);
            body.add("    if (source == null) return null;\n");
            body.add("    $T ids = new $T(source.size());\n", listOfString, arrayListOfString);
            body.add("    for ($T row : source) {\n", jooqRecord);
            body.add("        ids.add($T.$L(", encoderClass, encoderMethod);
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("row.get(($T<$T>) $T.field($S, $T.class))",
                    ClassName.get("org.jooq", "Field"),
                    ClassName.bestGuess(pkColumns.get(i).columnClass()),
                    DSL, pkColumns.get(i).sqlName(),
                    ClassName.bestGuess(pkColumns.get(i).columnClass()));
            }
            body.add("));\n");
            body.add("    }\n");
            body.add("    return ids;\n");
        } else {
            body.add("    $T source = ($T) env.getSource();\n", jooqRecord, jooqRecord);
            body.add("    if (source == null) return null;\n");
            body.add("    return $T.$L(", encoderClass, encoderMethod);
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) body.add(", ");
                body.add("source.get(($T<$T>) $T.field($S, $T.class))",
                    ClassName.get("org.jooq", "Field"),
                    ClassName.bestGuess(pkColumns.get(i).columnClass()),
                    DSL, pkColumns.get(i).sqlName(),
                    ClassName.bestGuess(pkColumns.get(i).columnClass()));
            }
            body.add(");\n");
        }
        body.add("}");
        return body.build();
    }

    /**
     * R156 — data-fetcher value for a {@link ChildField.SingleRecordTableFieldFromReturning}.
     * Synthesizes per source row a new jOOQ {@code Record} keyed to the element {@code @table}'s
     * column shape, populated with PK column values copied from the RETURNING source row.
     * Non-PK column slots remain null on the synthesized Record, so the per-field
     * {@code ColumnFetcher} on the element type returns null for {@code NonPkNullable} arms of
     * the projection. No follow-up SELECT runs — the row is gone.
     *
     * <p>The synthesis approach relies on
     * {@code BuildContext.classifyDeleteTableProjection} rejecting any element-type field that
     * cannot resolve from a PK-only synthesized Record (FK references, child collections,
     * computed fields, {@code @service}-resolved fields). Relaxing the rejection rule (e.g.
     * admitting a {@code ColumnReferenceField} as nullable) would surface here as a runtime
     * error when the per-field fetcher tries to read an aliased joined column off a synthesized
     * Record that doesn't carry it.
     *
     * <p>The encoder reference for PK-encoded scalar (the SDL {@code id} alias) is NOT needed in
     * this emitter — the existing per-field {@link ChildField.ColumnField} /
     * {@link ChildField.CompositeColumnField} fetcher consumes the
     * {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys} compaction
     * off its own slot and runs the encoder at fetch time. The synthesized Record carries the
     * raw PK column value; the encoder runs downstream.
     *
     * <p><b>Same-Field-instance round-trip.</b> The PK copy emits
     * {@code row.set(Tables.FILM.FILM_ID, sourceRow.get(Tables.FILM.FILM_ID))}: the same
     * {@code Field<T>} instance reads the source and writes the synthesized Record. This is
     * what makes the copy type-erasure-free in the generated code — jOOQ's
     * {@code Field<T>}-typed accessor returns and accepts the same {@code T}, so no cast or
     * boxing widening is generated. The load-bearing assumption is therefore not only "the
     * projection rejects FK / service / non-PK-non-null fields" (which lets the synthesis
     * succeed) but also "source and target Records resolve to the same generated table class"
     * (which lets the per-column copy compile). The @mutation classifier pins the input
     * {@code @table} on both sides; a future change that synthesizes a Record over a different
     * jOOQ-generated class than the source RETURNING reads from would need to revisit this.
     */
    private static CodeBlock buildSingleRecordTableFromReturningFetcherValue(
            ChildField.SingleRecordTableFieldFromReturning carrier) {
        var table = carrier.returnType().table();
        var jooqRecord = ClassName.get("org.jooq", "Record");
        var jooqResult = ClassName.get("org.jooq", "Result");
        boolean isList = carrier.returnType().wrapper().isList();
        // PK column set, in declaration order (declaration-order matches the synthesized Record's
        // expected column ordering — jOOQ stores by index but is also addressable by Field<T>).
        var pkColumns = table.primaryKeyColumns();
        var body = CodeBlock.builder().add("($T env) -> {\n", DATA_FETCHING_ENV);
        // Synthesize via the table's per-instance newRecord(). The static Tables.<TABLE> field
        // is typed to the table's class (e.g. `Film`), which exposes the per-column accessors
        // (`FILM.FILM_ID`) and the newRecord() factory.
        var arrayListOfRecord = ParameterizedTypeName.get(
            ClassName.get("java.util", "ArrayList"), jooqRecord);
        var listOfRecord = ParameterizedTypeName.get(
            ClassName.get("java.util", "List"), jooqRecord);
        if (isList) {
            var resultOfRecord = ParameterizedTypeName.get(jooqResult, jooqRecord);
            body.add("    $T source = ($T) env.getSource();\n", resultOfRecord, resultOfRecord);
            body.add("    if (source == null) return null;\n");
            body.add("    $T out = new $T(source.size());\n", listOfRecord, arrayListOfRecord);
            body.add("    for ($T sourceRow : source) {\n", jooqRecord);
            body.add("        $T row = $T.$L.newRecord();\n",
                jooqRecord, table.constantsClass(), table.javaFieldName());
            for (var pk : pkColumns) {
                body.add("        row.set($T.$L.$L, sourceRow.get($T.$L.$L));\n",
                    table.constantsClass(), table.javaFieldName(), pk.javaName(),
                    table.constantsClass(), table.javaFieldName(), pk.javaName());
            }
            body.add("        out.add(row);\n");
            body.add("    }\n");
            body.add("    return out;\n");
        } else {
            body.add("    $T sourceRow = ($T) env.getSource();\n", jooqRecord, jooqRecord);
            body.add("    if (sourceRow == null) return null;\n");
            body.add("    $T row = $T.$L.newRecord();\n",
                jooqRecord, table.constantsClass(), table.javaFieldName());
            for (var pk : pkColumns) {
                body.add("    row.set($T.$L.$L, sourceRow.get($T.$L.$L));\n",
                    table.constantsClass(), table.javaFieldName(), pk.javaName(),
                    table.constantsClass(), table.javaFieldName(), pk.javaName());
            }
            body.add("    return row;\n");
        }
        body.add("}");
        return body.build();
    }

    private static CodeBlock propertyOrRecordValue(
            String columnName, ColumnRef column, GraphitronType.ResultType resultType,
            AccessorResolution.Resolved accessor, String outputPackage) {
        var columnFetcherClass = ClassName.get(outputPackage + ".util",
            ColumnFetcherClassGenerator.CLASS_NAME);
        if (resultType instanceof GraphitronType.JooqTableRecordType jtrt
                && column != null && jtrt.table() != null) {
            return CodeBlock.of("new $T<>($T.$L.$L)",
                columnFetcherClass, jtrt.table().constantsClass(), jtrt.table().javaFieldName(), column.javaName());
        }
        if (resultType instanceof GraphitronType.JooqTableRecordType
                || resultType instanceof GraphitronType.JooqRecordType) {
            return CodeBlock.of("new $T<>($T.field($S))", columnFetcherClass, DSL, columnName);
        }
        // @record-Java-backed parent: read the pre-resolved accessor handle. The read itself is the
        // shared recordBackedAccessorRead, source-bound to env.getSource(); the R268 arm-switch path
        // calls the same helper with success.value(), so the accessor switch lives in one place.
        String fqClassName = (resultType instanceof GraphitronType.JavaRecordType jrt)
            ? jrt.fqClassName()
            : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
        var backingClass = ClassName.bestGuess(fqClassName);
        return CodeBlock.of("($T env) -> $L",
            DATA_FETCHING_ENV, recordBackedAccessorRead(backingClass, accessor, ENV_SOURCE));
    }

    /**
     * The value expression reading a {@code @record}-Java-backed accessor off a source object. The
     * source is supplied as a {@link CodeBlock} ({@code env.getSource()} on the normal path,
     * {@code success.value()} on the R268 outcome arm-switch), so this one helper serves both the
     * normal {@link #propertyOrRecordValue} lambda and the arm-switch ternary. Field reads emit
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
