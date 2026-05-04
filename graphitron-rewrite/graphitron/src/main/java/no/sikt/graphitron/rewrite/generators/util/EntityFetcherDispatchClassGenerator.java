package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.EntityResolution;
import no.sikt.graphitron.rewrite.model.KeyAlternative;
import no.sikt.graphitron.rewrite.model.KeyAlternative.KeyShape;

import javax.lang.model.element.Modifier;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the {@code EntityFetcherDispatch} class — the runtime dispatcher for the
 * Apollo Federation {@code Query._entities(representations:)} field.
 *
 * <p>Emitted only when the schema has at least one classified
 * {@link EntityResolution} entry. The generated class:
 * <ol>
 *   <li>{@code fetchEntities(env)} — reads {@code representations}, dispatches per
 *       {@code __typename} to a per-type handler, returns
 *       {@code CompletableFuture<List<Object>>} preserving exact rep order.</li>
 *   <li>{@code handle<TypeName>(reps, indices, env, result)} — for each rep, selects the
 *       most-specific resolvable {@link KeyAlternative}, decodes the rep into a column-value
 *       row (DIRECT: copy values; NODE_ID: {@code NodeIdEncoder.decodeValues}), and groups
 *       by {@code (alternative, tenantId)} for batching.</li>
 *   <li>{@code select<TypeName>Alt<N>(bindings, env, dsl, result)} — issues one SELECT per
 *       group via a {@code VALUES (idx, col1, col2, ...)} derived table joined to the type's
 *       jOOQ table, projecting {@code <TypeName>.$fields(...)} plus the literal
 *       {@code __typename} column. Result rows scatter back to original positions via the
 *       {@code idx} column.</li>
 * </ol>
 *
 * <p>Tenant scoping mirrors {@code QueryNodeFetcher.dispatchNodes}: each rep gets a per-rep
 * DFE (with {@code arguments(rep)}) so the consumer's {@code getTenantId(repEnv)} resolves
 * against the individual rep, not the outer representations list. Reps from different
 * tenants land in separate groups; partitioning before SQL keeps tenant isolation intact.
 *
 * <p>{@code resolveType} is exposed as a separate static helper that reads the synthetic
 * {@code __typename} column projected on every entity row. Consumers who override
 * {@code fetchEntities} and return a non-{@link org.jooq.Record} shape get {@code null}
 * back (federation surfaces its own resolution-failure error); enriching the type-resolution
 * path is a follow-up.
 */
public final class EntityFetcherDispatchClassGenerator {

    public static final String CLASS_NAME = "EntityFetcherDispatch";
    public static final String FETCH_ENTITIES_METHOD = "fetchEntities";
    public static final String RESOLVE_BY_REPS_METHOD = "resolveByReps";
    public static final String RESOLVE_TYPE_METHOD = "resolveType";
    public static final String TYPENAME_FOR_TYPE_ID_METHOD = "typenameForTypeId";
    public static final String TYPENAME_COLUMN = "__typename";

    private static final ClassName ENV          = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName ENV_IMPL     = ClassName.get("graphql.schema", "DataFetchingEnvironmentImpl");
    private static final ClassName OBJ_TYPE     = ClassName.get("graphql.schema", "GraphQLObjectType");
    private static final ClassName LIST         = ClassName.get("java.util", "List");
    private static final ClassName MAP          = ClassName.get("java.util", "Map");
    private static final ClassName ARRAYS       = ClassName.get("java.util", "Arrays");
    private static final ClassName ARRAY_LIST   = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName CF           = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName RECORD       = ClassName.get("org.jooq", "Record");
    private static final ClassName TABLE        = ClassName.get("org.jooq", "Table");
    private static final ClassName FIELD        = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL_CONTEXT  = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL          = ClassName.get("org.jooq.impl", "DSL");

    private EntityFetcherDispatchClassGenerator() {}

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
        var entities = schema.entitiesByType().values().stream()
            .sorted(Comparator.comparing(EntityResolution::typeName))
            .toList();
        if (entities.isEmpty()) {
            return List.of();
        }

