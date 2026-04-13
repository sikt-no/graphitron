package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeValidationTest {

    private static final TableRef RESOLVED_FILM = new TableRef("film", "FILM", "Film", List.of());

    enum Case implements TypeValidatorCase {

        NO_NODE("no @node directive — plain @table type, no errors",
            new TableType("Film", null, RESOLVED_FILM, List.of()),
            List.of()),

        NODE_NO_KEY_COLUMNS("@node with no keyColumns argument — empty list, no errors",
            new NodeType("Film", null, RESOLVED_FILM, null, List.of(), List.of()),
            List.of()),

        NODE_WITH_TYPE_ID("@node with typeId and no keyColumns — no errors",
            new NodeType("Film", null, RESOLVED_FILM, "film", List.of(), List.of()),
            List.of()),

        NODE_WITH_RESOLVED_KEY_COLUMN("@node with a key column resolved in the jOOQ table — no errors",
            new NodeType("Film", null, RESOLVED_FILM, null, List.of(new ColumnRef("film_id", "FILM_ID", "")), List.of()),
            List.of());

        private final String description;
        private final GraphitronType type;
        private final List<String> errors;

        Case(String description, GraphitronType type, List<String> errors) {
            this.description = description;
            this.type = type;
            this.errors = errors;
        }

        @Override public GraphitronType type() { return type; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void nodeTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
