package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
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
     * @param jooqPackage   the jOOQ-generated package (e.g. {@code no.sikt.jooq})
     */
    public static CodeBlock dataFetcherValue(
            GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, String jooqPackage) {
        if (field instanceof ChildField.ConstructorField) {
            return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
        }
        if (field instanceof ChildField.NestingField) {
            return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
        }
        if (field instanceof ChildField.PropertyField pf && resultType != null) {
            return propertyOrRecordValue(pf.columnName(), pf.column(), resultType, outputPackage, jooqPackage);
        }
        if (field instanceof ChildField.RecordField rf && resultType != null) {
            return propertyOrRecordValue(rf.columnName(), rf.column(), resultType, outputPackage, jooqPackage);
        }
        if (field instanceof ChildField.ColumnField cf && parentTable != null) {
            var columnFetcherClass = ClassName.get(outputPackage + ".util",
                ColumnFetcherClassGenerator.CLASS_NAME);
            var tablesClass = ClassName.get(jooqPackage, "Tables");
            return CodeBlock.of("new $T<>($T.$L.$L)",
                columnFetcherClass, tablesClass,
                parentTable.javaFieldName(), cf.column().javaName());
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
        if (field instanceof ChildField.NodeIdField nf && parentTable != null) {
            var encoderClass = ClassName.get(outputPackage + ".util",
                NodeIdEncoderClassGenerator.CLASS_NAME);
            var recordClass = ClassName.get("org.jooq", "Record");
            var tablesClass = ClassName.get(jooqPackage, "Tables");
            var body = CodeBlock.builder()
                .add("($T env) -> {\n", DATA_FETCHING_ENV)
                .add("    $T r = ($T) env.getSource();\n", recordClass, recordClass)
                .add("    return $T.encode($S", encoderClass, nf.nodeTypeId());
            for (var col : nf.nodeKeyColumns()) {
                body.add(",\n        r.get($T.$L.$L)", tablesClass, parentTable.javaFieldName(), col.javaName());
            }
            body.add(");\n").add("}");
            return body.build();
        }
        if (field instanceof ChildField.NodeIdReferenceField nrf && parentTable != null) {
            // FK-mirror collapse — encode the parent's FK source columns directly. See
            // TypeClassGenerator#fkMirrorSourceColumns; matching code paths must agree on which
            // columns get projected.
            var fkMirror = no.sikt.graphitron.rewrite.generators.TypeClassGenerator.fkMirrorSourceColumns(nrf);
            if (fkMirror != null) {
                var encoderClass = ClassName.get(outputPackage + ".util",
                    NodeIdEncoderClassGenerator.CLASS_NAME);
                var recordClass = ClassName.get("org.jooq", "Record");
                var tablesClass = ClassName.get(jooqPackage, "Tables");
                var body = CodeBlock.builder()
                    .add("($T env) -> {\n", DATA_FETCHING_ENV)
                    .add("    $T r = ($T) env.getSource();\n", recordClass, recordClass)
                    .add("    return $T.encode($S", encoderClass, nrf.nodeTypeId());
                for (var col : fkMirror) {
                    body.add(",\n        r.get($T.$L.$L)", tablesClass, parentTable.javaFieldName(), col.javaName());
                }
                body.add(");\n").add("}");
                return body.build();
            }
            // Non-FK-mirror cases (composite-FK that doesn't mirror, multi-hop, condition-join)
            // fall through to a runtime-throwing stub by default. Lifted to the JOIN-projection
            // form in a follow-up.
            return CodeBlock.of(
                "($T env) -> { throw new $T($S); }",
                DATA_FETCHING_ENV, UnsupportedOperationException.class,
                "NodeIdReferenceField '" + nrf.parentTypeName() + "." + nrf.name()
                    + "' requires the JOIN-projection form (composite FK or multi-hop) which "
                    + "is not yet implemented; the FK-mirror case is supported. See graphitron-rewrite/roadmap/nodeidreferencefield-join-projection-form.md.");
        }
        return CodeBlock.of("$T::$L", fetchersClass, field.name());
    }

    private static CodeBlock propertyOrRecordValue(
            String columnName, ColumnRef column, GraphitronType.ResultType resultType,
            String outputPackage, String jooqPackage) {
        var columnFetcherClass = ClassName.get(outputPackage + ".util",
            ColumnFetcherClassGenerator.CLASS_NAME);
        if (resultType instanceof GraphitronType.JooqTableRecordType jtrt
                && column != null && jtrt.table() != null) {
            var tablesClass = ClassName.get(jooqPackage, "Tables");
            return CodeBlock.of("new $T<>($T.$L.$L)",
                columnFetcherClass, tablesClass, jtrt.table().javaFieldName(), column.javaName());
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
