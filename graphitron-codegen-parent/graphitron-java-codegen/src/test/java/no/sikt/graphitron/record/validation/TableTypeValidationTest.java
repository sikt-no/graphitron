package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.type.GraphitronType;
import no.sikt.graphitron.record.type.ResolvedTable;
import no.sikt.graphitron.record.type.TableType;
import no.sikt.graphitron.record.type.UnresolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED {
            public GraphitronType type() {
                return new TableType("Film", null, "film", new ResolvedTable("FILM", FILM));
            }
            public List<String> errors() { return List.of(); }
        },

        UNRESOLVED_TABLE {
            public GraphitronType type() {
                return new TableType("Film", null, "film", new UnresolvedTable());
            }
            public List<String> errors() {
                return List.of("Type 'Film': table 'film' could not be resolved in the jOOQ catalog");
            }
        };

        public abstract GraphitronType type();
        public abstract List<String> errors();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void tableTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
