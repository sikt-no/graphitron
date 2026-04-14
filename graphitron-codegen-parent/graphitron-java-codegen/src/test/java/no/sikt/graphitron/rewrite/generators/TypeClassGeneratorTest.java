package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TypeClassGenerator}. Tests verify structural properties of the generated
 * TypeSpec (method names, return types, parameter signatures) — not the generated code body.
 * Code correctness is verified by compiling the generated output against real jOOQ classes in
 * the {@code graphitron-rewrite-test-spec} module.
 */
class TypeClassGeneratorTest {

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

    private static final List<ChildField.ColumnField> FILM_COLUMNS = List.of(
        new ChildField.ColumnField("Film", "title", null, "title",
            new ColumnRef("title", "TITLE", "java.lang.String"), false),
        new ChildField.ColumnField("Film", "filmId", null, "film_id",
            new ColumnRef("film_id", "FILM_ID", "java.lang.Integer"), false)
    );

    private static TypeSpec spec() {
        return TypeClassGenerator.buildTypeSpec("Film",
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

    // ===== Class structure =====

    @Test
    void generate_classNameMatchesTableName() {
        assertThat(spec().name()).isEqualTo("Film");
    }

    @Test
    void generate_allNineMethodsArePresent() {
        assertThat(spec().methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder(
                "fields",
                "selectMany", "selectOne",
                "selectManyByRowKeys", "selectOneByRowKeys",
                "selectManyByRecordKeys", "selectOneByRecordKeys",
                "subselectMany", "subselectOne");
    }

    // ===== Signatures =====

    @Test
    void fields_signature() {
        var m = method("fields");
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingFieldSelectionSet");
    }

    @Test
    void selectMany_signature() {
        var m = method("selectMany");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Result<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "condition", "orderBy");
    }

    @Test
    void selectOne_signature() {
        var m = method("selectOne");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Record");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "condition");
    }

    @Test
    void subselectMany_signature() {
        var m = method("subselectMany");
        assertThat(m.returnType().toString())
            .isEqualTo("org.jooq.Field<org.jooq.Result<org.jooq.Record>>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "sel", "condition", "orderBy");
    }

    @Test
    void subselectOne_signature() {
        var m = method("subselectOne");
        assertThat(m.returnType().toString()).isEqualTo("org.jooq.Field<org.jooq.Record>");
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("env", "sel", "condition");
    }

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
