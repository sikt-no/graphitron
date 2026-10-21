package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import graphql.schema.GraphQLDirective;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLSchemaElement;
import graphql.schema.idl.DirectiveInfo;
import graphql.schema.idl.SchemaPrinter;
import no.sikt.graphitron.model.schema.OneOfDirectiveSdl;
import no.sikt.graphitron.model.schema.federation.FederationSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prints the emitted {@link GraphQLSchema} to a fixed {@code schema.graphqls} file under
 * {@code <resourcesRoot>/<outputPackage as path>/}.
 *
 * <p>It prints the emitted schema and nothing else: what is published was decided in capture,
 * {@code EmittedRegistry} having taken out graphitron's own directives and the support types no
 * author type references, so the printer filters nothing of its own.
 *
 * <p>The form follows the schema it prints. A schema applying the federation {@code @link},
 * authored or added for a configured tag, prints as the federation service SDL: it runs
 * {@link Federation#transform} first, mirroring the {@code Federation.transform(...)} call the
 * generated {@code GraphitronSchema.build} makes at consumer load time, and the option shape of
 * {@code ServiceSDLPrinter.generateServiceSDLV2}, including its dropping of graphql-spec built-in
 * directive definitions, so the file matches what the consumer's runtime serves as
 * {@code _service.sdl}. Any other schema prints plain.
 */
public final class SchemaSdlEmitter {

    private SchemaSdlEmitter() {
    }

    public static Path emit(GraphQLSchema emitted, Path resourcesRoot, String outputPackage) {
        Path target = packageDir(resourcesRoot, outputPackage).resolve("schema.graphqls");
        try {
            Files.createDirectories(target.getParent());
            String sdl = linksFederation(emitted)
                ? printFederationServiceSdl(emitted)
                : printPlain(emitted);
            Files.writeString(target, sdl, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target, e);
        }
    }

    /** Whether the schema applies a {@code @link} to the federation specification. */
    static boolean linksFederation(GraphQLSchema schema) {
        return schema.getSchemaAppliedDirectives("link").stream()
            .map(link -> link.getArgument("url"))
            .anyMatch(url -> url != null && url.getValue() instanceof String value
                && value.startsWith(FederationSpec.SPEC_PREFIX));
    }

    /**
     * The federation arm's one filter, mirroring {@code ServiceSDLPrinter.generateServiceSDLV2}:
     * graphql-spec built-in directive <em>definitions</em> do not print, their applications do,
     * which is what the {@code @oneOf} augment relies on.
     */
    static boolean includeSchemaElement(GraphQLSchemaElement element) {
        return !(element instanceof GraphQLDirective directive
            && DirectiveInfo.isGraphqlSpecifiedDirective(directive));
    }

    private static String printFederationServiceSdl(GraphQLSchema emitted) {
        // Mirror the generated runtime build: Federation.transform attaches the federation
        // runtime types (_Service, _entities, _Entity) exactly as the consumer's runtime does.
        // The resolveEntityType / fetchEntities callbacks are required by the API but never
        // invoked at print time, so a placeholder pair suffices.
        var federated = Federation.transform(emitted)
            .setFederation2(true)
            .resolveEntityType(env -> null)
            .fetchEntities(env -> List.of())
            .build();
        var options = SchemaPrinter.Options.defaultOptions()
            .includeSchemaDefinition(true)
            .includeScalarTypes(true)
            .includeSchemaElement(SchemaSdlEmitter::includeSchemaElement);
        String sdl = new SchemaPrinter(options).print(federated).trim();
        // The spec-built-in filter above strips the @oneOf definition but keeps the application.
        // Reinstate the definition so Apollo composition does not reject the subgraph with
        // "Unknown directive @oneOf"; no-op when the schema has no @oneOf.
        return OneOfDirectiveSdl.augment(emitted, sdl);
    }

    private static String printPlain(GraphQLSchema emitted) {
        var options = SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeIntrospectionTypes(false)
            .includeSchemaDefinition(true);
        return new SchemaPrinter(options).print(emitted);
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
