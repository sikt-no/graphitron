package no.sikt.graphitron.recordtransformer;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.*;

@DisplayName("Record Transformer - Content for the RecordTransformer")
public class RecordTransformerTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "recordtransformer";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_RECORD);
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new TransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("Maps from graph types to Java records")
    void fromGraphToJava() {
        assertGeneratedContentMatches("fromGraphToJava", DUMMY_INPUT_RECORD);
    }

    @Test
    @DisplayName("Maps from graph types to jOOQ records")
    void fromGraphToJOOQ() {
        assertGeneratedContentMatches("fromGraphToJOOQ", CUSTOMER_INPUT_TABLE);
    }

    @Test
    @DisplayName("Maps from Java records to graph types")
    void fromJavaToGraph() {
        assertGeneratedContentMatches("fromJavaToGraph");
    }

    @Test
    @DisplayName("Maps from jOOQ records to graph types")
    void fromJOOQToGraph() {
        assertGeneratedContentMatches("fromJOOQToGraph", CUSTOMER_TABLE);
    }

    @Test
    @DisplayName("Generates correct validation code pattern")
    void withValidation() {
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
        assertGeneratedContentContains(
                "withValidation",
                Set.of(CUSTOMER_INPUT_TABLE),
                "records = CustomerInputTableJOOQMapper.toJOOQRecord(input, path, this);" +
                        "validationErrors.addAll(CustomerInputTableJOOQMapper.validate(records, indexPath, this));" +
                        "return records"
        );
    }
}
