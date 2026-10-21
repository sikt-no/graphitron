package no.sikt.graphitron.sakila.example.app;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.diffing.EditOperation;
import graphql.schema.diffing.SchemaDiffing;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import no.sikt.graphitron.jakarta.rest.AbstractGraphitronApplication;
import no.sikt.graphitron.model.schema.federation.FederationSpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDL a {@code graphitron-jakarta-rest} adapter serves on {@code /schema}, pinned in-process
 * over both generated facades. The example's Quarkus app boots only the non-federated schema, so
 * the federated arm is held here: an adapter given {@code Graphitron::schemaSdl} serves exactly
 * what the subgraph's {@code { _service { sdl } }} answers, which is the path Federation 2
 * composition already accepts. {@link SchemaEndpointTest} carries the property to the HTTP route.
 */
@PipelineTier
class AdapterSchemaSdlTest {

    /** An adapter over arbitrary facade references; nothing here executes a request through it. */
    private static final class Adapter extends AbstractGraphitronApplication {
        Adapter(Supplier<GraphQLSchema> schema, Function<GraphQLSchema, String> printer) {
            super(schema, printer);
        }

        @SuppressWarnings("deprecation")
        Adapter(Supplier<GraphQLSchema> schema) {
            super(schema);
        }

        @Override
        public ExecutionInput.Builder newExecutionInput() {
            throw new UnsupportedOperationException("not executed through the adapter");
        }
    }

    private static Adapter federated() {
        return new Adapter(
            () -> no.sikt.graphitron.generated.federated.Graphitron.buildSchema(b -> {}),
            no.sikt.graphitron.generated.federated.Graphitron::schemaSdl);
    }

    @Test
    @DisplayName("A federated adapter serves the SDL its _service query answers.")
    void federatedAdapterServesTheServiceSdl() {
        var adapter = federated();
        var result = GraphQL.newGraphQL(adapter.schema()).build()
            .execute(ExecutionInput.newExecutionInput().query("{ _service { sdl } }").build());
        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        var service = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("_service");

        // The federated fixture applies @oneOf, so this equality also covers the helper's augment.
        assertThat(adapter.schemaSdl()).isEqualTo(service.get("sdl"));
    }

    @Test
    @DisplayName("A federated adapter's SDL declares the federation @link on the schema.")
    void federatedAdapterSdlCarriesTheSchemaLink() {
        assertThat(federated().schemaSdl())
            .contains(FederationSpec.URL)
            .containsPattern("schema\\s+[^{]*@link\\s*\\(");
    }

    @Test
    @DisplayName("A federated adapter's SDL leaves out the built-in @skip and @include definitions.")
    void federatedAdapterSdlOmitsBuiltInDirectiveDefinitions() {
        assertThat(federated().schemaSdl())
            .doesNotContain("directive @skip")
            .doesNotContain("directive @include");
    }

    @Test
    @DisplayName("The plain facade's schemaSdl describes the same schema as the emitted schema.graphqls.")
    void plainSchemaSdlMatchesTheEmittedFile() throws Exception {
        String emitted;
        try (var in = no.sikt.graphitron.generated.Graphitron.class.getResourceAsStream("schema.graphqls")) {
            assertThat(in).as("emitted schema.graphqls on classpath").isNotNull();
            emitted = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String printed = no.sikt.graphitron.generated.Graphitron.schemaSdl(
            no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {}));

        List<EditOperation> edits = new SchemaDiffing().diffGraphQLSchema(parse(emitted), parse(printed));
        assertThat(edits).isEmpty();
    }

    @Test
    @DisplayName("An adapter built without a printer still serves SDL through the SPI default.")
    void adapterWithoutPrinterFallsBackToTheDefault() {
        var adapter = new Adapter(() -> no.sikt.graphitron.generated.Graphitron.buildSchema(b -> {}));
        assertThat(adapter.schemaSdl()).contains("type Query");
    }

    private static GraphQLSchema parse(String sdl) {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(new SchemaParser().parse(sdl));
    }
}
