package no.fellesstudentsystem.graphitron_newtestorder.recordtransformer;

import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_RECORD;
import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.DUMMY_SERVICE;

@DisplayName("Record Transformer - Classes for the RecordTransformer")
public class RecordTransformerClassTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "recordtransformer";

    public RecordTransformerClassTest() {
        super(SRC_TEST_RESOURCES_PATH, Set.of(DUMMY_SERVICE.get(), DUMMY_RECORD.get()));
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new TransformerClassGenerator(schema));
    }

    @Test
    @DisplayName("The class is still generated when no mappable records exist.")
    void whenNoRecordsExist() {
        assertFilesAreGenerated(Set.of("RecordTransformer.java"), "whenNoRecordsExist");
    }
}
