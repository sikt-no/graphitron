package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.ResolvedTable;
import no.sikt.graphitron.record.type.TableInterfaceType;
import no.sikt.graphitron.record.type.UnresolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableInterfaceTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED {
            public GraphitronType type() {
                return new TableInterfaceType("FilmStatus", null, "status_type", "film_status", new ResolvedTable("FILM_STATUS", FILM));
            }
            public List<String> errors() { return List.of(); }
        },

        UNRESOLVED_TABLE {
            public GraphitronType type() {
                return new TableInterfaceType("FilmStatus", null, "status_type", "film_status", new UnresolvedTable());
            }
            public List<String> errors() {
                return List.of("Type 'FilmStatus': table 'film_status' could not be resolved in the jOOQ catalog");
            }
        };

        public abstract GraphitronType type();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableInterfaceTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
