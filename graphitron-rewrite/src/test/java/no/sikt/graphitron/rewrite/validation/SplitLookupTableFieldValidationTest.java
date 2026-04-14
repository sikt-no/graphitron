package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class SplitLookupTableFieldValidationTest {

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", new TableRef("film", "FILM", "Film", List.of()), wrapper);
    }

    private static final BatchKey PARENT_BATCH_KEY = new BatchKey.RowKeyed(List.of());

    enum Case implements ValidatorCase {

        VALID_SINGLE("single return — valid",
            new SplitLookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of()),

        VALID_LIST("list return — valid",
            new SplitLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.List(true, true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of()),

        CONNECTION_BLOCKED("connection return — not valid on lookup field",
            new SplitLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY),
            List.of("Field 'films': lookup fields must not return a connection"));

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
    void splitLookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
