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

    private static final List<TableCodeGenerator.ScalarColumn> FILM_COLUMNS = List.of(
        new TableCodeGenerator.ScalarColumn("title", "TITLE"),
        new TableCodeGenerator.ScalarColumn("filmId", "FILM_ID")
    );

    private static TypeSpec spec() {
        return GEN.generate(
            new TableRef("film", "FILM", "Film",
                List.of(new ColumnRef("id", "ID", "java.lang.Integer"))),
            FILM_COLUMNS);
    }

    private static MethodSpec method(String methodName) {
        return spec().methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    private static MethodSpec methodByFirstParam(String methodName, String firstParamHint) {
        return spec().methodSpecs().stream()
            .filter(m -> m.name().equals(methodName)
                && !m.parameters().isEmpty()
                && m.parameters().get(0).type().toString().contains(firstParamHint))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Method not found: " + methodName + " with first param containing '" + firstParamHint + "'"));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameMatchesTableName() {
        assertThat(spec().name()).isEqualTo("Film");
    }

    @Test
    void generate_allNineMethodsArePresent() {
        assertThat(spec().methodSpecs()).hasSize(9);
        assertThat(spec().methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder(
                "fields",
                "selectMany", "selectOne",
                "selectManyByRowKeys", "selectOneByRowKeys",
                "selectManyByRecordKeys", "selectOneByRecordKeys",
                "subselectMany", "subselectOne");
    }

    // ===== fields() =====

    @Test
    void fields_signature() {
        var m = method("fields");
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingFieldSelectionSet");
    }

    @Test
    void fields_iteratesFieldsGroupedByResultKey() {
        var code = method("fields").code().toString();
        assertThat(code).contains("sel.getFieldsGroupedByResultKey()");
        assertThat(code).contains("sf.getName()");
    }

    @Test
    void fields_matchesColumnsBySchemaFieldName() {
        var code = method("fields").code().toString();
        assertThat(code).contains("case \"title\" -> fields.add(table.TITLE)");
        assertThat(code).contains("case \"filmId\" -> fields.add(table.FILM_ID)");
    }

    @Test
    void fields_hasDefaultBranch() {
        assertThat(method("fields").code().toString()).contains("default -> { }");
    }

    // ===== selectMany (root) =====

    @Test
    void selectMany_signature() {
        var m = method("selectMany");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "condition", "orderBy");
    }

    @Test
    void selectMany_body() {
        var code = method("selectMany").code().toString();
        assertThat(code).contains("getDslContext(env)");
        assertThat(code).contains(".select(fields(env.getSelectionSet()))");
        assertThat(code).contains(".from(table)");
        assertThat(code).contains(".where(condition)");
        assertThat(code).contains(".orderBy(orderBy)");
        assertThat(code).contains(".fetch()");
    }

    // ===== selectOne (root) =====

    @Test
    void selectOne_signature() {
        var m = method("selectOne");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "condition");
    }

    // ===== subselectMany =====

    @Test
    void subselectMany_signature() {
        var m = method("subselectMany");
        assertThat(m.returnType().toString())
            .isEqualTo("org.jooq.Field<org.jooq.Result<org.jooq.Record>>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "sel", "condition", "orderBy");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .contains("graphql.schema.SelectedField");
    }

    // ===== subselectOne =====

    @Test
    void subselectOne_signature() {
        var m = method("subselectOne");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Field<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "sel", "condition");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .contains("graphql.schema.SelectedField");
    }

    // ===== Service overloads (stubs — verify signatures only) =====

    @Test
    void selectManyByRowKeys_signature() {
        var m = method("selectManyByRowKeys");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "env", "sel", "serviceRecords");
    }

    @Test
    void selectOneByRowKeys_signature() {
        var m = method("selectOneByRowKeys");
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "env", "sel", "serviceRecord");
    }

    @Test
    void selectManyByRecordKeys_signature() {
        var m = method("selectManyByRecordKeys");
        assertThat(m.returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "env", "sel", "serviceRecords");
    }

    @Test
    void selectOneByRecordKeys_signature() {
        var m = method("selectOneByRecordKeys");
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("keys", "env", "sel", "serviceRecord");
    }
}
