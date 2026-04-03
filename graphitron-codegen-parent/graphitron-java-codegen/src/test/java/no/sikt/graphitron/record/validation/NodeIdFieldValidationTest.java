package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.GraphitronSchema;
import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.ChildField.NodeIdField;
import no.sikt.graphitron.record.type.NodeStep.NodeDirective;
import no.sikt.graphitron.record.type.NodeStep.NoNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inTableTypeSchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("parent type has @node — no errors",
            new NodeIdField("Film", "id", null, new NodeDirective(null, List.of())),
            List.of()),

        PARENT_LACKS_NODE("parent type has no @node — one error",
            new NodeIdField("Film", "id", null, new NoNode()),
            List.of("Field 'id': @nodeId requires the containing type to have @node"));

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        Case(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }

        GraphitronSchema schema() {
            return inTableTypeSchema("Film", "id", field);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nodeIdFieldValidation(Case tc) {
        assertThat(validate(tc.schema()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
