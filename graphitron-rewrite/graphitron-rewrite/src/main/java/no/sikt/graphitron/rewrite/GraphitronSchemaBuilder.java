package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLObjectType;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;

import no.sikt.graphitron.rewrite.model.GraphitronField;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.*;

/**
 * Builds a {@link GraphitronSchema} from a {@link TypeDefinitionRegistry} by classifying every
 * named type into the sealed {@link no.sikt.graphitron.rewrite.model.GraphitronType} hierarchy
 * and every field into the sealed {@link GraphitronField} hierarchy.
 *
 * <p>This is the directive-reading boundary: the only place in the pipeline that reads schema
 * directives. Downstream code works exclusively with the produced model values.
 *
 * <p>The Maven plugin calls {@link #build(TypeDefinitionRegistry, RewriteContext)} before running
 * {@link GraphitronSchemaValidator#validate(GraphitronSchema)}.
 */
public class GraphitronSchemaBuilder {

    /**
     * Pairs the classified {@link GraphitronSchema} with the raw {@link GraphQLSchema} the
     * classifier built internally. The Commit B emitters read raw type structure (argument
     * default values, nested list/non-null wrapping, directive applications) off the assembled
     * schema and don't need the reclassification to carry it all, so we just surface both
     * together. Production callers use {@link #buildBundle(TypeDefinitionRegistry, RewriteContext)};
     * tests that only need the classified model continue to call
     * {@link #build(TypeDefinitionRegistry, RewriteContext)}.
     */
    public record Bundle(GraphitronSchema model, graphql.schema.GraphQLSchema assembled) {}

    /**
     * Classifies all types and fields in {@code registry} and returns the resulting
     * {@link GraphitronSchema}. The registry must already include the Graphitron directive
     * definitions.
     */
    public static GraphitronSchema build(TypeDefinitionRegistry registry, RewriteContext ctx) {
        return buildBundle(registry, ctx).model();
    }

    /**
     * Classifies {@code registry} and returns both the classified model and the assembled
     * {@link GraphQLSchema}. See {@link Bundle}.
     */
    public static Bundle buildBundle(TypeDefinitionRegistry registry, RewriteContext ctx) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring ->
            registry.scalars().forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(EchoingWiringFactory.fakeScalar(name));
                }
            })
        );
        var assembled = new SchemaGenerator().makeExecutableSchema(registry, runtimeWiring);
        var bctx = new BuildContext(assembled, new JooqCatalog(ctx.jooqPackage()), ctx);
        var svc = new ServiceCatalog(bctx);
        bctx.svc = svc;
        var typeBuilder = new TypeBuilder(bctx, svc);
        var fieldBuilder = new FieldBuilder(bctx, svc);
        return new Bundle(buildSchema(bctx, typeBuilder, fieldBuilder), assembled);
    }

    private static GraphitronSchema buildSchema(BuildContext ctx, TypeBuilder typeBuilder, FieldBuilder fieldBuilder) {
        validateDirectiveSchema(ctx);
        ctx.types = typeBuilder.buildTypes();
        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        ctx.schema.getAllTypesAsList().stream()
            .filter(t -> t instanceof GraphQLObjectType && !t.getName().startsWith("__"))
            .map(t -> (GraphQLObjectType) t)
            .forEach(objType -> {
                var parentType = ctx.types.get(objType.getName());
                if (parentType == null) return;
                objType.getFieldDefinitions().forEach(fieldDef ->
                    fields.put(
                        FieldCoordinates.coordinates(objType.getName(), fieldDef.getName()),
                        fieldBuilder.classifyField(fieldDef, objType.getName(), parentType)));
            });
        return new GraphitronSchema(ctx.types, Collections.unmodifiableMap(fields), ctx.warnings());
    }

    private static void validateDirectiveSchema(BuildContext ctx) {
        assertDirective(ctx, DIR_TABLE, ARG_NAME);
        assertDirective(ctx, DIR_RECORD);
        assertDirective(ctx, DIR_DISCRIMINATE, ARG_ON);
        assertDirective(ctx, DIR_DISCRIMINATOR, ARG_VALUE);
        assertDirective(ctx, DIR_NODE, ARG_TYPE_ID, ARG_KEY_COLUMNS);
        assertDirective(ctx, DIR_NOT_GENERATED);
        assertDirective(ctx, DIR_MULTITABLE_REFERENCE);
        assertDirective(ctx, DIR_NODE_ID, ARG_TYPE_NAME);
        assertDirective(ctx, DIR_FIELD, ARG_NAME, ARG_JAVA_NAME);
        assertDirective(ctx, DIR_REFERENCE, ARG_PATH);
        assertDirective(ctx, DIR_ERROR, ARG_HANDLERS);
        assertDirective(ctx, DIR_TABLE_METHOD);
        assertDirective(ctx, DIR_DEFAULT_ORDER);
        assertDirective(ctx, DIR_SPLIT_QUERY);
        assertDirective(ctx, DIR_SERVICE);
        assertDirective(ctx, DIR_EXTERNAL_FIELD);
        assertDirective(ctx, DIR_LOOKUP_KEY);
        assertDirective(ctx, DIR_ORDER_BY);
        assertDirective(ctx, DIR_CONDITION);
        assertDirective(ctx, DIR_MUTATION, ARG_TYPE_NAME);
        assertDirective(ctx, DIR_AS_CONNECTION, ARG_DEFAULT_FIRST_VALUE, ARG_CONNECTION_NAME);
    }

    private static void assertDirective(BuildContext ctx, String name, String... args) {
        var def = ctx.schema.getDirective(name);
        if (def == null) {
            throw new IllegalStateException("Expected directive @" + name + " in schema but it was not found.");
        }
        var argNames = def.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toSet());
        for (var arg : args) {
            if (!argNames.contains(arg)) {
                throw new IllegalStateException(
                    "Expected argument '" + arg + "' on directive @" + name + " but it was not found.");
            }
        }
    }
}
