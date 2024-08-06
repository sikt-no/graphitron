package no.fellesstudentsystem.graphitron_newtestorder.code;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.FieldSpecification;
import no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry;
import no.fellesstudentsystem.graphitron_newtestorder.SchemaComponent;
import no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron_newtestorder.ReferencedEntry.*;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.SRC_ROOT;
import static no.fellesstudentsystem.graphitron_newtestorder.TestConfiguration.setProperties;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractTransformerDetectionTest {
    protected static final String TEST_PATH = SRC_ROOT + "/transformerdetection/";
    private final String testPath;
    private final Set<SchemaComponent> components;
    private final Set<ReferencedEntry> references = Set.of(DUMMY_RECORD, DUMMY_SERVICE, DUMMY_CONDITION);

    protected AbstractTransformerDetectionTest(String testPath, SchemaComponent... components) {
        this.testPath = testPath;
        this.components = Set.of(components);
    }

    @AfterEach
    public void destroy() {
        GeneratorConfig.clear();
    }

    public List<String> getTransformableFieldNames(String schemaParentFolder, Set<SchemaComponent> extraComponents) {
        var allComponents = Stream.concat(components.stream(), extraComponents.stream()).collect(Collectors.toSet());
        var allPaths = allComponents.stream().flatMap(it -> it.getPaths().stream()).collect(Collectors.toSet());
        var allReferences = Stream.concat(makeReferences(references).stream(), allComponents.stream().flatMap(it -> makeReferences(it.getReferences()).stream())).collect(Collectors.toSet());

        setProperties(new ArrayList<>(allReferences));

        return TestConfiguration
                .getProcessedSchema(testPath + schemaParentFolder, allPaths, false)
                .getTransformableFields() // Note that duplicate record type references are filtered away.
                .stream()
                .map(FieldSpecification::getName)
                .collect(Collectors.toList());
    }

    protected Set<ExternalReference> makeReferences(Set<ReferencedEntry> entries) {
        return entries.stream().map(ReferencedEntry::get).collect(Collectors.toSet());
    }

    protected void checkFoundNames(String path, String... expected) {
        checkFoundNames(path, Set.of(), expected);
    }

    protected void checkFoundNames(String path, Set<SchemaComponent> extraComponents, String... expected) {
        // Uses isEqualTo because duplicate field names should be checked for as well.
        assertThat(getTransformableFieldNames(path, extraComponents)).isEqualTo(List.of(expected));
    }
}
