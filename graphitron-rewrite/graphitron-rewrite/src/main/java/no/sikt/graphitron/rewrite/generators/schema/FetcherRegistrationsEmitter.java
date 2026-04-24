package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.FetcherEmitter;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the body of {@code registerFetchers(GraphQLCodeRegistry.Builder codeRegistry)} for every
 * GraphQL object type that owns data fetchers. One entry per type; the type name is the map key
 * and the value is the method body that attaches each fetcher to the shared code registry
 * through {@link graphql.schema.FieldCoordinates}.
 *
 * <p>Covers five categories inherited from the deleted {@code WiringClassGenerator}:
 * <ol>
 *   <li>Regular object types. {@code codeRegistry.dataFetcher(FieldCoordinates.coordinates("Film", "title"), FilmFetchers::title)}.</li>
 *   <li>Nested object types with {@link BatchKeyField} leaves — mixes fetcher references with
 *       inline {@code ColumnFetcher} bindings.</li>
 *   <li>Nested object types without {@link BatchKeyField} leaves — inline bindings only.</li>
 *   <li>Connection types — binds {@code edges}, {@code nodes}, {@code pageInfo} to
 *       {@link ConnectionHelperClassGenerator}.</li>
 *   <li>Edge types — binds {@code node}, {@code cursor} to {@link ConnectionHelperClassGenerator}.</li>
 * </ol>
 *
 * <p>The fetcher-value expressions come from {@link FetcherEmitter#dataFetcherValue} unchanged
 * from the former wiring path. Output map ordering is alphabetical by type name for stable
 * generated-source diffs; callers pass {@code keySet()} to
 * {@link GraphitronSchemaClassGenerator} so the emitted {@code GraphitronSchema.build()} invokes
 * {@code registerFetchers} on every type that has one.
 */
public final class FetcherRegistrationsEmitter {

    private static final ClassName FIELD_COORDS = ClassName.get("graphql.schema", "FieldCoordinates");

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

    private FetcherRegistrationsEmitter() {}

    public static Map<String, CodeBlock> emit(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        String fetchersPackage = outputPackage + ".fetchers";
        String utilPackage     = outputPackage + ".util";

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

        var nestedTypeMap = new LinkedHashMap<String, NestedTypeWiring>();
        schema.fields().values().forEach(field -> collectNestedTypes(field, nestedTypeMap));

        var result = new TreeMap<String, CodeBlock>();

        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      || e.getValue() instanceof GraphitronType.ResultType)
            .forEach(e -> result.put(e.getKey(), typeBody(schema, e.getKey(), fetchersPackage, outputPackage, jooqPackage)));

        nestedTypeMap.values().forEach(ntw ->
            result.put(ntw.nestedTypeName(), nestedBody(ntw, fetchersPackage, outputPackage, jooqPackage)));

        connectionTypeMap.forEach((connName, edgeName) -> {
            result.put(connName, connectionBody(connName, utilPackage));
            result.put(edgeName,  edgeBody(edgeName, utilPackage));
        });

        return result;
    }

    private static CodeBlock typeBody(GraphitronSchema schema, String typeName,
            String fetchersPackage, String outputPackage, String jooqPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.NotGeneratedField))
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        ClassName fetchersClass = ClassName.get(fetchersPackage, typeName + "Fetchers");
        return buildBody(typeName, fields, fetchersClass, parentTable, resultType, outputPackage, jooqPackage);
    }

    private static CodeBlock nestedBody(NestedTypeWiring ntw, String fetchersPackage, String outputPackage, String jooqPackage) {
        ClassName nestedFetchersClass = ntw.fields().stream().anyMatch(f -> f instanceof BatchKeyField)
            ? ClassName.get(fetchersPackage, ntw.nestedTypeName() + "Fetchers") : null;

        var body = CodeBlock.builder();
        if (ntw.fields().isEmpty()) {
            return body.build();
        }
        body.add("codeRegistry").indent();
        for (var field : ntw.fields()) {
            if (nestedFetchersClass != null && field instanceof BatchKeyField) {
                body.add("\n.dataFetcher($T.coordinates($S, $S), $T::$L)",
                    FIELD_COORDS, ntw.nestedTypeName(), field.name(),
                    nestedFetchersClass, field.name());
            } else {
                body.add(registrationEntry(ntw.nestedTypeName(), field,
                    null, ntw.representativeParentTable(), null, outputPackage, jooqPackage));
            }
        }
        body.add(";\n").unindent();
        return body.build();
    }

    private static CodeBlock connectionBody(String connectionTypeName, String utilPackage) {
        var helper = ClassName.get(utilPackage, ConnectionHelperClassGenerator.CLASS_NAME);
        return CodeBlock.builder()
            .add("codeRegistry")
            .indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edges)",    FIELD_COORDS, connectionTypeName, "edges",    helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::nodes)",    FIELD_COORDS, connectionTypeName, "nodes",    helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::pageInfo);\n", FIELD_COORDS, connectionTypeName, "pageInfo", helper)
            .unindent()
            .build();
    }

    private static CodeBlock edgeBody(String edgeTypeName, String utilPackage) {
        var helper = ClassName.get(utilPackage, ConnectionHelperClassGenerator.CLASS_NAME);
        return CodeBlock.builder()
            .add("codeRegistry")
            .indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edgeNode)",    FIELD_COORDS, edgeTypeName, "node",   helper)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edgeCursor);\n", FIELD_COORDS, edgeTypeName, "cursor", helper)
            .unindent()
            .build();
    }

    private static CodeBlock buildBody(String typeName, List<GraphitronField> fields,
            ClassName fetchersClass, TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, String jooqPackage) {
        if (fields.isEmpty()) {
            return CodeBlock.builder().build();
        }
        var body = CodeBlock.builder().add("codeRegistry").indent();
        for (var field : fields) {
            body.add(registrationEntry(typeName, field, fetchersClass, parentTable, resultType, outputPackage, jooqPackage));
        }
        body.add(";\n").unindent();
        return body.build();
    }

    private static CodeBlock registrationEntry(String typeName, GraphitronField field,
            ClassName fetchersClass, TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, String jooqPackage) {
        return CodeBlock.builder()
            .add("\n.dataFetcher($T.coordinates($S, $S), ", FIELD_COORDS, typeName, field.name())
            .add(FetcherEmitter.dataFetcherValue(field, fetchersClass, parentTable, resultType, outputPackage, jooqPackage))
            .add(")")
            .build();
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
