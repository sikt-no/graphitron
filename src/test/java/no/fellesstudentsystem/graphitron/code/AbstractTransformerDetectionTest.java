package no.fellesstudentsystem.graphitron.code;

import no.fellesstudentsystem.graphitron.common.GeneratorTest;
import no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.definitions.interfaces.FieldSpecification;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.common.configuration.ReferencedEntry.*;
import static no.fellesstudentsystem.graphitron.common.configuration.SchemaComponent.CUSTOMER_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractTransformerDetectionTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "transformerdetection";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_RECORD, DUMMY_SERVICE, DUMMY_CONDITION);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(CUSTOMER_TABLE);
    }

    public List<String> getTransformableFieldNames(String schemaParentFolder, Set<SchemaComponent> extraComponents) {
        return getProcessedSchema(schemaParentFolder, extraComponents)
                .getTransformableFields() // Note that duplicate record type references are filtered away.
                .stream()
                .map(FieldSpecification::getName)
                .collect(Collectors.toList());
    }

    protected void checkFoundNames(String path, String... expected) {
        checkFoundNames(path, Set.of(), expected);
    }

    protected void checkFoundNames(String path, Set<SchemaComponent> extraComponents, String... expected) {
        // Uses isEqualTo because duplicate field names should be checked for as well.
        assertThat(getTransformableFieldNames(path, extraComponents)).isEqualTo(List.of(expected));
    }
}
