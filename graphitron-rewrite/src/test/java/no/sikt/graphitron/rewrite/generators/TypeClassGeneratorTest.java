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
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
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
            FILM_COLUMNS,
            List.of(),
            List.of());
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
    void generate_allMethodsArePresent() {
        assertThat(spec().methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("$fields");
    }

    // ===== Platform-id fields =====

    @Test
    void $fields_containsPlatformIdMethodCall() {
        var spec = TypeClassGenerator.buildTypeSpec("Film",
            filmTable(),
            List.of(),
            List.of(platformIdField("Film", "id", "getId")),
            List.of());
        var code = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow()
            .code().toString();
        assertThat(code).contains("case \"id\"");
        assertThat(code).contains("table.getId()");
    }

    @Test
    void $fields_platformIdUsesMethodCallNotFieldAccess() {
        var spec = TypeClassGenerator.buildTypeSpec("Film",
            filmTable(),
            List.of(),
            List.of(platformIdField("Film", "personId", "getPersonId")),
            List.of());
        var code = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow()
            .code().toString();
        assertThat(code).contains("table.getPersonId()");
        assertThat(code).doesNotContain("table.PERSONID");
    }

    // ===== Signatures =====

    @Test
    void $fields_signature() {
        var m = method("$fields");
        assertThat(m.modifiers()).contains(
            javax.lang.model.element.Modifier.PUBLIC,
            javax.lang.model.element.Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingFieldSelectionSet",
                DEFAULT_JOOQ_PACKAGE + ".tables.Film",
                "graphql.schema.DataFetchingEnvironment");
    }
}
