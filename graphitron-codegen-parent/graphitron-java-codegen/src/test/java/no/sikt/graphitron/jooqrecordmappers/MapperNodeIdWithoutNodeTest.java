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

import static no.sikt.graphitron.common.configuration.ReferencedEntry.*;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.VAR_NODE_STRATEGY;

@DisplayName("Mappers - Node strategy is not used when Node interface is absent")
public class MapperNodeIdWithoutNodeTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "javamappers/nodeStrategy/toRecord";
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
        return makeReferences(DUMMY_SERVICE);
    }

    @Test
    @DisplayName("Node strategy is not used in mapper when enabled but no Node interface exists")
    void nodeStrategyWithoutNodeInMapper() {
        GeneratorConfig.setNodeStrategy(true);
        resultDoesNotContain("nodeIdWithoutNode", Set.of(DUMMY_INPUT_RECORD), "NodeIdStrategy", VAR_NODE_STRATEGY);
        GeneratorConfig.setNodeStrategy(false);
    }
}