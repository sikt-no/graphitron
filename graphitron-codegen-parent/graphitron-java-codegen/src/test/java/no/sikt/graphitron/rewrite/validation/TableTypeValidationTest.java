package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.type.GraphitronType;
import no.sikt.graphitron.rewrite.type.NodeRef.NoNode;
import no.sikt.graphitron.rewrite.type.TableRef.ResolvedTable;
import no.sikt.graphitron.rewrite.type.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.type.TableRef.UnresolvedTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.FILM;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableTypeValidationTest {

    enum Case implements TypeValidatorCase {

        RESOLVED("table name resolved to a jOOQ Table",
            new TableType("Film", null, new ResolvedTable("film", "FILM", FILM), new NoNode()),
            List.of()),

        UNRESOLVED_TABLE("table name could not be matched to a jOOQ table in the catalog",
            new TableType("Film", null, new UnresolvedTable("film"), new NoNode()),
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
