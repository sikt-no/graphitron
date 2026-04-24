package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.LookupTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class LookupTableFieldValidationTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final LookupMapping EMPTY_LOOKUP = new LookupMapping.ColumnMapping(List.of(), FILM_TABLE);

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
    }

    enum Case implements ValidatorCase {

        // Single-cardinality @lookupKey is now rejected at classifier time (argres Phase 2a C1);
        // it cannot reach the validator. Kept as a structural-validator smoke test: the model
        // record itself is constructible, and the validator has no extra errors to add.
        SINGLE_NOW_PROJECTED("single return — no validator errors; classifier rejection prevents reaching this state",
            new LookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null,
                EMPTY_LOOKUP),
            List.of()),

        LIST_PROJECTED("list return — inline-projected, no validator errors",
            new LookupTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)), List.of(), List.of(), new OrderBySpec.None(), null,
                EMPTY_LOOKUP),
            List.of()),

        CONNECTION_BLOCKED("connection return — not valid on lookup field (validator mirror of classifier rejection)",
            new LookupTableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, true)), List.of(), List.of(), new OrderBySpec.None(), null,
                EMPTY_LOOKUP),
            List.of("Field 'Language.films': lookup fields must not return a connection"));

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
    void lookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
