package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.RewriteConfig;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.ChildField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.*;
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
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE);
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    private static final List<ChildField.ColumnField> FILM_COLUMNS = List.of(
        TestFixtures.columnField("Film", "title", "title", "TITLE", "java.lang.String"),
        TestFixtures.columnField("Film", "filmId", "film_id", "FILM_ID", "java.lang.Integer")
    );

    private static TypeSpec spec() {
        return TypeClassGenerator.buildTypeSpec("Film",
            filmTable(List.of(col("id", "ID", "java.lang.Integer"))),
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

    /**
     * {@code fields()} takes exactly one parameter — the selection set — not a table instance.
     *
     * <p>This is intentional for the MULTISET correlated subquery strategy: each call to
     * {@code subselectMany} or {@code subselectOne} creates its own SQL scope, so calling the
     * same type method twice (e.g. {@code Actor.subselectMany} for both {@code leadMaleActor}
     * and {@code leadFemaleActor} on {@code Film}) does NOT produce alias collisions — each
     * subquery is independent.
     *
     * <p>For flat batch JOINs (DataLoader), the table would need to be aliased per field
     * to avoid duplicate aliases in the shared SELECT. In that case {@code fields()} would
     * need to accept the aliased table instance. That extension is deferred to the flat-JOIN
     * generation phase; the single-parameter signature here documents the current safe design.
     */
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
