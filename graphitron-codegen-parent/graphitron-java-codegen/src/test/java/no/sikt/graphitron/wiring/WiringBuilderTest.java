package no.sikt.graphitron.wiring;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.wiring.WiringClassGenerator;
import no.sikt.graphitron.generators.datafetchers.operations.OperationClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Wiring - Generation of the method returning a runtime wiring builder")
public class WiringBuilderTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "wiring";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        var generator = new OperationClassGenerator(schema);
        return List.of(generator, new WiringClassGenerator(List.of(generator), schema));
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
                "getRuntimeWiringBuilder(NodeIdHandler nodeIdHandler)",
                ".dataFetcher(\"node\", QueryGeneratedDataFetcher.node(nodeIdHandler)"
        );
    }

    @Test
    @DisplayName("Node data fetcher generator exists")
    void nodeStrategy() {
        GeneratorConfig.setNodeStrategy(true);
        assertGeneratedContentContains(
                "node", Set.of(NODE),
                "getRuntimeWiringBuilder(NodeIdStrategy nodeIdStrategy)",
                ".dataFetcher(\"node\", QueryGeneratedDataFetcher.node(nodeIdStrategy)"
        );
        GeneratorConfig.setNodeStrategy(false);
    }

    @Test
    @DisplayName("No fetchers are generated")
    void noFetchers() {
        assertGeneratedContentContains("noFetchers", ".newRuntimeWiring();return wiring;");
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
    @DisplayName("Extended scalars present in the schema are added to the wiring")
    void extendedScalars() {
        assertGeneratedContentContains(
                "scalars",
                "wiring.scalar(ExtendedScalars.LocalTime);",
                "wiring.scalar(ExtendedScalars.GraphQLBigInteger);"
        );
    }
}
