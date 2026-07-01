package no.sikt.graphitron.rewrite.test.querydb;

import graphql.language.EnumTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.generators.util.SchemaDirectiveRegistry;
import no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
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
            .contains(FederationSpec.URL)
            .as("federated SDL carries the schema-applied @link declaration (R250)")
            .containsPattern("schema\\s+@link\\s*\\(");
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

    /**
     * R291: the published SDL carries no Graphitron-internal surface, for every plugin
     * execution in this module. Strictly internal support types never print; directive
     * definitions and applications are survivor-only ({@link SchemaDirectiveRegistry});
     * assertions are structural (re-parse, then walk), not substring matches.
     */
    @Test
    void emittedSdlCarriesNoGraphitronInternalSurface() throws IOException {
        for (String outputPackage : List.of(
                "no.sikt.graphitron.generated",
                "no.sikt.graphitron.generated.federated",
                "no.sikt.graphitron.generated.multischema")) {
            var registry = new SchemaParser().parse(
                Files.readString(sdlFor(outputPackage), StandardCharsets.UTF_8));

            DirectiveSupportTypes.strictlyInternal().forEach(name ->
                assertThat(registry.getTypeOrNull(name))
                    .as("strictly internal type %s must not reach %s", name, outputPackage)
                    .isNull());

            registry.getDirectiveDefinitions().keySet().forEach(name ->
                assertThat(SchemaDirectiveRegistry.isSurvivor(name))
                    .as("directive definition @%s in %s must be a survivor", name, outputPackage)
                    .isTrue());

            registry.types().values().forEach(type -> {
                ((graphql.language.DirectivesContainer<?>) type).getDirectives().forEach(applied ->
                    assertThat(SchemaDirectiveRegistry.isSurvivor(applied.getName()))
                        .as("application @%s on type %s in %s must be a survivor",
                            applied.getName(), type.getName(), outputPackage)
                        .isTrue());
                if (type instanceof ObjectTypeDefinition object) {
                    object.getFieldDefinitions().forEach(field ->
                        field.getDirectives().forEach(applied ->
                            assertThat(SchemaDirectiveRegistry.isSurvivor(applied.getName()))
                                .as("application @%s on %s.%s in %s must be a survivor",
                                    applied.getName(), type.getName(), field.getName(), outputPackage)
                                .isTrue()));
                }
            });
        }
    }

    /**
     * R292: the synthesised relay boilerplate for the directive-driven {@code stores: [Store!]!
     * @asConnection} carrier carries the canonical graphql-relay-js descriptions, and they survive
     * the {@code SchemaPrinter} seam end to end onto the emitted {@code schema.graphqls}. Parsed
     * structurally (re-parse, then read the description off each type/field), not by substring
     * match, so a printer that dropped descriptions would fail here.
     *
     * <p>Asserts only the genuinely-synthesised types ({@code QueryStoresConnection} /
     * {@code QueryStoresEdge}). This fixture declares {@code PageInfo} structurally in SDL (shared
     * by the hand-written {@code FilmsConnection} / {@code ActorsConnection}), so the synthesis
     * path reuses the consumer-owned object and does not stamp a description on it; that is the
     * R292 scope boundary. Synthesised-{@code PageInfo} descriptions are covered at the unit tier
     * by {@code ConnectionPromoterTest.directiveDrivenSynthesis_carriesRelayDescriptionsOnTypesAndFields},
     * whose fixture declares no PageInfo.
     */
    @Test
    void synthesisedConnectionBoilerplateCarriesRelayDescriptions() throws IOException {
        var registry = new SchemaParser().parse(
            Files.readString(sdlFor("no.sikt.graphitron.generated"), StandardCharsets.UTF_8));

        var connection = requireNonNull(registry.getTypeOrNull("QueryStoresConnection", ObjectTypeDefinition.class));
        assertThat(descriptionOf(connection)).isEqualTo("A connection to a list of items.");
        assertThat(fieldDescription(connection, "edges")).isEqualTo("A list of edges.");
        assertThat(fieldDescription(connection, "nodes")).isEqualTo("A list of nodes.");
        assertThat(fieldDescription(connection, "pageInfo")).isEqualTo("Information to aid in pagination.");
        assertThat(fieldDescription(connection, "totalCount"))
            .isEqualTo("Identifies the total count of items in the connection.");

        var edge = requireNonNull(registry.getTypeOrNull("QueryStoresEdge", ObjectTypeDefinition.class));
        assertThat(descriptionOf(edge)).isEqualTo("An edge in a connection.");
        assertThat(fieldDescription(edge, "cursor")).isEqualTo("A cursor for use in pagination.");
        assertThat(fieldDescription(edge, "node")).isEqualTo("The item at the end of the edge.");
    }

    private static String descriptionOf(ObjectTypeDefinition type) {
        return type.getDescription() == null ? null : type.getDescription().getContent();
    }

    private static String fieldDescription(ObjectTypeDefinition type, String fieldName) {
        var field = type.getFieldDefinitions().stream()
            .filter(f -> f.getName().equals(fieldName))
            .findFirst().orElseThrow();
        return field.getDescription() == null ? null : field.getDescription().getContent();
    }

    /**
     * R291 retention split across the fixtures: the shared fixture references
     * {@code SortDirection} from {@code FilmOrderBy} / {@code ActorOrderBy}, so the published
     * support type prints with its description; the federated and multischema fixtures
     * reference no support type, demonstrating the all-dropped case.
     */
    @Test
    void publishedSupportTypeRetainedOnlyWhereReferenced() throws IOException {
        var shared = new SchemaParser().parse(
            Files.readString(sdlFor("no.sikt.graphitron.generated"), StandardCharsets.UTF_8));
        var sortDirection = requireNonNull(shared.getTypeOrNull("SortDirection", EnumTypeDefinition.class));
        assertThat(sortDirection.getDescription())
            .as("retained SortDirection carries its description")
            .isNotNull();
        assertThat(sortDirection.getEnumValueDefinitions())
            .allSatisfy(value -> assertThat(value.getDescription())
                .as("enum value %s carries its description", value.getName())
                .isNotNull());

        for (String unreferencing : List.of(
                "no.sikt.graphitron.generated.federated",
                "no.sikt.graphitron.generated.multischema")) {
            var registry = new SchemaParser().parse(
                Files.readString(sdlFor(unreferencing), StandardCharsets.UTF_8));
            DirectiveSupportTypes.all().forEach(name ->
                assertThat(registry.getTypeOrNull(name))
                    .as("unreferenced support type %s must not reach %s", name, unreferencing)
                    .isNull());
        }
    }
}
