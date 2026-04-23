package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.generators.util.ColumnFetcherClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

/**
 * Generates one {@code <TypeName>Wiring} class per GraphQL object type, emitted to
 * {@code <outputPackage>.rewrite.wiring}. Each class has a single
 * {@code public static TypeRuntimeWiring.Builder wiring()} method.
 *
 * <p>Covers five categories:
 * <ol>
 *   <li>Regular object types — {@code FilmWiring.wiring()} references {@code FilmFetchers::method}.</li>
 *   <li>Nested object types with {@link BatchKeyField} leaves — mixes fetcher references with
 *       inline {@code ColumnFetcher} bindings.</li>
 *   <li>Nested object types without {@link BatchKeyField} leaves — uses inline bindings only.</li>
 *   <li>Connection types — binds {@code edges}, {@code nodes}, {@code pageInfo} to
 *       {@link ConnectionHelperClassGenerator}.</li>
 *   <li>Edge types — binds {@code node}, {@code cursor} to {@link ConnectionHelperClassGenerator}.</li>
 * </ol>
 *
 * <p>Output is sorted alphabetically by class name for stable generated-source diffs.
 */
public class WiringClassGenerator {

    private static final ClassName TYPE_WIRING    = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring");
    private static final ClassName WIRING_BUILDER = ClassName.get("graphql.schema.idl", "TypeRuntimeWiring", "Builder");

    private record ConnectionWiring(String connectionTypeName, String edgeTypeName) {}

    /**
     * @param nestedTypeName            the GraphQL type name (e.g. {@code "FilmMeta"})
     * @param fields                    the classified nested fields in SDL order
     * @param representativeParentTable first-seen parent's table; column Field references resolve
     *                                  against this table at generation time (first-occurrence-wins)
     */
    private record NestedTypeWiring(
            String nestedTypeName,
            List<ChildField> fields,
            TableRef representativeParentTable) {}

    public static List<TypeSpec> generate(GraphitronSchema schema) {
        String fetchersPackage = RewriteConfig.outputPackage() + ".rewrite.fetchers";
        String rewritePackage  = RewriteConfig.outputPackage() + ".rewrite";

        // Collect one entry per distinct Connection type; first-seen edge name wins.
        var connectionTypeMap = new LinkedHashMap<String, String>();
        schema.fields().forEach((coords, field) -> {
            if (field instanceof SqlGeneratingField sgf
                    && sgf.returnType().wrapper() instanceof FieldWrapper.Connection conn) {
                String parentType = coords.getTypeName();
                String fieldName  = coords.getFieldName();
                String connName   = conn.connectionName() != null
                    ? conn.connectionName()
                    : parentType + capitalize(fieldName) + "Connection";
                connectionTypeMap.putIfAbsent(connName, connName.replace("Connection", "Edge"));
            }
        });

        // Collect one entry per distinct nested object type; first-seen parent table wins.
        var nestedTypeMap = new LinkedHashMap<String, NestedTypeWiring>();
        schema.fields().values().forEach(field -> collectNestedTypes(field, nestedTypeMap));

        var result = new ArrayList<TypeSpec>();

        // Categories 1, 2, 3: regular and nested object types.
        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> result.add(generateTypeWiring(schema, e.getKey(), fetchersPackage)));

        nestedTypeMap.values().forEach(ntw ->
            result.add(generateNestedTypeWiring(ntw, fetchersPackage)));

        // Categories 4, 5: connection and edge types.
        connectionTypeMap.forEach((connName, edgeName) -> {
            result.add(generateConnectionWiring(connName, rewritePackage));
            result.add(generateEdgeWiring(edgeName, rewritePackage));
        });

