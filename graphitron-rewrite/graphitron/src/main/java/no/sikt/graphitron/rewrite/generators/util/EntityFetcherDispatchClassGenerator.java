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
    public static final String RESOLVE_TYPE_METHOD = "resolveType";
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

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
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
            .addMethod(buildFetchEntitiesMethod(entities))
            .addMethod(buildResolveTypeMethod())
            .addMethod(buildContextHelper(graphitronContext));

        for (var entity : entities) {
            spec.addMethod(buildHandleMethod(entity, outputPackage, jooqPackage, nodeIdEncoder));
            for (int i = 0; i < entity.alternatives().size(); i++) {
                var alt = entity.alternatives().get(i);
                if (!alt.resolvable()) continue;
                spec.addMethod(buildSelectMethod(entity, alt, i, outputPackage, jooqPackage));
            }
        }

        return List.of(spec.build());
    }

    // ----- Method emitters (defined in companion methods below) -----

    private static MethodSpec buildFetchEntitiesMethod(List<EntityResolution> entities) {
        // Implemented in fetchEntities-method-body Edit
        return MethodSpec.methodBuilder(FETCH_ENTITIES_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(CF, ParameterizedTypeName.get(LIST, ClassName.get(Object.class))))
            .addParameter(ENV, "env")
            .addCode(fetchEntitiesBody(entities))
            .build();
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

    // ----- Body emitters (filled in via Edit) -----

    private static CodeBlock fetchEntitiesBody(List<EntityResolution> entities) {
        var b = CodeBlock.builder();
        b.addStatement("$T<$T<String, Object>> reps = env.getArgument($S)",
            LIST, MAP, "representations");
        b.addStatement("if (reps == null || reps.isEmpty()) return $T.completedFuture($T.of())",
            CF, LIST);
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
        b.addStatement("return $T.completedFuture($T.asList(result))", CF, ARRAYS);
        return b.build();
    }

    // Per-type handle methods and per-alternative select methods are emitted by the helpers
    // below. Implementations are filled in by subsequent Edit operations to keep this file
    // composable in small chunks.
    private static MethodSpec buildHandleMethod(
        EntityResolution entity, String outputPackage, String jooqPackage, ClassName nodeIdEncoder
    ) {
        return new HandleMethodEmitter(entity, outputPackage, jooqPackage, nodeIdEncoder).build();
    }

    private static MethodSpec buildSelectMethod(
        EntityResolution entity, KeyAlternative alt, int altIndex,
        String outputPackage, String jooqPackage
    ) {
        return new SelectMethodEmitter(entity, alt, altIndex, outputPackage, jooqPackage).build();
    }

    // ----- Inner emitter classes (lightweight; minimal state) -----

    private static final class HandleMethodEmitter {
        final EntityResolution entity;
        final String outputPackage;
        final String jooqPackage;
        final ClassName nodeIdEncoder;

        HandleMethodEmitter(EntityResolution entity, String outputPackage, String jooqPackage, ClassName nodeIdEncoder) {
            this.entity = entity;
            this.outputPackage = outputPackage;
            this.jooqPackage = jooqPackage;
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
        final String jooqPackage;

        SelectMethodEmitter(EntityResolution entity, KeyAlternative alt, int altIndex,
                            String outputPackage, String jooqPackage) {
            this.entity = entity;
            this.alt = alt;
            this.altIndex = altIndex;
            this.outputPackage = outputPackage;
            this.jooqPackage = jooqPackage;
        }

        MethodSpec build() {
            return SelectMethodBody.buildMethod(entity, alt, altIndex, outputPackage, jooqPackage);
        }
    }
}
