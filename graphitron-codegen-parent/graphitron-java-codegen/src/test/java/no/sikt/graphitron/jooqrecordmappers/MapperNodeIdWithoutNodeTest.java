package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.JAVA_RECORD_CUSTOMER;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.MAPPER_FETCH_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;

@DisplayName("Mappers - Node strategy is not used when Node interface is absent")
public class MapperNodeIdWithoutNodeTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/nodeStrategy";
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new RecordMapperClassGenerator(schema, false),
                new RecordMapperClassGenerator(schema, true),
                new TransformerClassGenerator(schema)
        );
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(JAVA_RECORD_CUSTOMER, MAPPER_FETCH_SERVICE);
    }

    @Test
    @DisplayName("Node strategy is not used in mapper when enabled but no Node interface exists")
    void nodeStrategyWithoutNodeInMapper() {
        GeneratorConfig.setNodeStrategy(true);
        resultDoesNotContain(Set.of(CUSTOMER_TABLE), "NodeIdStrategy", VAR_NODE_STRATEGY);
        GeneratorConfig.setNodeStrategy(false);
    }
}