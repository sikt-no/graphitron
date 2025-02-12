package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.fetch.FetchClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Wiring - Generation of the method returning a runtime wiring")
public class WiringTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        var generator = new FetchClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema.nodeExists()));
    }

    @Test
    @DisplayName("One data fetcher generator exists")
    void defaultCase() {
        assertGeneratedContentMatches("default", CUSTOMER_QUERY, CUSTOMER);
    }

    @Test
    @DisplayName("Node data fetcher generator exists")
    void node() {
        assertGeneratedContentContains(
                "node", Set.of(NODE),
                "getRuntimeWiring(NodeIdHandler nodeIdHandler)",
                ".dataFetcher(\"node\", QueryGeneratedDataFetcher.node(nodeIdHandler)"
        );
    }

    @Test
    @DisplayName("No fetchers are generated")
    void noFetchers() {
        assertGeneratedContentContains("noFetchers", ".newRuntimeWiring();return wiring.build();");
    }

    @Test
    @DisplayName("Two data fetcher generators exist for the same type")
    void twoFetchers() {
        assertGeneratedContentContains(
                "twoFetchers", Set.of(CUSTOMER),
                "TypeRuntimeWiring.newTypeWiring(\"Query\")" +
                        ".dataFetcher(\"customer\", QueryGeneratedDataFetcher.customer())" +
                        ".dataFetcher(\"payment\", QueryGeneratedDataFetcher.payment())"
        );
    }

    @Test
    @DisplayName("Two types have one data fetcher each")
    void twoTypes() {
        assertGeneratedContentContains(
                "twoTypes",
                        ".newTypeWiring(\"Query\").dataFetcher(\"customer\",",
                        ".newTypeWiring(\"Customer\").dataFetcher(\"address\","
        );
    }

    @Test
    @Disabled("Not supported yet.")
    @DisplayName("Unreferenced types exist")
    void unreferencedTypes() {
        assertGeneratedContentContains("unreferencedTypes", ".newTypeWiring(\"SomeType\")");
    }
}
