package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.model.jooq.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class TableInterfaceFieldValidationTest {

    enum Case implements ValidatorCase {

        SINGLE_CARDINALITY("single cardinality — implemented, no errors expected",
            new TableInterfaceField("Film", "status", null, new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)), TestFixtures.discriminatorCol("FILM_TYPE"), List.of(), List.of(), List.of(), List.of(), new OrderBySpec.None(), null),
            List.of()),

        // The interface fetcher composes only the parent correlation and the discriminator
        // restriction; an accepted filter would be silently ignored at runtime (unfiltered rows,
        // wrong data), so the shape is a deferred rejection and the condition producer
        // backstop-throws on it.
        FILTERED("carries a filter the fetcher never folds — deferred rejection",
            new TableInterfaceField("Film", "status", null, new ReturnTypeRef.TableBoundReturnType("Film", TestFixtures.tableRef("film", "FILM", "Film", List.of()), new FieldWrapper.Single(true)), TestFixtures.discriminatorCol("FILM_TYPE"), List.of(), List.of(), List.of(),
                List.of(new no.sikt.graphitron.rewrite.model.ConditionFilter(
                    "com.example.Conditions", "statusCondition", List.of())),
                new OrderBySpec.None(), null),
            List.of("Field 'Film.status': filters on a single-table interface child coordinate are "
                + "not emitted: the interface fetcher composes only the parent correlation and the "
                + "discriminator restriction, so the filter would be silently ignored at runtime; "
                + "hoist the filterable argument to a concrete coordinate, or drop it"));

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
    void tableInterfaceFieldValidation(Case tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
