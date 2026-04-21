package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class SplitLookupTableFieldValidationTest {

    private static final TableRef FILM_TABLE = new TableRef("film", "FILM", "Film", List.of());
    private static final LookupMapping EMPTY_LOOKUP = new LookupMapping(List.of(), FILM_TABLE);

    private static ReturnTypeRef.TableBoundReturnType filmReturn(FieldWrapper wrapper) {
        return new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, wrapper);
    }

    private static final BatchKey PARENT_BATCH_KEY = new BatchKey.RowKeyed(List.of());

    // Validator messages for the intra-variant runtime-stub branches. See the sibling comment
    // in SplitTableFieldValidationTest for why these are inlined rather than read from
    // SplitRowsMethodEmitter.unsupportedReason.
    private static final String SINGLE_CARDINALITY_STUB =
        "Field 'Language.film': Single-cardinality @splitQuery @lookupKey on 'Language.film' "
        + "not yet supported; list cardinality is the Phase 2b C2 scope.";

    enum Case implements ValidatorCase {

        SINGLE_CARDINALITY_STUBBED("single return — runtime stub today, surfaced as build error",
            new SplitLookupTableField("Language", "film", null, filmReturn(new FieldWrapper.Single(true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY, EMPTY_LOOKUP),
            List.of(SINGLE_CARDINALITY_STUB)),

        CONNECTION_BLOCKED("connection return — not valid on lookup field",
            new SplitLookupTableField("Language", "films", null, filmReturn(new FieldWrapper.Connection(true, true)), List.of(), List.of(), new OrderBySpec.None(), null, PARENT_BATCH_KEY, EMPTY_LOOKUP),
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
    void splitLookupTableFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
