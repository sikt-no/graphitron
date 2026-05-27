package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders the assembled {@link GraphQLSchema} to a fixed {@code schema.graphqls}
 * file under {@code <resourcesRoot>/<outputPackage as path>/}.
 *
 * <p>The federation arm delegates to {@link ServiceSDLPrinter#generateServiceSDLV2}
 * (mirrors the round-trip locked by {@code FederationBuildSmokeTest}); the
 * non-federation arm uses graphql-java's {@link SchemaPrinter} with the same
 * include flags so the two arms are structurally comparable.
 */
public final class SchemaSdlEmitter {

    private SchemaSdlEmitter() {
    }

    public static Path emit(GraphQLSchema assembled, boolean federationLink, Path resourcesRoot, String outputPackage) {
        Path target = packageDir(resourcesRoot, outputPackage).resolve("schema.graphqls");
        try {
            Files.createDirectories(target.getParent());
            String sdl = federationLink ? ServiceSDLPrinter.generateServiceSDLV2(assembled) : printPlain(assembled);
            Files.writeString(target, sdl, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target, e);
        }
    }

    private static String printPlain(GraphQLSchema assembled) {
        var options = SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeIntrospectionTypes(false)
            .includeSchemaDefinition(true);
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
