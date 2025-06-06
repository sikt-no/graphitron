package no.sikt.graphitron.recordtransformer;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.TransformerClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_RECORD;
import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;

@DisplayName("Record Transformer - Classes for the RecordTransformer")
public class RecordTransformerClassTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "recordtransformer";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE, DUMMY_RECORD);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new TransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("The class is still generated when no mappable records exist")
    void whenNoRecordsExist() {
        assertFilesAreGenerated("whenNoRecordsExist", "RecordTransformer");
    }
}