        result.sort(Comparator.comparing(t -> t.name()));
        return result;
    }

    private static TypeSpec generateTypeWiring(GraphitronSchema schema, String typeName,
            String fetchersPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.NotGeneratedField))
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        ClassName fetchersClass = ClassName.get(fetchersPackage, typeName + "Fetchers");
        return buildWiringTypeSpec(typeName, fields, fetchersClass, parentTable, resultType);
    }

    private static TypeSpec generateNestedTypeWiring(NestedTypeWiring ntw, String fetchersPackage) {
        ClassName nestedFetchersClass = ntw.fields().stream().anyMatch(f -> f instanceof BatchKeyField)
            ? ClassName.get(fetchersPackage, ntw.nestedTypeName() + "Fetchers") : null;

        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, ntw.nestedTypeName())
            .indent();
        for (var field : ntw.fields()) {
            if (nestedFetchersClass != null && field instanceof BatchKeyField) {
                body.add("\n.dataFetcher($S, $T::$L)", field.name(), nestedFetchersClass, field.name());
            } else {
                body.add(buildWiringEntry(field, null, ntw.representativeParentTable(), null));
            }
        }
        body.add(";\n").unindent();

        return TypeSpec.classBuilder(ntw.nestedTypeName() + "Wiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(wiringMethod(body.build()))
            .build();
    }

    private static TypeSpec generateConnectionWiring(String connectionTypeName, String rewritePackage) {
        var helper = ClassName.get(rewritePackage, ConnectionHelperClassGenerator.CLASS_NAME);
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, connectionTypeName)
            .indent()
            .add("\n.dataFetcher(\"edges\", $T::edges)", helper)
            .add("\n.dataFetcher(\"nodes\", $T::nodes)", helper)
            .add("\n.dataFetcher(\"pageInfo\", $T::pageInfo);\n", helper)
            .unindent();
        return TypeSpec.classBuilder(connectionTypeName + "Wiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(wiringMethod(body.build()))
            .build();
    }

    private static TypeSpec generateEdgeWiring(String edgeTypeName, String rewritePackage) {
        var helper = ClassName.get(rewritePackage, ConnectionHelperClassGenerator.CLASS_NAME);
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, edgeTypeName)
            .indent()
            .add("\n.dataFetcher(\"node\", $T::edgeNode)", helper)
            .add("\n.dataFetcher(\"cursor\", $T::edgeCursor);\n", helper)
            .unindent();
        return TypeSpec.classBuilder(edgeTypeName + "Wiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(wiringMethod(body.build()))
            .build();
    }

    private static TypeSpec buildWiringTypeSpec(String typeName, List<GraphitronField> fields,
            ClassName fetchersClass, TableRef parentTable, GraphitronType.ResultType resultType) {
        var body = CodeBlock.builder()
            .add("return $T.newTypeWiring($S)", TYPE_WIRING, typeName);
        if (fields.isEmpty()) {
            body.add(";\n");
        } else {
            body.indent();
            for (var field : fields) {
                body.add(buildWiringEntry(field, fetchersClass, parentTable, resultType));
            }
            body.add(";\n").unindent();
        }
        return TypeSpec.classBuilder(typeName + "Wiring")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(wiringMethod(body.build()))
            .build();
    }

    private static MethodSpec wiringMethod(CodeBlock body) {
        return MethodSpec.methodBuilder("wiring")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(WIRING_BUILDER)
            .addCode(body)
            .build();
    }

    // --- Moved from TypeFetcherGenerator ---

    /**
     * Builds the {@code .dataFetcher(name, …)} wiring entry for one field.
     * Column fields with a parent table use {@code ColumnFetcher} directly.
     * Property/record fields on {@code @record} parents use backing-object accessors.
     * All other fields use a plain method reference to the corresponding {@code *Fetchers} class.
     */
    private static CodeBlock buildWiringEntry(GraphitronField field, ClassName fetchersClass,
            TableRef parentTable, GraphitronType.ResultType resultType) {
        if (field instanceof ChildField.ConstructorField cf) {
            return CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", cf.name());
        }
        if (field instanceof ChildField.NestingField nf) {
            return CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name());
        }
        if (field instanceof ChildField.PropertyField pf && resultType != null) {
            return buildPropertyOrRecordFetcherEntry(pf.name(), pf.columnName(), pf.column(), resultType);
        }
        if (field instanceof ChildField.RecordField rf && resultType != null) {
            return buildPropertyOrRecordFetcherEntry(rf.name(), rf.columnName(), rf.column(), resultType);
        }
        if (field instanceof ChildField.ColumnField cf && parentTable != null) {
            var columnFetcherClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
                ColumnFetcherClassGenerator.CLASS_NAME);
            var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.$L.$L))",
                field.name(), columnFetcherClass, tablesClass,
                parentTable.javaFieldName(), cf.column().javaName());
        }
        if (field instanceof ChildField.TableField tf) {
            // Inline projection: the parent record carries this field as an aliased multiset.
            // For single cardinality, unwrap to the first row (or null).
            boolean single = tf.returnType().wrapper() instanceof FieldWrapper.Single;
            if (single) {
                var recordClass = ClassName.get("org.jooq", "Record");
                var resultClass = ClassName.get("org.jooq", "Result");
                var resultWildcard = ParameterizedTypeName.get(resultClass, WildcardTypeName.subtypeOf(Object.class));
                return CodeBlock.of(
                    "\n.dataFetcher($S, env -> { Object raw = (($T) env.getSource()).get($S, $T.class); return raw instanceof $T r && !r.isEmpty() ? r.get(0) : null; })",
                    field.name(), recordClass, field.name(), resultClass, resultWildcard);
            }
            var columnFetcherClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.field($S)))",
                field.name(), columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.LookupTableField) {
            // Inline projection: same outer shape as TableField list cardinality.
            var columnFetcherClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
                ColumnFetcherClassGenerator.CLASS_NAME);
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.field($S)))",
                field.name(), columnFetcherClass, DSL, field.name());
        }
        if (field instanceof ChildField.NodeIdField nf && parentTable != null) {
            // Key columns are projected by TypeClassGenerator.$fields; the DataFetcher reads them
            // from the parent Record and encodes the opaque node ID.
            var encoderClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
                NodeIdEncoderClassGenerator.CLASS_NAME);
            var recordClass = ClassName.get("org.jooq", "Record");
            var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
            var body = CodeBlock.builder()
                .add("\n.dataFetcher($S, env -> {\n", field.name())
                .add("    $T r = ($T) env.getSource();\n", recordClass, recordClass)
                .add("    return $T.encode($S", encoderClass, nf.nodeTypeId());
            for (var col : nf.nodeKeyColumns()) {
                body.add(",\n        r.get($T.$L.$L)", tablesClass, parentTable.javaFieldName(), col.javaName());
            }
            body.add(");\n").add("})");
            return body.build();
        }
        return CodeBlock.of("\n.dataFetcher($S, $T::$L)", field.name(), fetchersClass, field.name());
    }

    private static CodeBlock buildPropertyOrRecordFetcherEntry(
            String fieldName, String columnName, ColumnRef column, GraphitronType.ResultType resultType) {
        var columnFetcherClass = ClassName.get(RewriteConfig.outputPackage() + ".rewrite",
            ColumnFetcherClassGenerator.CLASS_NAME);
        if (resultType instanceof GraphitronType.JooqTableRecordType jtrt && column != null && jtrt.table() != null) {
            var tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.$L.$L))",
                fieldName, columnFetcherClass, tablesClass, jtrt.table().javaFieldName(), column.javaName());
        }
        if (resultType instanceof GraphitronType.JooqTableRecordType
                || resultType instanceof GraphitronType.JooqRecordType) {
            return CodeBlock.of("\n.dataFetcher($S, new $T<>($T.field($S)))",
                fieldName, columnFetcherClass, DSL, columnName);
        }
        if (resultType instanceof GraphitronType.JavaRecordType jrt) {
            var backingClass = ClassName.bestGuess(jrt.fqClassName());
            var accessor = toCamelCase(columnName);
            return CodeBlock.of("\n.dataFetcher($S, env -> (($T) env.getSource()).$L())",
                fieldName, backingClass, accessor);
        }
        var prt = (GraphitronType.PojoResultType) resultType;
        if (prt.fqClassName() != null) {
            var backingClass = ClassName.bestGuess(prt.fqClassName());
            var accessorBase = toCamelCase(columnName);
            var getter = "get" + Character.toUpperCase(accessorBase.charAt(0)) + accessorBase.substring(1);
            return CodeBlock.of("\n.dataFetcher($S, env -> (($T) env.getSource()).$L())",
                fieldName, backingClass, getter);
        }
        var propertyDataFetcher = ClassName.get("graphql.schema", "PropertyDataFetcher");
        return CodeBlock.of("\n.dataFetcher($S, $T.fetching($S))",
            fieldName, propertyDataFetcher, columnName);
    }

    private static void collectNestedTypes(GraphitronField field, Map<String, NestedTypeWiring> out) {
        if (!(field instanceof ChildField.NestingField nf)) {
            return;
        }
        var nestedTypeName = nf.returnType().returnTypeName();
        TableRef parentTable = nf.returnType().table();
        out.putIfAbsent(nestedTypeName,
            new NestedTypeWiring(nestedTypeName, nf.nestedFields(), parentTable));
        for (var nested : nf.nestedFields()) {
            collectNestedTypes(nested, out);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
