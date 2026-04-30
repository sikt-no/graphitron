package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.TestFixtures.filmTable;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.schema;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ColumnFieldValidationTest {

    enum Case {

        RESOLVED_IMPLICIT("no @field — column name defaults to the GraphQL field name",
            new ColumnField("Film", "title", null, "title", new ColumnRef("TITLE", "", ""), new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            false,
            List.of()),

        RESOLVED_EXPLICIT("@field(name:) overrides the column name",
            new ColumnField("Film", "title", null, "film_title", new ColumnRef("FILM_TITLE", "", ""), new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            false,
            List.of()),

        NON_TABLE_BACKED_PARENT("@column on a non-table-backed parent type is rejected",
            new ColumnField("Film", "title", null, "title", new ColumnRef("TITLE", "", ""), new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct()),
            true,
            List.of("Field 'Film.title': @column is not valid on a non-table-backed type"));

        private final String description;
        private final GraphitronField field;
        private final boolean useRootParent;
        private final List<String> errors;

        Case(String description, GraphitronField field, boolean useRootParent, List<String> errors) {
            this.description = description;
            this.field = field;
            this.useRootParent = useRootParent;
            this.errors = errors;
        }

        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void columnFieldValidation(Case tc) {
        GraphitronType parentType = tc.useRootParent
            ? new GraphitronType.RootType(tc.field.parentTypeName(), null)
            : new GraphitronType.TableType(tc.field.parentTypeName(), null, filmTable());
        assertThat(validate(schema(parentType, tc.field.name(), tc.field)))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors);
    }
}
