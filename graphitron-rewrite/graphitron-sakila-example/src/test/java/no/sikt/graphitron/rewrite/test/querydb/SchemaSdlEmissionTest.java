package no.sikt.graphitron.rewrite.test.querydb;

import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage for {@code SchemaSdlEmitter}: the plugin executions
 * configured in this module's pom run the generator at GENERATE_SOURCES, so
 * by the time the test phase runs, the emitted SDL files are on disk under
 * {@code target/generated-resources/graphitron/<package as path>/schema.graphqls}.
 *
 * <p>The federation arm reuses the canonical assertions
 * {@code FederationBuildSmokeTest.serviceSdlExposesCanonicalKeyDirectiveShape}
 * makes against the in-memory {@code ServiceSDLPrinter} output, but applies
 * them to the file on disk so a regression in the emitter (wrong path, wrong
 * printer, encoding) fails the build.
 */
@PipelineTier
class SchemaSdlEmissionTest {

    private static final Path RESOURCES_ROOT =
        Path.of("target/generated-resources/graphitron").toAbsolutePath();

    private static Path sdlFor(String outputPackage) {
        Path dir = RESOURCES_ROOT;
        for (String segment : outputPackage.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir.resolve("schema.graphqls");
    }

    @Test
    void federatedExecutionEmitsServiceSdl() throws IOException {
        Path sdlFile = sdlFor("no.sikt.graphitron.generated.federated");
        assertThat(sdlFile).exists();
        String sdl = Files.readString(sdlFile, StandardCharsets.UTF_8);

        assertThat(sdl)
            .as("federated SDL declares the canonical @key directive with federation__FieldSet")
            .containsPattern(
                "directive\\s+@key\\s*\\(\\s*fields\\s*:\\s*federation__FieldSet\\s*!\\s*,\\s*"
                    + "resolvable\\s*:\\s*Boolean\\s*=\\s*true\\s*\\)\\s+repeatable\\s+on")
            .as("federated SDL registers the synthesised federation__FieldSet scalar")
            .contains("scalar federation__FieldSet")
            .as("federated SDL carries the @link directive referencing FederationSpec.URL")
            .contains(FederationSpec.URL);
    }

    @Test
    void unfederatedExecutionEmitsPlainSdl() throws IOException {
        Path sdlFile = sdlFor("no.sikt.graphitron.generated");
        assertThat(sdlFile).exists();
        String sdl = Files.readString(sdlFile, StandardCharsets.UTF_8);

        assertThat(sdl)
            .as("non-federation arm must not route through ServiceSDLPrinter")
            .doesNotContain("federation__FieldSet")
            .doesNotContain(FederationSpec.URL);
        assertThat(new SchemaParser().parse(sdl))
            .as("non-federation SDL is parseable by graphql-java SchemaParser")
            .isNotNull();
    }

    @Test
    void emittedSdlShipsOnClasspathUnderOutputPackage() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        assertThat(cl.getResource("no/sikt/graphitron/generated/schema.graphqls"))
            .as("non-federation SDL must be available as a classpath resource under outputPackage")
            .isNotNull();
        assertThat(cl.getResource("no/sikt/graphitron/generated/federated/schema.graphqls"))
            .as("federated SDL must be available as a classpath resource under outputPackage")
            .isNotNull();
    }
}