        var graphitronContext = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        var nodeIdEncoder = ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);

        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Runtime dispatcher for the Apollo Federation\n"
                + "{@code Query._entities(representations:)} field. See\n"
                + "{@link EntityFetcherDispatchClassGenerator} for emission semantics.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(buildFetchEntitiesMethod())
            .addMethod(buildResolveByRepsMethod(entities))
            .addMethod(buildResolveTypeMethod())
            .addMethod(buildTypenameForTypeIdMethod(entities))
            .addMethod(buildContextHelper(graphitronContext));

        for (var entity : entities) {
            spec.addMethod(buildHandleMethod(entity, outputPackage, nodeIdEncoder));
            for (int i = 0; i < entity.alternatives().size(); i++) {
                var alt = entity.alternatives().get(i);
                if (!alt.resolvable()) continue;
                spec.addMethod(buildSelectMethod(entity, alt, i, outputPackage));
            }
        }

        return List.of(spec.build());
    }

    // ----- Method emitters (defined in companion methods below) -----

    private static MethodSpec buildFetchEntitiesMethod() {
        var b = CodeBlock.builder();
        b.addStatement("$T<$T<String, Object>> reps = env.getArgument($S)",
            LIST, MAP, "representations");
        b.addStatement("if (reps == null || reps.isEmpty()) return $T.completedFuture($T.of())",
            CF, LIST);
        b.addStatement("Object[] result = $L(reps, env)", RESOLVE_BY_REPS_METHOD);
        b.addStatement("return $T.completedFuture($T.asList(result))", CF, ARRAYS);
        return MethodSpec.methodBuilder(FETCH_ENTITIES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(CF, ParameterizedTypeName.get(LIST, ClassName.get(Object.class))))
            .addParameter(ENV, "env")
            .addCode(b.build())
            .build();
    }

    private static MethodSpec buildResolveByRepsMethod(List<EntityResolution> entities) {
        var listOfMap = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)));
        return MethodSpec.methodBuilder(RESOLVE_BY_REPS_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(no.sikt.graphitron.javapoet.ArrayTypeName.of(ClassName.get(Object.class)))
            .addParameter(listOfMap, "reps")
            .addParameter(ENV, "env")
            .addJavadoc("Synchronous dispatch core. Takes a representations list directly\n"
                + "(not via {@code DataFetchingEnvironment.getArgument}) so callers like\n"
                + "{@code QueryNodeFetcher.rowsNodes} can synthesise reps from a list of\n"
                + "Relay node ids and reuse the same SELECT path.\n"
                + "Returns an {@code Object[]} sized to {@code reps.size()}; entries are\n"
                + "jOOQ {@code Record}s for resolved reps or {@code null} for unresolved.\n")
            .addCode(resolveByRepsBody(entities))
            .build();
    }

    private static MethodSpec buildTypenameForTypeIdMethod(List<EntityResolution> entities) {
        // Static initialised map, keyed by NodeType.typeId() (which differs from the typename
        // when the consumer set @node(typeId: ...)). Used by QueryNodeFetcher.rowsNodes after
        // peekTypeId to recover the GraphQL typename for synthesising reps.
        var b = CodeBlock.builder();
        b.add("$T<String, String> $L = $T.ofEntries(",
            MAP, "MAP", MAP);
        boolean first = true;
        for (var entity : entities) {
            if (entity.nodeTypeId() == null) continue; // only NodeType entities have a typeId
            if (!first) b.add(",\n        ");
            else b.add("\n        ");
            first = false;
            b.add("$T.entry($S, $S)", MAP, entity.nodeTypeId(), entity.typeName());
        }
        b.add(");\n");
        var fieldInit = b.build();

        var holder = MethodSpec.methodBuilder(TYPENAME_FOR_TYPE_ID_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get(String.class))
            .addParameter(String.class, "typeId")
            .addJavadoc("Returns the GraphQL typename for a given NodeId typeId prefix, or\n"
                + "{@code null} when no {@code @node} type matches. Used by\n"
                + "{@code QueryNodeFetcher.rowsNodes} to synthesise representations from\n"
                + "Relay ids before dispatching to {@link #" + RESOLVE_BY_REPS_METHOD + "}.\n")
            .addStatement("if (typeId == null) return null")
            .addCode(fieldInit)
            .addStatement("return MAP.get(typeId)")
            .build();
        return holder;
    }

    private static CodeBlock resolveByRepsBody(List<EntityResolution> entities) {
        var b = CodeBlock.builder();
        b.addStatement("Object[] result = new Object[reps.size()]");
        b.addStatement("$T<String, $T> byType = new $T<>()",
            MAP, ParameterizedTypeName.get(LIST, ClassName.get(Integer.class)), LINKED_HASH_MAP);
        b.beginControlFlow("for (int i = 0; i < reps.size(); i++)");
        b.addStatement("$T<String, Object> rep = reps.get(i)", MAP);
        b.addStatement("if (rep == null) continue");
        b.addStatement("Object tn = rep.get($S)", TYPENAME_COLUMN);
        b.addStatement("if (!(tn instanceof String typeName)) continue");
        b.addStatement("byType.computeIfAbsent(typeName, k -> new $T<>()).add(i)", ARRAY_LIST);
        b.endControlFlow();
        for (var entity : entities) {
            b.beginControlFlow("if (byType.containsKey($S))", entity.typeName());
            b.addStatement("handle$L(reps, byType.get($S), env, result)",
                entity.typeName(), entity.typeName());
            b.endControlFlow();
        }
        b.addStatement("return result");
        return b.build();
    }

    private static MethodSpec buildResolveTypeMethod() {
        return MethodSpec.methodBuilder(RESOLVE_TYPE_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(OBJ_TYPE)
            .addParameter(ClassName.get("graphql", "TypeResolutionEnvironment"), "env")
            .addStatement("Object o = env.getObject()")
            .addStatement("if (!(o instanceof $T r)) return null", RECORD)
            .addStatement("Object tn = r.get($S)", TYPENAME_COLUMN)
            .addStatement("if (!(tn instanceof String typeName)) return null")
            .addStatement("return env.getSchema().getObjectType(typeName)")
            .build();
    }

    private static MethodSpec buildContextHelper(ClassName graphitronContext) {
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(graphitronContext)
            .addParameter(ENV, "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", graphitronContext)
            .build();
    }

    // Per-type handle methods and per-alternative select methods are emitted by the helpers
    // below. Implementations are filled in by subsequent Edit operations to keep this file
    // composable in small chunks.
    private static MethodSpec buildHandleMethod(
        EntityResolution entity, String outputPackage, ClassName nodeIdEncoder
    ) {
        return new HandleMethodEmitter(entity, outputPackage, nodeIdEncoder).build();
    }

    private static MethodSpec buildSelectMethod(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage
    ) {
        return new SelectMethodEmitter(entity, alt, altIndex, outputPackage).build();
    }

    // ----- Inner emitter classes (lightweight; minimal state) -----

    private static final class HandleMethodEmitter {
        final EntityResolution entity;
        final String outputPackage;
        final ClassName nodeIdEncoder;

        HandleMethodEmitter(EntityResolution entity, String outputPackage, ClassName nodeIdEncoder) {
            this.entity = entity;
            this.outputPackage = outputPackage;
            this.nodeIdEncoder = nodeIdEncoder;
        }

        MethodSpec build() {
            var listOfInteger = ParameterizedTypeName.get(LIST, ClassName.get(Integer.class));
            var listOfMap = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(Object.class)));
            return MethodSpec.methodBuilder("handle" + entity.typeName())
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(listOfMap, "reps")
                .addParameter(listOfInteger, "indices")
                .addParameter(ENV, "env")
                .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
                .addCode(body())
                .build();
        }

        CodeBlock body() {
            return HandleMethodBody.emit(entity, nodeIdEncoder);
        }
    }

    private static final class SelectMethodEmitter {
        final EntityResolution entity;
        final KeyAlternative alt;
        final int altIndex;
        final String outputPackage;

        SelectMethodEmitter(EntityResolution entity, KeyAlternative alt, int altIndex,
                            String outputPackage) {
            this.entity = entity;
            this.alt = alt;
            this.altIndex = altIndex;
            this.outputPackage = outputPackage;
        }

        MethodSpec build() {
            return SelectMethodBody.buildMethod(entity, alt, altIndex, outputPackage);
        }
    }
}
