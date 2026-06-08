package no.sikt.graphitron.rewrite.generators.schema;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.printer.ServiceSDLPrinter;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders the assembled {@link GraphQLSchema} to a fixed {@code schema.graphqls}
 * file under {@code <resourcesRoot>/<outputPackage as path>/}.
 *
 * <p>The federation arm runs {@link Federation#transform} on the assembled
 * schema before printing, mirroring the {@code Federation.transform(...)} call
 * the generated {@code GraphitronSchema.build} makes at consumer load time.
 * Without that step the emitted SDL would describe a different schema than the
 * one the consumer's runtime serves (missing the federation runtime fields
 * {@code _Service.sdl}, {@code Query._entities}, {@code Query._service}, and
 * the {@code _Entity} union). {@link ServiceSDLPrinter#generateServiceSDLV2}
 * strips those runtime fields back out before printing for subgraph
 * publication, so the on-disk SDL is what a supergraph composer would consume.
 *
 * <p>The non-federation arm uses graphql-java's {@link SchemaPrinter} with the
 * same include flags so the two arms are structurally comparable.
 */
public final class SchemaSdlEmitter {

    private SchemaSdlEmitter() {
    }

    public static Path emit(GraphQLSchema assembled, boolean federationLink, Path resourcesRoot, String outputPackage) {
        Path target = packageDir(resourcesRoot, outputPackage).resolve("schema.graphqls");
        try {
            Files.createDirectories(target.getParent());
            String sdl = federationLink ? printFederationServiceSdl(assembled) : printPlain(assembled);
            Files.writeString(target, sdl, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + target, e);
        }
    }

    private static String printFederationServiceSdl(GraphQLSchema assembled) {
        // Mirror the generated runtime build: Federation.transform attaches the
        // federation runtime types (_Service, _entities, _Entity); ServiceSDLPrinter
        // then strips them when rendering for subgraph publication. The
        // resolveEntityType / fetchEntities callbacks are required by the API but
        // never invoked at print time, so a placeholder pair suffices.
        var transformer = Federation.transform(assembled)
            .setFederation2(true)
            .resolveEntityType(env -> null)
            .fetchEntities(env -> List.of());
        String sdl = ServiceSDLPrinter.generateServiceSDLV2(transformer.build());
        // generateServiceSDLV2 emits the @oneOf application but strips the spec-built-in
        // definition (R283). Reinstate it so Apollo composition does not reject the subgraph
        // with "Unknown directive @oneOf"; no-op (byte-identical) when the schema has no @oneOf.
        return OneOfDirectiveSdl.augment(assembled, sdl);
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
