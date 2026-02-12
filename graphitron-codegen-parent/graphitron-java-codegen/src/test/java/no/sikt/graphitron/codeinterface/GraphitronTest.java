package no.sikt.graphitron.codeinterface;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.codeinterface.CodeInterfaceClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.SchemaComponent.FEDERATION_QUERY;
import static no.sikt.graphitron.common.configuration.SchemaComponent.NODE;

@DisplayName("Graphitron - Generation of the code interface methods")
public class GraphitronTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "codeinterface";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new CodeInterfaceClassGenerator(schema));
    }

    @Test
    @DisplayName("Default code interface")
    void defaultCase() {
        assertGeneratedContentMatches("default");
    }

    @Test
    @DisplayName("Node is enabled")
    void node() {
        assertGeneratedContentContains(
                "node", Set.of(NODE),
                "getRuntimeWiringBuilder(NodeIdHandler _iv_nodeIdHandler",
                ".getRuntimeWiringBuilder(_iv_nodeIdHandler",
                "getRuntimeWiring(NodeIdHandler _iv_nodeIdHandler",
                "return getRuntimeWiringBuilder(_iv_nodeIdHandler",
                "getSchema(NodeIdHandler _iv_nodeIdHandler)",
                "= getRuntimeWiringBuilder(_iv_nodeIdHandler)"
        );
    }

    @Test
    @DisplayName("Federation schema helper is generated when entities exist")
    void federation() {
        assertGeneratedContentContains(
                "federation", Set.of(FEDERATION_QUERY),
                "getFederatedSchema(TypeDefinitionRegistry registry",
                """
                public static GraphQLSchema getFederatedSchema(TypeDefinitionRegistry registry,
                        RuntimeWiring.Builder wiringBuilder) {
                    return FederationHelper.buildFederatedSchema(registry, wiringBuilder, EntityTypeResolver.entityTypeResolver(), QueryGeneratedDataFetcher._entities());
                """
        );
    }

    @Test
    @DisplayName("Federation schema helper includes NodeIdStrategy when configured")
    void federationWithNodeStrategy() {
        try {
            GeneratorConfig.setNodeStrategy(true);
            assertGeneratedContentContains(
                    "federation", Set.of(FEDERATION_QUERY, NODE),
                    """
                    public static GraphQLSchema getFederatedSchema(TypeDefinitionRegistry registry, RuntimeWiring.Builder wiringBuilder, NodeIdStrategy _iv_nodeIdStrategy) {
                        return FederationHelper.buildFederatedSchema(registry, wiringBuilder, EntityTypeResolver.entityTypeResolver(), QueryGeneratedDataFetcher._entities(_iv_nodeIdStrategy));
                    """
            );
        } finally {
            GeneratorConfig.setNodeStrategy(false);
        }
    }

    @Test
    @DisplayName("Simple federation schema helper is generated when federation is imported but no entities exist")
    void federationWithoutEntities() {
        assertGeneratedContentContains(
                "federation-no-entities",
                """
                public static GraphQLSchema getFederatedSchema(TypeDefinitionRegistry registry, RuntimeWiring.Builder wiringBuilder) {
                    return FederationHelper.buildFederatedSchema(registry, wiringBuilder);
                """
        );
    }

    @Test
    @DisplayName("Federation schema helper is NOT generated when federation is not imported")
    void noFederationWithoutImport() {
        resultDoesNotContain(
                "default",
                "getFederatedSchema",
                "FederationHelper"
        );
    }

    @Test
    @DisplayName("Simple getSchema overload with includeFederation parameter is generated when federation is imported")
    void getSchemaWithIncludeFederationParameter() {
        assertGeneratedContentContains(
                "federation", Set.of(FEDERATION_QUERY),
                "getSchema(boolean includeFederation)",
                """
                public static GraphQLSchema getSchema(boolean includeFederation) {
                    if (!includeFederation) {
                        return getSchema();
                    }
                    var wiring = getRuntimeWiringBuilder();
                    var registry = getTypeRegistry();
                    return getFederatedSchema(registry, wiring);
                }
                """
        );
    }

    @Test
    @DisplayName("Simple getSchema overload with includeFederation parameter includes NodeIdStrategy when Node is enabled")
    void getSchemaWithIncludeFederationAndNodeStrategy() {
        try {
            GeneratorConfig.setNodeStrategy(true);
            assertGeneratedContentContains(
                    "federation", Set.of(FEDERATION_QUERY, NODE),
                    "getSchema(NodeIdStrategy _iv_nodeIdStrategy, boolean includeFederation)",
                    """
                    public static GraphQLSchema getSchema(NodeIdStrategy _iv_nodeIdStrategy, boolean includeFederation) {
                        if (!includeFederation) {
                            return getSchema(_iv_nodeIdStrategy);
                        }
                        var wiring = getRuntimeWiringBuilder(_iv_nodeIdStrategy);
                        var registry = getTypeRegistry();
                        return getFederatedSchema(registry, wiring, _iv_nodeIdStrategy);
                    }
                    """
            );
        } finally {
            GeneratorConfig.setNodeStrategy(false);
        }
    }

    @Test
    @DisplayName("Simple getSchema overload is NOT generated when federation is not imported")
    void noGetSchemaOverloadWithoutFederation() {
        resultDoesNotContain(
                "default",
                "includeFederation"
        );
    }
}
