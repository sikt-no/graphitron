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
import no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

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

    private FetcherEmitter() {}

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
     */
    public static CodeBlock dataFetcherValue(
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
        if (field instanceof ChildField.ErrorsField) {
            // Passthrough off the parent payload's errors accessor. PropertyDataFetcher reflects
            // through record-style accessor → JavaBean getter → field, which covers every payload
            // backing class shape (JavaRecordType / PojoResultType / untyped). The runtime carrier
            // lives on the payload as the SDL field name; per-error dispatch and try/catch
            // wrapping ship later in error-handling-parity.md (carrier classifier + dispatch arm).
            var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
            return CodeBlock.of("$T.fetching($S)", propertyDataFetcher, field.name());
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
     * R75 Phase 1: data-fetcher value for a {@link ChildField.SingleRecordTableField}. Reads
     * {@code env.getSource()} typed by {@code SourceKey.wrap × columns} and runs the response
     * SELECT keyed by the PK columns. The upstream value is whatever the mutation's two-step
     * fetcher returned: a single {@code RecordN<...>} when the input was single
     * ({@code Cardinality.ONE}) or a {@code Result<RecordN<...>>} when the input was bulk
     * ({@code Cardinality.MANY}).
     *
     * <p>Single-PK tables (the sakila fixtures) emit {@code where(PK.eq(value))} or
     * {@code where(PK.in(getValues(PK)))}; composite-PK tables emit {@code where(row(PK1,...).
     * in(...))}. The {@code mutation-dml-record-field.data-table-equals-input-table} load-
     * bearing key pins that the data field's table equals the input table, so the PK columns
     * here are exactly what the upstream RETURNING projected.
     */
    @DependsOnClassifierCheck(
        key = "mutation-dml-record-field.data-table-equals-input-table",
        reliesOn = "The response SELECT's WHERE predicate reads source.getValues(<DataTable.PK>) "
            + "/ source.value1() against the upstream Result's row type. That row type is the "
            + "DML's input @table PK columns (the mutation fetcher's RETURNING clause), which "
            + "the load-bearing classifier check forces to equal the data field's element table "
            + "PK columns. Without that equality the source.getValues call would request a "
            + "column the upstream Result does not carry.")
    @DependsOnClassifierCheck(
        key = "source-key.result-row-walk-cardinality-matches-upstream-result",
        reliesOn = "The SingleRecordTableField permit's compact constructor requires "
            + "Reader.ResultRowWalk, and SourceKey's compact constructor pins ResultRowWalk to "
            + "Wrap.Record with empty path. The emitted source cast (Result<RecordN<...>> for "
            + "MANY, RecordN<...> for ONE) reads SourceKey.wrap × columns directly.")
    private static CodeBlock buildSingleRecordTableFetcherValue(
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
            var resultOfRow = no.sikt.graphitron.javapoet.ParameterizedTypeName.get(resultClass, rowType);
            body.add("    $T source = ($T) env.getSource();\n", resultOfRow, resultOfRow);
            body.add("    if (source.isEmpty()) return source;\n");
        } else {
            body.add("    $T source = ($T) env.getSource();\n", rowType, rowType);
            body.add("    if (source == null) return null;\n");
        }
        body.add("    $T dsl = (($T) env.getGraphQlContext().get($T.class)).getDslContext(env);\n",
            dslContextClass, graphitronContextClass, graphitronContextClass);
        body.add("    return dsl.select($T.$$fields(env.getSelectionSet(), $T.$L, env))\n",
            typeClass, table.constantsClass(), table.javaFieldName());
        body.add("        .from($T.$L)\n", table.constantsClass(), table.javaFieldName());
        if (many) {
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
        } else {
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

    @DependsOnClassifierCheck(
        key = "class-accessor-resolver-shape-guarantee",
        reliesOn = "Reads the pre-resolved Method or Field handle off AccessorResolution.Resolved. "
            + "FieldBuilder routes Rejected through UnclassifiedField, and the slot type on "
            + "PropertyField/RecordField is AccessorResolution.Resolved (statically), so the @record-Java "
            + "branch below has no Rejected or runtime-heuristic case to fall back to.")
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
        if (resultType instanceof GraphitronType.PojoResultType.NoBacking) {
            var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
            return CodeBlock.of("$T.fetching($S)", propertyDataFetcher, columnName);
        }
        // @record-Java-backed parent: read the pre-resolved accessor handle.
        String fqClassName = (resultType instanceof GraphitronType.JavaRecordType jrt)
            ? jrt.fqClassName()
            : ((GraphitronType.PojoResultType.Backed) resultType).fqClassName();
        var backingClass = ClassName.bestGuess(fqClassName);
        return switch (accessor) {
            case AccessorResolution.GetterPrefixed gp -> methodCallExpr(backingClass, gp.method());
            case AccessorResolution.BareName bn -> methodCallExpr(backingClass, bn.method());
            case AccessorResolution.FieldRead fr -> CodeBlock.of("($T env) -> (($T) env.getSource()).$L",
                DATA_FETCHING_ENV, backingClass, fr.field().getName());
        };
    }

    /**
     * Emits the method-call expression for a resolved accessor. Three injection forms:
     * zero-arg ({@code .name()}), full-environment ({@code .name(env)} when the method takes a
     * single {@code DataFetchingEnvironment}), or per-argument ({@code .name(($T) env.getArgument($S), …)}
     * — uses the candidate method's reflected parameter names as the SDL argument keys, which
     * holds when the consumer compiles with {@code -parameters}).
     */
    private static CodeBlock methodCallExpr(ClassName backingClass, java.lang.reflect.Method method) {
        var paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return CodeBlock.of("($T env) -> (($T) env.getSource()).$L()",
                DATA_FETCHING_ENV, backingClass, method.getName());
        }
        if (paramTypes.length == 1 && "graphql.schema.DataFetchingEnvironment".equals(paramTypes[0].getName())) {
            return CodeBlock.of("($T env) -> (($T) env.getSource()).$L(env)",
                DATA_FETCHING_ENV, backingClass, method.getName());
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
        return CodeBlock.of("($T env) -> (($T) env.getSource()).$L($L)",
            DATA_FETCHING_ENV, backingClass, method.getName(), argsBuilder.build());
    }
}
