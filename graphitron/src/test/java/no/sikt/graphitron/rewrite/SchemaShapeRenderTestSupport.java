package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.command.TypeUnitCommand;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.SchemaShapePlanner;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.generators.schema.EnumTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.generators.schema.InputTypeGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans the schema-shape rows a fixture needs and runs the emitter over them, which is the two
 * steps a test does for itself.
 *
 * <p>Here rather than as an overload on each emitter, because an emitter executes commands and
 * does not ask for them: a convenience that had it call {@link SchemaShapePlanner} put the
 * orchestration inside the thing being orchestrated, and made every emitter's main sources depend
 * on the plan layer for a path production never takes. A test may hold both ends; an emitter may
 * not.
 */
public final class SchemaShapeRenderTestSupport {

    private SchemaShapeRenderTestSupport() {}

    /** The enum-form classes, planned then rendered. */
    public static List<TypeSpec> enumTypes(GraphitronSchema schema) {
        return SchemaShapePlanner.produce(schema, "").stream()
            .filter(r -> r.form() == TypeUnitCommand.SchemaShapeForm.ENUM)
            .map(r -> EnumTypeGenerator.generateFor((GraphitronType.EnumType) schema.type(r.typeName())))
            .toList();
    }

    /** The input-form classes, planned then rendered. */
    public static List<TypeSpec> inputTypes(GraphitronSchema schema) {
        return SchemaShapePlanner.produce(schema, "").stream()
            .filter(r -> r.form() == TypeUnitCommand.SchemaShapeForm.INPUT)
            .map(r -> InputTypeGenerator.generateFor((GraphitronType.InputType) schema.type(r.typeName())))
            .toList();
    }

    /** The object, interface and union classes, planned then rendered. */
    public static List<TypeSpec> objectTypes(GraphitronSchema schema, GraphQLSchema assembled,
                                             Map<String, CodeBlock> fetcherBodies) {
        return SchemaShapePlanner.produce(schema, "").stream()
            .filter(r -> r.form() == TypeUnitCommand.SchemaShapeForm.OBJECT
                      || r.form() == TypeUnitCommand.SchemaShapeForm.INTERFACE
                      || r.form() == TypeUnitCommand.SchemaShapeForm.UNION)
            .map(r -> ObjectTypeGenerator.generateFor(schema, assembled, r, fetcherBodies.get(r.typeName())))
            .toList();
    }

    /** The same for a fixture with no {@code registerFetchers} bodies. */
    public static List<TypeSpec> objectTypes(GraphitronSchema schema, GraphQLSchema assembled) {
        return objectTypes(schema, assembled, Map.of());
    }

    /** The registerFetchers bodies, planned then rendered. */
    public static Map<String, CodeBlock> fetcherRegistrations(GraphitronSchema schema,
                                                              String outputPackage) {
        return FetcherRegistrationsEmitter.emit(schema, outputPackage,
            SchemaShapePlanner.produce(schema, outputPackage));
    }

    /** The schema class, with the fetcher-carrying names a fixture chooses rather than plans. */
    public static List<TypeSpec> schemaClass(GraphitronSchema schema, GraphQLSchema assembled,
                                             Set<String> typesWithFetchers, String outputPackage,
                                             boolean federationLink) {
        var rows = SchemaShapePlanner.produce(schema, outputPackage).stream()
            .map(r -> new TypeUnitCommand.SchemaShapeUnit(
                r.typeName(), r.unit(), r.form(), typesWithFetchers.contains(r.typeName())))
            .toList();
        return GraphitronSchemaClassGenerator.generate(schema, assembled, rows, outputPackage, federationLink);
    }

    /** The schema class for a fixture with no fetcher-carrying names and no package prefix. */
    public static List<TypeSpec> schemaClass(GraphitronSchema schema, GraphQLSchema assembled) {
        return schemaClass(schema, assembled, Set.of(), "", false);
    }

    /** The same with a chosen fetcher set and package, federation off. */
    public static List<TypeSpec> schemaClass(GraphitronSchema schema, GraphQLSchema assembled,
                                             Set<String> typesWithFetchers, String outputPackage) {
        return schemaClass(schema, assembled, typesWithFetchers, outputPackage, false);
    }
}
