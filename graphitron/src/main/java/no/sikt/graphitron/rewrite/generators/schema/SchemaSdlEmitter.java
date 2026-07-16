package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.idl.DirectiveInfo;
import graphql.schema.idl.SchemaPrinter;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;
import no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders the assembled {@link GraphQLSchema} to a fixed {@code schema.graphqls}
 * file under {@code <resourcesRoot>/<outputPackage as path>/}.
 *
 * <p>Both arms print through graphql-java's {@link SchemaPrinter} with
 * {@link SchemaDirectiveRegistry#isSurvivor(String)} as the directive filter, so the
 * file carries the same directive surface the generated {@code GraphitronSchema.build}
 * produces at consumer load time: generator-only
 * directive definitions and applications are consumed at generate time and never
 * printed; survivors (federation directives, {@code @deprecated}, consumer-declared
 * custom directives) keep printing. {@link #includeSchemaElement} additionally drops
 * Graphitron support types that classification did not retain (absent from
 * {@link GraphitronSchema#types()}), so the one retention decision made at classify
 * time drives both the runtime registration and this print seam.
 *
 * <p>The federation arm runs {@link Federation#transform} on the assembled schema
 * before printing, mirroring the {@code Federation.transform(...)} call the generated
 * {@code GraphitronSchema.build} makes at consumer load time, and mirrors the option
 * shape of {@code ServiceSDLPrinter.generateServiceSDLV2} (including its
 * {@code includeSchemaElement} filter dropping graphql-spec built-in directive
 * definitions) so the printed form matches what the consumer's runtime serves as
 * {@code _service.sdl}.
 */
public final class SchemaSdlEmitter {

    private SchemaSdlEmitter() {
    }

    public static Path emit(GraphQLSchema assembled, GraphitronSchema schema, boolean federationLink,
                            Path resourcesRoot, String outputPackage) {
        Path target = packageDir(resourcesRoot, outputPackage).resolve("schema.graphqls");
        Set<String> droppedSupportTypes = droppedSupportTypes(schema);
        try {
            Files.createDirectories(target.getParent());
            String sdl = federationLink
                ? printFederationServiceSdl(assembled, droppedSupportTypes)
                : printPlain(assembled, droppedSupportTypes);
            Files.writeString(target, sdl, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target, e);
        }
    }

    /**
     * Support types classification did not retain: in the support set but absent from
     * {@code schema.types()}. The classifier is the single decision site; this method only
     * reads its outcome.
     */
    private static Set<String> droppedSupportTypes(GraphitronSchema schema) {
        return DirectiveSupportTypes.all().stream()
            .filter(name -> !schema.types().containsKey(name))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The single named print-seam element filter, shared by both arms so the two conditions it
     * carries cannot drift apart. Drops graphql-spec built-in directive <em>definitions</em>
     * when {@code dropSpecBuiltInDirectiveDefinitions} holds (the federation arm mirrors
     * {@code ServiceSDLPrinter.generateServiceSDLV2}; spec-built-in <em>applications</em> keep
     * printing, which is what the {@code @oneOf} augment relies on), and drops named types
     * in {@code droppedTypeNames} (support types classification did not retain) on both arms.
     */
    static boolean includeSchemaElement(GraphQLSchemaElement element,
                                        boolean dropSpecBuiltInDirectiveDefinitions,
                                        Set<String> droppedTypeNames) {
        if (dropSpecBuiltInDirectiveDefinitions
                && element instanceof GraphQLDirective directive
                && DirectiveInfo.isGraphqlSpecifiedDirective(directive)) {
            return false;
        }
        return !(element instanceof GraphQLNamedType named && droppedTypeNames.contains(named.getName()));
    }

    private static String printFederationServiceSdl(GraphQLSchema assembled, Set<String> droppedSupportTypes) {
        // Mirror the generated runtime build: Federation.transform attaches the federation
        // runtime types (_Service, _entities, _Entity) exactly as the consumer's runtime does.
        // The resolveEntityType / fetchEntities callbacks are required by the API but never
        // invoked at print time, so a placeholder pair suffices.
        var federated = Federation.transform(assembled)
            .setFederation2(true)
            .resolveEntityType(env -> null)
            .fetchEntities(env -> List.of())
            .build();
        var options = SchemaPrinter.Options.defaultOptions()
            .includeSchemaDefinition(true)
            .includeScalarTypes(true)
            .includeDirectiveDefinition(SchemaDirectiveRegistry::isSurvivor)
            .includeDirectives(SchemaDirectiveRegistry::isSurvivor)
            .includeSchemaElement(element -> includeSchemaElement(element, true, droppedSupportTypes));
        String sdl = new SchemaPrinter(options).print(federated).trim();
        // The spec-built-in filter above strips the @oneOf definition but keeps the application
        //. Reinstate the definition so Apollo composition does not reject the subgraph
        // with "Unknown directive @oneOf"; no-op (byte-identical) when the schema has no @oneOf.
        return OneOfDirectiveSdl.augment(assembled, sdl);
    }

    private static String printPlain(GraphQLSchema assembled, Set<String> droppedSupportTypes) {
        var options = SchemaPrinter.Options.defaultOptions()
            .includeDirectiveDefinition(SchemaDirectiveRegistry::isSurvivor)
            .includeDirectives(SchemaDirectiveRegistry::isSurvivor)
            .includeScalarTypes(true)
            .includeIntrospectionTypes(false)
            .includeSchemaDefinition(true)
            .includeSchemaElement(element -> includeSchemaElement(element, false, droppedSupportTypes));
        return new SchemaPrinter(options).print(assembled);
    }

    private static Path packageDir(Path resourcesRoot, String outputPackage) {
        Path dir = resourcesRoot;
        if (outputPackage.isEmpty()) {
            return dir;
        }
        for (String segment : outputPackage.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir;
    }
}
