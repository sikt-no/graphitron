package no.sikt.graphitron.record.validation;

import no.sikt.graphitron.record.ValidationError;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.GraphitronField;
import no.sikt.graphitron.record.field.OrderSpec;
import no.sikt.graphitron.record.field.QueryField.TableMethodQueryField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.inQuerySchema;
import static no.sikt.graphitron.record.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class TableMethodQueryFieldValidationTest {

    enum Case implements ValidatorCase {

        VALID("single cardinality — valid",
            new TableMethodQueryField("Query", "filmsByMethod", null, new FieldCardinality.Single()),
            List.of()),

        LIST_UNRESOLVED_INDEX("list cardinality: @defaultOrder references an index that could not be found — validation error",
            new TableMethodQueryField("Query", "filmsByMethod", null,
                new FieldCardinality.List(new DefaultOrderSpec(new OrderSpec.UnresolvedIndexOrder("IDX_MISSING"), "ASC"), List.of())),
            List.of("Field 'filmsByMethod': index 'IDX_MISSING' could not be resolved in the jOOQ catalog")),

        LIST_UNRESOLVED_PRIMARY_KEY("list cardinality: @defaultOrder uses primaryKey but the table has none — validation error",
            new TableMethodQueryField("Query", "filmsByMethod", null,
                new FieldCardinality.List(new DefaultOrderSpec(new OrderSpec.UnresolvedPrimaryKeyOrder(), "ASC"), List.of())),
            List.of("Field 'filmsByMethod': primary key could not be resolved — the table may not have one"));

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
    void tableMethodQueryFieldValidation(Case tc) {
        assertThat(validate(inQuerySchema("filmsByMethod", tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
