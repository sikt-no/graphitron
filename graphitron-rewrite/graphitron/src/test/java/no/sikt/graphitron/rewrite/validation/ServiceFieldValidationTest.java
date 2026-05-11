package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.schema;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.stubbedError;
import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import no.sikt.graphitron.rewrite.TestFixtures;

@UnitTier
class ServiceFieldValidationTest {

    // ===== ServiceRecordField — non-table return type =====

    private static final MethodRef RESOLVED_METHOD = TestFixtures.staticServiceMethodRef("com.example.Service", "method", TypeName.VOID, List.of());
    private static final List<ColumnRef> RESOLVED_KEY_COLUMNS =
        List.of(new ColumnRef("FILM_ID", "filmId", "java.lang.Integer"));
    private static final ReturnTypeRef.ResultReturnType RECORD_RT_SINGLE =
        new ReturnTypeRef.ResultReturnType("Film", new FieldWrapper.Single(true), null);
    private static final SourceKey RECORD_SOURCE_KEY =
        TestFixtures.serviceRecordSourceKey(RECORD_RT_SINGLE, new SourceKey.Wrap.Row(), RESOLVED_KEY_COLUMNS, List.of());
    private static final LoaderRegistration RECORD_LR =
        TestFixtures.loaderRegistration(RECORD_RT_SINGLE, false, false);

    enum RecordCase implements ValidatorCase {

        NO_PATH("no @reference — passes validation now that ServiceRecordField is implemented (Phase A)",
            new ServiceRecordField("Film", "externalChild", null, RECORD_RT_SINGLE, List.of(), RESOLVED_METHOD, RECORD_SOURCE_KEY, RECORD_LR, Optional.empty()),
            List.of()),

        WITH_LIFT_CONDITION("lift condition with a resolved method — DEFERRED until the lift form ships",
            new ServiceRecordField("Film", "externalChild", null, RECORD_RT_SINGLE, List.of(
                new JoinStep.ConditionJoin(TestFixtures.staticServiceMethodRef("com.example.Conditions", "liftCondition", ClassName.get("org.jooq", "Condition"),
                    List.of(new MethodRef.Param.Typed("ctx", "org.jooq.DSLContext", new ParamSource.DslContext()))), "")),
                RESOLVED_METHOD, RECORD_SOURCE_KEY, RECORD_LR, Optional.empty()),
            List.of("Field 'Film.externalChild': @service with a @reference path "
                + "(condition-join lift form) is not yet supported — see "
                + "graphitron-rewrite/roadmap/service-record-field.md"));

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
            buildServiceTableField(
                new ReturnTypeRef.TableBoundReturnType("Film",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"))),
                    new FieldWrapper.Single(true)),
                new SourceKey.Wrap.Row(),
                List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                false),
            List.of()),

        NO_SOURCES_PARAM("no Sources param — missing DataLoader batch key error",
            new ServiceTableField("Film", "externalChild", null,
                new ReturnTypeRef.TableBoundReturnType("Film",
                    TestFixtures.tableRef("film", "FILM", "Film", List.of()),
                    new FieldWrapper.Single(true)),
                List.of(), List.of(), new OrderBySpec.None(), null,
                TestFixtures.staticServiceMethodRef("com.example.FilmService", "getFilms", TypeName.OBJECT, List.of()),
                null, null,
                Optional.empty()),
            List.of("Field 'Film.externalChild': @service on a table-bound return type requires a Sources parameter for DataLoader batching"));

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
     * <p>Key columns are guaranteed to match the parent PK by construction (they are passed in
     * from {@code parentPkColumns} during reflection). The validator only needs to check that
     * {@code Sources}-typed parameters have a parent with a primary key.
     */
    interface TablePkCase {
        GraphitronType parentType();
        GraphitronField field();
        List<String> errors();
    }

    private static final TableRef FILM_TABLE_SINGLE_PK =
        TestFixtures.tableRef("film", "FILM", "Film",
            List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")));

    private static final TableRef FILM_TABLE_COMPOSITE_PK =
        TestFixtures.tableRef("film", "FILM", "Film",
            List.of(
                new ColumnRef("film_id",    "FILM_ID",    "java.lang.Integer"),
                new ColumnRef("language_id","LANGUAGE_ID","java.lang.Integer")));

    private static final TableRef FILM_TABLE_NO_PK =
        TestFixtures.tableRef("film", "FILM", "Film", List.of());

    private static GraphitronType.TableType filmTableType(TableRef tableRef) {
        return new GraphitronType.TableType("Film", null, tableRef);
    }

    private static final ReturnTypeRef.TableBoundReturnType FILM_RETURN =
        new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE_SINGLE_PK, new FieldWrapper.Single(true));

    private static ServiceTableField serviceField(SourceKey.Wrap wrap, List<ColumnRef> keyColumns, boolean mapped) {
        return buildServiceTableField(FILM_RETURN, wrap, keyColumns, mapped);
    }

    private static ServiceTableField buildServiceTableField(
            ReturnTypeRef.TableBoundReturnType returnType,
            SourceKey.Wrap wrap, List<ColumnRef> keyColumns, boolean mapped) {
        LoaderRegistration.Container container = mapped
            ? LoaderRegistration.Container.MAPPED_SET
            : LoaderRegistration.Container.POSITIONAL_LIST;
        return new ServiceTableField("Film", "externalChild", null, returnType,
            List.of(), List.of(), new OrderBySpec.None(), null,
            TestFixtures.staticServiceMethodRef("com.example.FilmService", "getFilms", TypeName.OBJECT,
                List.of(TestFixtures.sourced("filmKeys", wrap, keyColumns, container))),
            TestFixtures.serviceTableSourceKey(returnType, wrap, keyColumns),
            TestFixtures.loaderRegistration(returnType, mapped, false),
            Optional.empty());
    }

