package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable.Plain;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.TableRef.UnresolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED("table name resolved to a jOOQ Table",
            new TableType("Film", null, new Plain("film", "FILM", "Film", true, List.of(), List.of())),
            List.of()),

        UNRESOLVED_TABLE("table name could not be matched to a jOOQ table in the catalog",
            new TableType("Film", null, new UnresolvedTable("film")),
            List.of("Type 'Film': table 'film' could not be resolved in the jOOQ catalog"));

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
    void tableTypeValidation(Case tc) {
        assertThat(validate(tc.type()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
