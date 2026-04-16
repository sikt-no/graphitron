package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class InterfaceTypeValidationTest {

    enum Case implements TypeValidatorCase {

        NO_PARTICIPANTS("interface type with no implementing types — valid",
            new InterfaceType("Node", null, List.of()),
            List.of()),

        ALL_BOUND("all implementing types are table-bound — valid",
            new InterfaceType("Media", null, List.of(
                new ParticipantRef.TableBound("Film", new TableRef("film", "FILM", "Film", List.of()), null),
                new ParticipantRef.TableBound("Actor", new TableRef("actor", "ACTOR", "Actor", List.of()), null)
            )),
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
    void interfaceTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
