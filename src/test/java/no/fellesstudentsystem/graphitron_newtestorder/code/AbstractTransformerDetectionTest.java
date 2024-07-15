package no.fellesstudentsystem.graphitron_newtestorder.code;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.FieldSpecification;
import no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferenceTestSet.*;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractTransformerDetectionTest {
    protected static final String TEST_PATH = SRC_ROOT + "/transformerdetection/";
    private final String testPath, subpathSchema;

    protected AbstractTransformerDetectionTest(String testPath, String subpathSchema) {
        this.testPath = testPath;
        this.subpathSchema = subpathSchema;
    }

    @BeforeEach
    public void setup() {
        setProperties(List.of(DUMMY_RECORD.get(), DUMMY_SERVICE.get(), DUMMY_CONDITION.get()));
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear();
    }

    public List<String> getTransformableFieldNames(String schemaParentFolder) {
        return TestConfiguration
                .getProcessedSchema(testPath + schemaParentFolder, subpathSchema, false)
                .getTransformableFields() // Note that duplicate record type references are filtered away.
                .stream()
                .map(FieldSpecification::getName)
                .collect(Collectors.toList());
    }

    protected void checkFoundNames(String path, String... expected) {
        // Uses isEqualTo because duplicate field names should be checked for as well.
        assertThat(getTransformableFieldNames(path)).isEqualTo(List.of(expected));
    }
}