    private static ServiceTableField multipleSourcesField() {
        var keyCols = FILM_TABLE_SINGLE_PK.primaryKeyColumns();
        var wrap = new SourceKey.Wrap.Row();
        return new ServiceTableField("Film", "externalChild", null, FILM_RETURN,
            List.of(), List.of(), new OrderBySpec.None(), null,
            TestFixtures.staticServiceMethodRef("com.example.FilmService", "getFilms", TypeName.OBJECT,
                List.of(
                    TestFixtures.sourced("filmKeys1", wrap, keyCols, LoaderRegistration.Container.POSITIONAL_LIST),
                    TestFixtures.sourced("filmKeys2", wrap, keyCols, LoaderRegistration.Container.POSITIONAL_LIST))),
            TestFixtures.serviceTableSourceKey(FILM_RETURN, wrap, keyCols),
            TestFixtures.loaderRegistration(FILM_RETURN, false, false),
            Optional.empty());
    }

    enum TablePkValidationCase implements TablePkCase {

        ROW_KEYED_SINGLE_PK(
            "RowKeyed — single-column parent PK — no errors",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new SourceKey.Wrap.Row(), FILM_TABLE_SINGLE_PK.primaryKeyColumns(), false),
            List.of()),

        ROW_KEYED_PARENT_NO_PK(
            "RowKeyed — parent table has no PK — missing PK error",
            filmTableType(FILM_TABLE_NO_PK),
            // Source columns are irrelevant here — the validator inspects the parent table's
            // own primaryKeyColumns(); the source-key is just present to make the field
            // structurally valid. SourceKey's canonical-constructor non-empty invariant
            // prevents an empty list at this site.
            serviceField(new SourceKey.Wrap.Row(), FILM_TABLE_SINGLE_PK.primaryKeyColumns(), false),
            List.of("Field 'Film.externalChild': @service on a table-bound return type requires the " +
                "parent table 'film' to have a primary key")),

        ROW_KEYED_COMPOSITE_PK(
            "RowKeyed — parent table has composite PK — no errors",
            filmTableType(FILM_TABLE_COMPOSITE_PK),
            serviceField(new SourceKey.Wrap.Row(), FILM_TABLE_COMPOSITE_PK.primaryKeyColumns(), false),
            List.of()),

        RECORD_KEYED_SINGLE_PK(
            "RecordKeyed — single-column parent PK — no errors",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new SourceKey.Wrap.Record(), FILM_TABLE_SINGLE_PK.primaryKeyColumns(), false),
            List.of()),

        RECORD_KEYED_COMPOSITE_PK(
            "RecordKeyed — parent table has composite PK — no errors",
            filmTableType(FILM_TABLE_COMPOSITE_PK),
            serviceField(new SourceKey.Wrap.Record(), FILM_TABLE_COMPOSITE_PK.primaryKeyColumns(), false),
            List.of()),

        MAPPED_ROW_KEYED_SINGLE_PK(
            "MappedRowKeyed — Set<Row1<Integer>> source classifies cleanly (R61 dual-shape acceptance)",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new SourceKey.Wrap.Row(), FILM_TABLE_SINGLE_PK.primaryKeyColumns(), true),
            List.of()),

        MAPPED_RECORD_KEYED_SINGLE_PK(
            "MappedRecordKeyed — Set<Record1<Integer>> source classifies cleanly (R61 dual-shape acceptance)",
            filmTableType(FILM_TABLE_SINGLE_PK),
            serviceField(new SourceKey.Wrap.Record(), FILM_TABLE_SINGLE_PK.primaryKeyColumns(), true),
            List.of()),

        MULTIPLE_SOURCES_ROW_KEYED(
            "two RowKeyed SOURCES params — no errors when parent has PK",
            filmTableType(FILM_TABLE_SINGLE_PK),
            multipleSourcesField(),
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

    // ===== Instance-holder rejection arm — validator-tier reachability (R87 Phase C) =====

    /**
     * Drives the {@code service-catalog-instance-service-holder-shape} {@code @LoadBearingClassifierCheck}
     * end-to-end: builds a real schema bound to {@link no.sikt.graphitron.rewrite.TestInstanceServiceStubNoCtor}
     * (an instance holder with no {@code (DSLContext)} constructor), runs it through
     * {@code GraphitronSchemaBuilder} + {@code GraphitronSchemaValidator}, and asserts a single
     * {@link ValidationError} surfaces with both fix options ("make the method static, or add the
     * (DSLContext) constructor"). Existing {@code ServiceCatalogTest} coverage is unit-tier; this
     * test pins reachability through the validator's own dispatch surface, matching
     * {@code rewrite-design-principles.adoc:101-105} ("validator mirrors classifier invariants").
     */
    @org.junit.jupiter.api.Test
    void instanceServiceHolderShape_noCtor_validatorReportsAuthorError() {
        var schema = no.sikt.graphitron.rewrite.TestSchemaHelper.buildSchema("""
            type Query {
                film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestInstanceServiceStubNoCtor", method: "getFilm"})
            }
            type Film @table(name: "film") {
                title: String
            }
            """);

        var errors = validate(schema);
        assertThat(errors).extracting(ValidationError::message)
            .filteredOn(m -> m.contains("Query.film"))
            .as("validator surfaces the instance-holder rejection through the validator's own surface")
            .singleElement()
            .satisfies(message -> assertThat(message)
                .contains("make the method static")
                .contains("DSLContext"));
    }
}
