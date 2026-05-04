package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.toCamelCase;

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
        if (field instanceof ChildField.ConstructorField) {
            return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
        }
        if (field instanceof ChildField.NestingField) {
            return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
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
            return propertyOrRecordValue(pf.columnName(), pf.column(), resultType, outputPackage);
        }
        if (field instanceof ChildField.RecordField rf && resultType != null) {
            return propertyOrRecordValue(rf.columnName(), rf.column(), resultType, outputPackage);
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
                && crf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys
                && parentTable != null) {
            // Single-column rooted-at-parent NodeId reference (single-hop or correlated-subquery
            // cases via R24's path machinery) — runtime stub until R24 lands the
            // JOIN-with-projection emission ($fields-side JOIN extension). FK-mirror cases collapse
            // to ColumnField at the classifier so they never reach this arm.
            return CodeBlock.of(
                "($T env) -> { throw new $T($S); }",
                DATA_FETCHING_ENV, UnsupportedOperationException.class,
                "Rooted-at-parent NodeId reference '" + crf.parentTypeName() + "." + crf.name()
                    + "' requires the JOIN-with-projection emission tracked in R24 — not yet implemented.");
        }
        if (field instanceof ChildField.CompositeColumnReferenceField ccrf && parentTable != null) {
            // Composite-key rooted-at-parent NodeId reference — same runtime stub as the
            // single-column reference above. Composite reference projection is always a NodeId
            // encode call (compaction is type-narrowed to NodeIdEncodeKeys).
            return CodeBlock.of(
                "($T env) -> { throw new $T($S); }",
                DATA_FETCHING_ENV, UnsupportedOperationException.class,
                "Rooted-at-parent composite NodeId reference '" + ccrf.parentTypeName() + "." + ccrf.name()
                    + "' requires the JOIN-with-projection emission tracked in R24 — not yet implemented.");
        }
        return CodeBlock.of("$T::$L", fetchersClass, field.name());
    }

    private static CodeBlock propertyOrRecordValue(
            String columnName, ColumnRef column, GraphitronType.ResultType resultType,
            String outputPackage) {
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
        if (resultType instanceof GraphitronType.JavaRecordType jrt) {
            var backingClass = ClassName.bestGuess(jrt.fqClassName());
            var accessor = toCamelCase(columnName);
            return CodeBlock.of("($T env) -> (($T) env.getSource()).$L()", DATA_FETCHING_ENV, backingClass, accessor);
        }
        var prt = (GraphitronType.PojoResultType) resultType;
        if (prt.fqClassName() != null) {
            var backingClass = ClassName.bestGuess(prt.fqClassName());
            var accessorBase = toCamelCase(columnName);
            var getter = "get" + Character.toUpperCase(accessorBase.charAt(0)) + accessorBase.substring(1);
            return CodeBlock.of("($T env) -> (($T) env.getSource()).$L()", DATA_FETCHING_ENV, backingClass, getter);
        }
        var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
        return CodeBlock.of("$T.fetching($S)", propertyDataFetcher, columnName);
    }
}
