package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.ReferencePathElementRef.ConditionOnlyRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamInfo;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ServiceMethodRef;
import no.sikt.graphitron.rewrite.model.ServiceMethodRef.ServiceParam;
import no.sikt.graphitron.rewrite.model.SourcesRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceFieldValidationTest {

    // ===== ServiceRecordField — non-table return type =====

    private static final ServiceMethodRef RESOLVED_METHOD = new ServiceMethodRef(List.of(), "void");

    enum RecordCase implements ValidatorCase {

        NO_PATH("no @reference — no lift condition; valid for non-table return",
            new ServiceRecordField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType.PojoReturnType("Film", new FieldWrapper.Single(true)), List.of(), null, List.of(), List.of(), RESOLVED_METHOD),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method",
            new ServiceRecordField("Film", "externalChild", null, new ReturnTypeRef.OtherReturnType.PojoReturnType("Film", new FieldWrapper.Single(true)), List.of(
                new ConditionOnlyRef(new MethodRef("com.example.Conditions.liftCondition", "org.jooq.Condition",
                    List.of(new ParamInfo("org.jooq.DSLContext", "ctx"))))),
                null, List.of(), List.of(), RESOLVED_METHOD),
            List.of());

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        RecordCase(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RecordCase.class)
    void serviceRecordFieldValidation(RecordCase tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }

    // ===== ServiceTableField — table-bound return type =====

    enum TableCase implements ValidatorCase {

        SOURCES_CORRECT_TYPE("SOURCES param is RowKeyed — no error (parent is RootType, no PK cross-check)",
            new ServiceTableField("Film", "externalChild", null,
                new ReturnTypeRef.TableBoundReturnType("Film",
                    new TableRef("film", "FILM", "Film", Optional.of(List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")))),
                    new FieldWrapper.Single(true)),
                List.of(), null, List.of(), List.of(),
                new ServiceMethodRef(
                    List.of(new ServiceParam.SourcesParam("filmKeys", new SourcesRef.RowKeyed(List.of("java.lang.Integer")))),
                    "java.lang.Object")),
            List.of());

        private final String description;
        private final GraphitronField field;
        private final List<String> errors;

        TableCase(String description, GraphitronField field, List<String> errors) {
            this.description = description;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableCase.class)
    void serviceTableFieldValidation(TableCase tc) {
        assertThat(validate(tc.field()))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
