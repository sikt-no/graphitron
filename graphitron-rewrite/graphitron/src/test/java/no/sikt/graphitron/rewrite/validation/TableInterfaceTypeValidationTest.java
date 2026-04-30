package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class TableInterfaceTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED("table name resolved — no implementing types",
            new TableInterfaceType("FilmStatus", null, "status_type", new TableRef("film_status", "FILM_STATUS", "FilmStatus", List.of()), List.of()),
            List.of()),

        RESOLVED_WITH_BOUND_PARTICIPANTS("resolved table with table-bound implementing types — valid",
            new TableInterfaceType("FilmStatus", null, "status_type",
                new TableRef("film_status", "FILM_STATUS", "FilmStatus", List.of()),
                List.of(
                    new ParticipantRef.TableBound("NewFilm", new TableRef("film", "FILM", "Film", List.of()), null),
                    new ParticipantRef.TableBound("OldFilm", new TableRef("film", "FILM", "Film", List.of()), null)
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
    void tableInterfaceTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
