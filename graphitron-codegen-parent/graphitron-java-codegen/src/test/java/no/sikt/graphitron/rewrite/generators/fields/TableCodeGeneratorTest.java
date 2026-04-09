package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class TableCodeGeneratorTest {

    private static final TableCodeGenerator GEN = new TableCodeGenerator();

    private static TypeSpec spec(String tableName) {
        return GEN.generate(tableName);
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

    // ===== All six methods present =====

    @Test
    void generate_allSixMethodsArePresent() {
        assertThat(spec("Film").methodSpecs()).hasSize(6);
        assertThat(spec("Film").methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder("selectMany", "selectOne", "selectMany", "selectOne", "subselectMany", "subselectOne");
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
    void selectMany_throwsUnsupportedOperationException() {
        assertThat(method("Film", "selectMany").code().toString())
            .contains("UnsupportedOperationException()");
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

    // ===== selectMany(List<Row>, SelectedField, List<?>) overload =====

    @Test
    void selectManyFromService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectMany", "Row").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectManyFromService_returnType() {
        assertThat(methodByFirstParam("Film", "selectMany", "Row").returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
    }

    @Test
    void selectManyFromService_parameters() {
        var params = methodByFirstParam("Film", "selectMany", "Row").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<org.jooq.Row>",
                "graphql.schema.SelectedField",
                "java.util.List<?>");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecords");
    }

    @Test
    void selectManyFromService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectMany", "Row").code().toString())
            .contains("UnsupportedOperationException()");
    }

    // ===== selectOne(List<Row>, SelectedField, Object) overload =====

    @Test
    void selectOneFromService_isPublicStatic() {
        assertThat(methodByFirstParam("Film", "selectOne", "Row").modifiers())
            .containsExactlyInAnyOrder(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void selectOneFromService_returnType() {
        assertThat(methodByFirstParam("Film", "selectOne", "Row").returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record>");
    }

    @Test
    void selectOneFromService_parameters() {
        var params = methodByFirstParam("Film", "selectOne", "Row").parameters();
        assertThat(params).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<org.jooq.Row>",
                "graphql.schema.SelectedField",
                "java.lang.Object");
        assertThat(params).extracting(p -> p.name())
            .containsExactly("keys", "sel", "serviceRecord");
    }

    @Test
    void selectOneFromService_throwsUnsupportedOperationException() {
        assertThat(methodByFirstParam("Film", "selectOne", "Row").code().toString())
            .contains("UnsupportedOperationException()");
    }
}
