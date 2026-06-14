package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.FetcherEmitter;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the body of {@code registerFetchers(GraphQLCodeRegistry.Builder codeRegistry)} for every
 * GraphQL object type that owns data fetchers. One entry per type; the type name is the map key
 * and the value is the method body that attaches each fetcher to the shared code registry
 * through {@link graphql.schema.FieldCoordinates}.
 *
 * <p>Covers the categories inherited from the deleted {@code WiringClassGenerator}; every field
 * is now wired through its owning {@code <Type>Fetchers} class (R303):
 * <ol>
 *   <li>Regular object types. {@code codeRegistry.dataFetcher(FieldCoordinates.coordinates("Film", "title"), FilmFetchers::title)}.</li>
 *   <li>Nested object types that own any fetcher — every field references into the type's own
 *       {@code <Type>Fetchers} class, as a method reference or a {@code LightFetcher}-wrapped read.</li>
 *   <li>Connection types — binds {@code edges}, {@code nodes}, {@code pageInfo}, and (when the
 *       schema declares it) {@code totalCount} to the per-connection {@code <Conn>Fetchers}
 *       delegates, which forward to {@link ConnectionHelperClassGenerator}.</li>
 *   <li>Edge types — binds {@code node}, {@code cursor} to the per-edge {@code <Edge>Fetchers}
 *       delegates, which forward to {@link ConnectionHelperClassGenerator}.</li>
 * </ol>
 *
 * <p>The fetcher-value expressions come from {@link FetcherEmitter#bind}'s
 * {@code registrationValue()}. Output map ordering is alphabetical by type name for stable
 * generated-source diffs; callers pass {@code keySet()} to
 * {@link GraphitronSchemaClassGenerator} so the emitted {@code GraphitronSchema.build()} invokes
 * {@code registerFetchers} on every type that has one.
 */
public final class FetcherRegistrationsEmitter {

    private static final ClassName FIELD_COORDS = ClassName.get("graphql.schema", "FieldCoordinates");

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

    public static Map<String, CodeBlock> emit(GraphitronSchema schema, String outputPackage) {
        String fetchersPackage = outputPackage + ".fetchers";

        var nestedTypeMap = new LinkedHashMap<String, NestedTypeWiring>();
        schema.fields().values().forEach(field -> collectNestedTypes(field, nestedTypeMap));

        var result = new TreeMap<String, CodeBlock>();

        schema.types().entrySet().stream()
            .filter(e -> e.getValue() instanceof GraphitronType.TableType
                      || e.getValue() instanceof GraphitronType.NodeType
                      || e.getValue() instanceof GraphitronType.RootType
                      // Single-record DML carriers bind to a JooqTableRecordType and hold one
                      // SingleRecordTableField data field that needs a wired fetcher entry. They
                      // fall through this filter via the ResultType arm — no NestingType
                      // widening required.
                      || e.getValue() instanceof GraphitronType.ResultType)
            .forEach(e -> typeBody(schema, e.getKey(), fetchersPackage, outputPackage)
                .ifPresent(body -> result.put(e.getKey(), body)));

        nestedTypeMap.values().forEach(ntw ->
            nestedBody(ntw, fetchersPackage, outputPackage)
                .ifPresent(body -> result.put(ntw.nestedTypeName(), body)));

        // Connection / Edge wiring is driven by the classifier's first-class type entries
        // (populated for both directive-driven and structural carriers). Iterate the type map
        // directly — no need for an intermediate (name, edgeName) projection because connectionBody
        // reads the full ConnectionType to inspect schemaType().getFieldDefinition("totalCount").
        schema.types().values().forEach(type -> {
            if (type instanceof GraphitronType.ConnectionType ct) {
                result.put(ct.name(),         connectionBody(ct, fetchersPackage));
                result.put(ct.edgeTypeName(), edgeBody(ct.edgeTypeName(), fetchersPackage));
            }
        });

        return result;
    }

    private static Optional<CodeBlock> typeBody(GraphitronSchema schema, String typeName,
            String fetchersPackage, String outputPackage) {
        var type = schema.type(typeName);
        var fields = schema.fieldsOf(typeName).stream()
            .filter(f -> !(f instanceof GraphitronField.UnclassifiedField))
            .sorted(Comparator.comparing(GraphitronField::name))
            .toList();
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        TableRef parentTable = type instanceof GraphitronType.TableBackedType tbt ? tbt.table() : null;
        GraphitronType.ResultType resultType = type instanceof GraphitronType.ResultType rt ? rt : null;
        ClassName fetchersClass = ClassName.get(fetchersPackage, typeName + "Fetchers");
        return Optional.of(buildBody(typeName, fields, fetchersClass, parentTable, resultType, outputPackage));
    }

    private static Optional<CodeBlock> nestedBody(NestedTypeWiring ntw, String fetchersPackage, String outputPackage) {
        // R303: every nested object type that owns a fetcher gets its own <Type>Fetchers class, and
        // each field's read (reified or method-backed) references into it. The gate is shared with
        // TypeFetcherGenerator.collectNestedFetcherClasses (which emits the class) via
        // FetcherEmitter.nestedTypeOwnsFetchers so the two sites cannot drift.
        if (!FetcherEmitter.nestedTypeOwnsFetchers(ntw.fields())) {
            return Optional.empty();
        }
        ClassName nestedFetchersClass = ClassName.get(fetchersPackage, ntw.nestedTypeName() + "Fetchers");

        boolean sourceIsOutcome = FetcherEmitter.hasWrapperArmErrors(ntw.fields());
        var body = CodeBlock.builder();
        body.add("codeRegistry").indent();
        for (var field : ntw.fields()) {
            body.add(registrationEntry(ntw.nestedTypeName(), field,
                nestedFetchersClass, ntw.representativeParentTable(), null, outputPackage, sourceIsOutcome));
        }
        body.add(";\n").unindent();
        return Optional.of(body.build());
    }

    private static CodeBlock connectionBody(GraphitronType.ConnectionType connectionType, String fetchersPackage) {
        var fetchers = ClassName.get(fetchersPackage, connectionType.name() + "Fetchers");
        var connName = connectionType.name();
        var body = CodeBlock.builder()
            .add("codeRegistry")
            .indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::edges)",    FIELD_COORDS, connName, "edges",    fetchers)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::nodes)",    FIELD_COORDS, connName, "nodes",    fetchers)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::pageInfo)", FIELD_COORDS, connName, "pageInfo", fetchers);
        // totalCount is always present on synthesised connection types; on structural connections
        // it is wired only when the SDL author declared it. GraphitronSchemaValidator already
        // rejected non-Int totalCount, so a present field is guaranteed to be Int / Int!. The same
        // gate drives ConnectionFetcherClassGenerator so the reference and the method agree.
        var totalCount = connectionType.schemaType().getFieldDefinition("totalCount");
        if (totalCount != null) {
            body.add("\n.dataFetcher($T.coordinates($S, $S), $T::totalCount)", FIELD_COORDS, connName, "totalCount", fetchers);
        }
        body.add(";\n").unindent();
        return body.build();
    }

    private static CodeBlock edgeBody(String edgeTypeName, String fetchersPackage) {
        var fetchers = ClassName.get(fetchersPackage, edgeTypeName + "Fetchers");
        return CodeBlock.builder()
            .add("codeRegistry")
            .indent()
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::node)",     FIELD_COORDS, edgeTypeName, "node",   fetchers)
            .add("\n.dataFetcher($T.coordinates($S, $S), $T::cursor);\n", FIELD_COORDS, edgeTypeName, "cursor", fetchers)
            .unindent()
            .build();
    }

    private static CodeBlock buildBody(String typeName, List<GraphitronField> fields,
            ClassName fetchersClass, TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage) {
        boolean sourceIsOutcome = FetcherEmitter.hasWrapperArmErrors(fields);
        var body = CodeBlock.builder().add("codeRegistry").indent();
        for (var field : fields) {
            body.add(registrationEntry(typeName, field, fetchersClass, parentTable, resultType,
                outputPackage, sourceIsOutcome));
        }
        body.add(";\n").unindent();
        return body.build();
    }

    private static CodeBlock registrationEntry(String typeName, GraphitronField field,
            ClassName fetchersClass, TableRef parentTable, GraphitronType.ResultType resultType,
            String outputPackage, boolean sourceIsOutcome) {
        return CodeBlock.builder()
            .add("\n.dataFetcher($T.coordinates($S, $S), ", FIELD_COORDS, typeName, field.name())
            .add(FetcherEmitter.bind(field, fetchersClass, parentTable, resultType,
                outputPackage, sourceIsOutcome).registrationValue())
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

}
