package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link ServiceCatalog#reflectServiceMethod} parameter classification
 * and {@link ServiceCatalog#reflectTableMethod} / {@link ServiceCatalog#reflectServiceMethod}
 * strict-return-type validation. Exercises the reflection path in isolation with synthetic
 * {@link TestServiceStub} / {@link TestTableMethodStub} methods; the classifier does not
 * read {@code BuildContext.schema} or {@code BuildContext.catalog}, so both may be
 * {@code null} here.
 */
class ServiceCatalogTest {

    private static final String STUB_CLASS = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String TABLE_METHOD_STUB_CLASS = "no.sikt.graphitron.rewrite.TestTableMethodStub";

    private static final ClassName FILM_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "FilmRecord");
    private static final ClassName LANGUAGE_RECORD = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables.records", "LanguageRecord");
    private static final ClassName JOOQ_RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName FILM_TABLE_CLASS = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables", "Film");
    private static final ClassName LANGUAGE_TABLE_CLASS = ClassName.get(
        "no.sikt.graphitron.rewrite.test.jooq.tables", "Language");

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, null));
    }

    @Test
    void reflectServiceMethod_dslContextParam_classifiedAsDslContextSource() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getByIdWithDsl", Set.of("id"), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0)).isInstanceOf(MethodRef.Param.Typed.class);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params.get(0).typeName()).isEqualTo("org.jooq.DSLContext");
        assertThat(params.get(1).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(1).name()).isEqualTo("id");
    }

    @Test
    void reflectServiceMethod_dslContextOnly_noArgs() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithDsl", Set.of(), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
        assertThat(params).noneMatch(p -> p instanceof MethodRef.Param.Sourced);
    }

    @Test
    void reflectServiceMethod_dslContextParamNameCollidesWithArg_typeWins() {
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilteredWithDsl", Set.of("filter"), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.DslContext.class);
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_onChildField_stillErrors() {
        // Non-empty parentPkColumns: child of a table-backed parent. SOURCES batching applies
        // here, so an unrecognized-shape parameter still falls through to the SOURCES error.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason()).contains("unrecognized sources type");
    }

    @Test
    void reflectServiceMethod_unrecognisedParam_onRootField_pointsAtArgCtxMismatch() {
        // Empty parentPkColumns: root operation field or DTO-parent child. SOURCES batching
        // cannot apply, so the rejection points at the actual problem (parameter name doesn't
        // match any GraphQL argument or context key) rather than mentioning sources at all.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown", Set.of(), Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: (none)")
            .contains("available context keys: (none)")
            .doesNotContain("sources type");
    }

    @Test
    void reflectServiceMethod_tableRecordSources_classifiedAsRowKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithTableRecordSources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.batchKey()).isEqualTo(new BatchKey.RowKeyed(filmPk));
    }

    @Test
    void reflectServiceMethod_dtoSources_onChildField_rejectedWithLifterDirectiveHint() {
        // Non-empty parentPkColumns: child of a table-backed parent. This is the only context
        // where the lifter-directive hint is genuinely actionable — DataLoader batching applies
        // and the missing piece is a DTO-to-key conversion, the feature roadmap/R1 will add.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("not backed by a jOOQ TableRecord")
            .contains("lifter directive");
    }

    @Test
    void reflectServiceMethod_dtoSources_onRootField_pointsAtArgCtxMismatch() {
        // Empty parentPkColumns: root operation field. List<DTO> would have been classified as
        // SOURCES, but root fields can't batch — the lifter-directive hint would mislead users
        // who really just have a Java-param-name vs. GraphQL-argument-name mismatch (the most
        // common cause). Surface the name mismatch directly.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", Set.of("inputs"), Set.of(), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("parameter 'keys'")
            .contains("does not match any GraphQL argument or context key")
            .contains("available GraphQL arguments: [inputs]")
            .doesNotContain("lifter directive")
            .doesNotContain("not backed by a jOOQ TableRecord");
    }

    @Test
    void reflectServiceMethod_dtoListMatchingArgName_classifiedAsArg() {
        // Happy path for the common batch-mutation pattern: Mutation with a single
        // List<InputDto> argument whose Java parameter name matches the GraphQL argument
        // name. This is what users hit on root operation fields once the name lines up.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithDtoSources", Set.of("keys"), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        var params = result.ref().params();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).source()).isInstanceOf(ParamSource.Arg.class);
        assertThat(params.get(0).name()).isEqualTo("keys");
    }

    @Test
    void reflectServiceMethod_rootFieldNameMismatch_listsAvailableNamesSorted() {
        // The error message lists both the available GraphQL argument names and context keys,
        // sorted, so users can spot typos. Multiple names exercise the join formatter.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getWithUnknown",
            Set.of("inputs", "filter"), Set.of("tenantId", "locale"), List.of(), null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("available GraphQL arguments: [filter, inputs]")
            .contains("available context keys: [locale, tenantId]");
    }

    @Test
    void reflectServiceMethod_listOfRecord1Sources_classifiedAsRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithListOfRecord1Sources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.batchKey()).isEqualTo(new BatchKey.RecordKeyed(filmPk));
    }

    @Test
    void reflectServiceMethod_setOfTableRecordSources_classifiedAsMappedRowKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfTableRecordSources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.batchKey()).isEqualTo(new BatchKey.MappedRowKeyed(filmPk));
    }

    @Test
    void reflectServiceMethod_setOfRow1Sources_classifiedAsMappedRowKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfRow1Sources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.batchKey()).isEqualTo(new BatchKey.MappedRowKeyed(filmPk));
    }

    @Test
    void reflectServiceMethod_setOfRecord1Sources_classifiedAsMappedRecordKeyed() {
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfRecord1Sources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isFalse();
        var sourced = result.ref().params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElseThrow();
        assertThat(sourced.batchKey()).isEqualTo(new BatchKey.MappedRecordKeyed(filmPk));
    }

    @Test
    void reflectServiceMethod_setOfDtoSources_onChildField_rejectedWithDtoMessage() {
        // Non-empty parentPkColumns: child of a table-backed parent. The Set<DTO> rejection
        // takes the same DTO-message path as List<DTO>, not the generic unrecognized-sources
        // fallback.
        var filmPk = List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"));
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilmsWithSetOfDtoSources", Set.of(), Set.of(), filmPk, null);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("not backed by a jOOQ TableRecord")
            .doesNotContain("unrecognized sources type");
    }

    // ===== Strict-return-type validation =====

    @Test
    void reflectServiceMethod_nullExpected_skipsValidationCapturesActual() {
        // expectedReturnType=null path: no validation; the captured TypeName on MethodRef.Basic
        // is whatever reflection saw, regardless of shape.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", Set.of(), Set.of(), List.of(), null);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType())
            .isEqualTo(ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD));
    }

    @Test
    void reflectServiceMethod_matchingParameterizedExpected_succeeds() {
        var expected = ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD);
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", Set.of(), Set.of(), List.of(), expected);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType()).isEqualTo(expected);
    }

    @Test
    void reflectServiceMethod_mismatchedRawClass_failsWithBothNamesInMessage() {
        // Single-cardinality field expects FilmRecord; method returns String — mismatch on the
        // raw outer class.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "get", Set.of(), Set.of(), List.of(), FILM_RECORD);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("must return")
            .contains("FilmRecord")
            .contains("String");
    }

    @Test
    void reflectServiceMethod_mismatchedInnerGeneric_failsStructurally() {
        // List-cardinality field expects Result<FilmRecord>; method returns Result<LanguageRecord>.
        // The raw outer Result matches; only the inner type differs. Structural equality must
        // catch this — a raw-class-only check would let it slip through.
        var expected = ParameterizedTypeName.get(JOOQ_RESULT, FILM_RECORD);
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getLanguages", Set.of(), Set.of(), List.of(), expected);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("FilmRecord")
            .contains("LanguageRecord");
    }

    @Test
    void reflectServiceMethod_mismatchedCardinality_failsListVsSingle() {
        // Field expects FilmRecord (Single); method returns Result<FilmRecord> (List).
        // Same inner class, different outer wrapper.
        var result = newCatalog().reflectServiceMethod(
            STUB_CLASS, "getFilms", Set.of(), Set.of(), List.of(), FILM_RECORD);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("must return")
            .contains("FilmRecord")
            .contains("Result");
    }

    @Test
    void reflectTableMethod_matchingExpected_succeedsAndCapturesClassName() {
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "getFilm", Set.of(), Set.of(), FILM_TABLE_CLASS);

        assertThat(result.failed()).isFalse();
        assertThat(result.ref().returnType()).isEqualTo(FILM_TABLE_CLASS);
    }

    @Test
    void reflectTableMethod_mismatchedClass_failsWithBothNamesInMessage() {
        // Method returns Film (the table class) but the field expects Language. The pre-existing
        // Table<?>-returning `get` covers the wider-return-type case; this case pins that the
        // strict check rejects mismatched specific-class returns symmetrically.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "getFilm", Set.of(), Set.of(), LANGUAGE_TABLE_CLASS);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("must return the generated jOOQ table class")
            .contains("Language")
            .contains("Film");
    }

    @Test
    void reflectTableMethod_widerReturnType_failsAgainstSpecificExpected() {
        // The legacy Table<?>-returning `get` method violates Invariants §3 when the field
        // expects a specific table class. Pins the rejection path the user is most likely to
        // trip into.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "get", Set.of(), Set.of(), FILM_TABLE_CLASS);

        assertThat(result.failed()).isTrue();
        assertThat(result.failureReason())
            .contains("must return the generated jOOQ table class")
            .contains("Film");
    }

    @Test
    void reflectTableMethod_nullExpected_skipsValidation() {
        // Condition-method callers (BuildContext + FieldBuilder) pass null for the expected class
        // since their return shape is Condition, not a table. Pin that null disables strict
        // validation regardless of the actual return type.
        var result = newCatalog().reflectTableMethod(
            TABLE_METHOD_STUB_CLASS, "get", Set.of(), Set.of(), null);

        assertThat(result.failed()).isFalse();
        // Captured return is the wider Table<?> raw class; the model still records it faithfully.
        assertThat(((TypeName) result.ref().returnType()).toString()).isEqualTo("org.jooq.Table");
    }
}
