package no.fellesstudentsystem.graphitron_newtestorder.recordtransformer;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.DUMMY_SERVICE;

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
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new TransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("The class is still generated when no mappable records exist.")
    void whenNoRecordsExist() {
        assertFilesAreGenerated("whenNoRecordsExist", "RecordTransformer.java");
    }
}
