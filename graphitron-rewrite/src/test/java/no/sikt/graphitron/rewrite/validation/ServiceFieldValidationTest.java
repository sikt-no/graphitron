package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.schema;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceFieldValidationTest {

    // ===== ServiceRecordField — non-table return type =====

    private static final MethodRef RESOLVED_METHOD = new MethodRef("com.example.Service", "method", "void", List.of());

    enum RecordCase implements ValidatorCase {

        NO_PATH("no @reference — no lift condition; valid for non-table return",
            new ServiceRecordField("Film", "externalChild", null, new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), null), List.of(), RESOLVED_METHOD),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method",
            new ServiceRecordField("Film", "externalChild", null, new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), null), List.of(
                new JoinStep.ConditionJoin(new MethodRef("com.example.Conditions", "liftCondition", "org.jooq.Condition",
                    List.of(new MethodRef.Param.Typed("ctx", "org.jooq.DSLContext", new ParamSource.DslContext()))))),
                RESOLVED_METHOD),
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

    // ===== ServiceTableField — table-bound return type, RootType parent =====

    enum TableCase implements ValidatorCase {

        SOURCES_CORRECT_TYPE("SOURCES param is RowKeyed — no error (parent is RootType, no PK cross-check)",
            new ServiceTableField("Film", "externalChild", null,
                new ReturnTypeRef.TableBoundReturnType("Film",
                    new TableRef("film", "FILM", "Film", List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"))),
                    new FieldWrapper.Single(true)),
                List.of(), List.of(), new OrderBySpec.None(), null,
                new MethodRef("com.example.FilmService", "getFilms", "java.lang.Object",
                    List.of(new MethodRef.Param.Sourced("filmKeys", new BatchKey.RowKeyed(List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"))))))),
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

    // ===== ServiceTableField — PK validation with a real TableType parent =====

    /**
     * Cases that require a real {@link GraphitronType.TableType} parent so the validator can
     * inspect the parent table's primary key. These use {@code schema(parentType, fieldName, field)}
     * rather than the {@code validate(field)} shortcut, which would wrap the field in a
     * {@link GraphitronType.RootType} and skip all PK checks.
     *
     * <p>With {@link BatchKey}, key columns are guaranteed to match the parent PK by construction
     * (they are passed in from {@code parentPkColumns} during reflection). The validator only
     * needs to check that Row-keyed and Record-keyed parameters have a parent with a primary key.
     */
    interface TablePkCase {
        GraphitronType parentType();
        GraphitronField field();
        List<String> errors();
    }

    private static final TableRef FILM_TABLE_SINGLE_PK =
        new TableRef("film", "FILM", "Film",
            List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")));

    private static final TableRef FILM_TABLE_COMPOSITE_PK =
        new TableRef("film", "FILM", "Film",
            List.of(
                new ColumnRef("film_id",    "FILM_ID",    "java.lang.Integer"),
                new ColumnRef("language_id","LANGUAGE_ID","java.lang.Integer")));

    private static final TableRef FILM_TABLE_NO_PK =
        new TableRef("film", "FILM", "Film", List.of());

    private static GraphitronType.TableType filmTableType(TableRef tableRef) {
        return new GraphitronType.TableType("Film", null, tableRef, List.of());
    }

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE_SINGLE_PK, new FieldWrapper.Single(true));

    private static ServiceTableField serviceField(BatchKey batchKey) {
        return new ServiceTableField("Film", "externalChild", null, FILM_RETURN,
            List.of(), List.of(), new OrderBySpec.None(), null,
            new MethodRef("com.example.FilmService", "getFilms", "java.lang.Object",
                List.of(new MethodRef.Param.Sourced("filmKeys", batchKey))));
    }

    enum TablePkValidationCase implements TablePkCase {

        ROW_KEYED_SINGLE_PK(
            "RowKeyed — single-column parent PK — no errors",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new BatchKey.RowKeyed(FILM_TABLE_SINGLE_PK.primaryKeyColumns())),
            List.of()),

        ROW_KEYED_PARENT_NO_PK(
            "RowKeyed — parent table has no PK — missing PK error",
            filmTableType(FILM_TABLE_NO_PK),
            serviceField(new BatchKey.RowKeyed(List.of())),
            List.of("Field 'externalChild': @service on a table-bound return type requires the " +
                "parent table 'film' to have a primary key")),

        ROW_KEYED_COMPOSITE_PK(
            "RowKeyed — parent table has composite PK — no errors",
            filmTableType(FILM_TABLE_COMPOSITE_PK),
            serviceField(new BatchKey.RowKeyed(FILM_TABLE_COMPOSITE_PK.primaryKeyColumns())),
            List.of()),

        RECORD_KEYED_SINGLE_PK(
            "RecordKeyed — single-column parent PK — no errors",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new BatchKey.RecordKeyed(FILM_TABLE_SINGLE_PK.primaryKeyColumns())),
            List.of()),

        RECORD_KEYED_COMPOSITE_PK(
            "RecordKeyed — parent table has composite PK — no errors",
            filmTableType(FILM_TABLE_COMPOSITE_PK),
            serviceField(new BatchKey.RecordKeyed(FILM_TABLE_COMPOSITE_PK.primaryKeyColumns())),
            List.of()),

        OBJECT_BASED(
            "ObjectBased — no PK constraint on parent table — no errors",
            filmTableType(FILM_TABLE_NO_PK),
            serviceField(new BatchKey.ObjectBased("no.sikt.graphitron.jooq.generated.tables.records.FilmRecord")),
            List.of()),

        MULTIPLE_SOURCES_ROW_KEYED(
            "two RowKeyed SOURCES params — no errors when parent has PK",
            filmTableType(FILM_TABLE_SINGLE_PK),
            new ServiceTableField("Film", "externalChild", null, FILM_RETURN,
                List.of(), List.of(), new OrderBySpec.None(), null,
                new MethodRef("com.example.FilmService", "getFilms", "java.lang.Object",
                    List.of(
                        new MethodRef.Param.Sourced("filmKeys1", new BatchKey.RowKeyed(FILM_TABLE_SINGLE_PK.primaryKeyColumns())),
                        new MethodRef.Param.Sourced("filmKeys2", new BatchKey.RowKeyed(FILM_TABLE_SINGLE_PK.primaryKeyColumns()))))),
            List.of());

        private final String description;
        private final GraphitronType parentType;
        private final GraphitronField field;
        private final List<String> errors;

        TablePkValidationCase(String description, GraphitronType parentType, GraphitronField field, List<String> errors) {
            this.description = description;
            this.parentType = parentType;
            this.field = field;
            this.errors = errors;
        }

        @Override public GraphitronType parentType() { return parentType; }
        @Override public GraphitronField field() { return field; }
        @Override public List<String> errors() { return errors; }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TablePkValidationCase.class)
    void serviceTableFieldPkValidation(TablePkValidationCase tc) {
        assertThat(validate(schema(tc.parentType(), tc.field().name(), tc.field())))
            .extracting(ValidationError::message)
            .containsExactlyInAnyOrderElementsOf(tc.errors());
    }
}
