package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.field.GraphitronField;
import no.sikt.graphitron.rewrite.field.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable.Plain;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable.WithNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeIdFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("parent type has @node — no errors",
            new NodeIdField("Film", "id", null, new WithNode("film", "FILM", "Film", true, List.of(), List.of(), null, List.of())),
            List.of()),

        PARENT_LACKS_NODE("parent type has no @node — one error",
            new NodeIdField("Film", "id", null, new Plain("film", "FILM", "Film", true, List.of(), List.of())),
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
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nodeIdFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
