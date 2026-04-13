package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

class TableCodeGeneratorTest {

    private static final TableCodeGenerator GEN = new TableCodeGenerator();

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            java.util.List.of(), java.util.Set.of(), java.util.List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    private static TableRef tableRef(String sqlName, String javaFieldName, String javaClassName) {
        return new TableRef(sqlName, javaFieldName, javaClassName,
            List.of(new ColumnRef("id", "ID", "java.lang.Integer")));
    }

    private static TypeSpec spec(String tableName) {
        return GEN.generate(tableRef(tableName.toLowerCase(), tableName.toUpperCase(), tableName));
    }

    private static MethodSpec method(String tableName, String methodName) {
        return spec(tableName).methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameMatchesTableName() {
        assertThat(spec("Film").name()).isEqualTo("Film");
    }

    @Test
    void generate_classIsPublic() {
        assertThat(spec("Film").modifiers()).contains(Modifier.PUBLIC);
    }

    /** Finds the overload of {@code methodName} whose first parameter type string contains {@code firstParamHint}. */
    private static MethodSpec methodByFirstParam(String tableName, String methodName, String firstParamHint) {
        return spec(tableName).methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)
                && !m.parameters().isEmpty()
                && m.parameters().get(0).type().toString().contains(firstParamHint))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Method not found: " + methodName + " with first param containing '" + firstParamHint + "'"));
    }

    // ===== All eight methods present =====

    @Test
    void generate_allEightMethodsArePresent() {
        assertThat(spec("Film").methodSpecs()).hasSize(8);
        assertThat(spec("Film").methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder(
                "selectMany", "selectOne",   // root query
                "selectMany", "selectOne",   // Row-keyed service
                "selectMany", "selectOne",   // Record-keyed service
                "subselectMany", "subselectOne");
    }

    // ===== selectMany =====

    @Test
    void selectMany_isPublicStatic() {
        assertThat(method("Film", "selectMany").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectMany_returnType() {
        assertThat(method("Film", "selectMany").returnType().toString())
            .isEqualTo("org.jooq.Result<org.jooq.Record>");
    }

    @Test
    void selectMany_parameters() {
        var params = method("Film", "selectMany").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingEnvironment",
                "org.jooq.Condition",
                "java.util.List<org.jooq.SortField<?>>");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("env", "condition", "orderBy");
    }

    @Test
    void selectMany_queriesTable() {
        var code = method("Film", "selectMany").code().toString();
        assertThat(code).contains("getDslContext()");
        assertThat(code).contains(".select(table.fields())");
        assertThat(code).contains(".from(table)");
        assertThat(code).contains(".where(condition)");
        assertThat(code).contains(".orderBy(orderBy)");
        assertThat(code).contains(".fetch()");
    }

    // ===== selectOne =====

    @Test
    void selectOne_isPublicStatic() {
        assertThat(method("Film", "selectOne").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectOne_returnType() {
        assertThat(method("Film", "selectOne").returnType().toString())
            .isEqualTo("org.jooq.Record");
    }

    @Test
    void selectOne_parameters() {
        var params = method("Film", "selectOne").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingEnvironment",
                "org.jooq.Condition");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("env", "condition");
    }

    // ===== subselectMany =====

    @Test
    void subselectMany_isPublicStatic() {
        assertThat(method("Film", "subselectMany").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void subselectMany_returnType() {
        assertThat(method("Film", "subselectMany").returnType().toString())
            .isEqualTo("org.jooq.Field<org.jooq.Result<org.jooq.Record>>");
    }

    @Test
    void subselectMany_parameters() {
        var params = method("Film", "subselectMany").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingFieldSelectionSet",
                "org.jooq.Condition",
                "java.util.List<org.jooq.SortField<?>>");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("sel", "condition", "orderBy");
    }

    // ===== subselectOne =====

    @Test
    void subselectOne_isPublicStatic() {
        assertThat(method("Film", "subselectOne").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void subselectOne_returnType() {
        assertThat(method("Film", "subselectOne").returnType().toString())
            .isEqualTo("org.jooq.Field<org.jooq.Record>");
    }

    @Test
    void subselectOne_parameters() {
        var params = method("Film", "subselectOne").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingFieldSelectionSet",
                "org.jooq.Condition");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("sel", "condition");
    }

    // ===== selectMany(List<? extends Row>, SelectedField, List<?>) overload (Row-keyed) =====

    @Test
    void selectManyFromRowService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Row").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectManyFromRowService_returnType() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Row").returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
    }

    @Test
    void selectManyFromRowService_parameters() {
        var params = methodByFirstParam("Film", "selectMany", "? extends org.jooq.Row").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<? extends org.jooq.Row>",
                "graphql.schema.SelectedField",
                "java.util.List<?>");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecords");
    }

    @Test
    void selectManyFromRowService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Row").code().toString())
            .contains("UnsupportedOperationException()");
    }

    // ===== selectOne(List<? extends Row>, SelectedField, Object) overload (Row-keyed) =====

    @Test
    void selectOneFromRowService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Row").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectOneFromRowService_returnType() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Row").returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record>");
    }

    @Test
    void selectOneFromRowService_parameters() {
        var params = methodByFirstParam("Film", "selectOne", "? extends org.jooq.Row").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<? extends org.jooq.Row>",
                "graphql.schema.SelectedField",
                "java.lang.Object");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecord");
    }

    @Test
    void selectOneFromRowService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Row").code().toString())
            .contains("UnsupportedOperationException()");
    }

    // ===== selectMany(List<? extends Record>, SelectedField, List<?>) overload (Record-keyed) =====

    @Test
    void selectManyFromRecordService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Record").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectManyFromRecordService_returnType() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Record").returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
    }

    @Test
    void selectManyFromRecordService_parameters() {
        var params = methodByFirstParam("Film", "selectMany", "? extends org.jooq.Record").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<? extends org.jooq.Record>",
                "graphql.schema.SelectedField",
                "java.util.List<?>");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecords");
    }

    @Test
    void selectManyFromRecordService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectMany", "? extends org.jooq.Record").code().toString())
            .contains("UnsupportedOperationException()");
    }

    // ===== selectOne(List<? extends Record>, SelectedField, Object) overload (Record-keyed) =====

    @Test
    void selectOneFromRecordService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Record").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectOneFromRecordService_returnType() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Record").returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record>");
    }

    @Test
    void selectOneFromRecordService_parameters() {
        var params = methodByFirstParam("Film", "selectOne", "? extends org.jooq.Record").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<? extends org.jooq.Record>",
                "graphql.schema.SelectedField",
                "java.lang.Object");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecord");
    }

    @Test
    void selectOneFromRecordService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectOne", "? extends org.jooq.Record").code().toString())
            .contains("UnsupportedOperationException()");
    }
}
