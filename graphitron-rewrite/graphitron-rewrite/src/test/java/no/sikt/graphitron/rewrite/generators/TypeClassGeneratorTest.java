package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TypeClassGenerator}. Tests verify structural properties of the generated
 * TypeSpec (method names, return types, parameter signatures) — not the generated code body.
 * Code correctness is verified by compiling the generated output against real jOOQ classes in
 * the {@code graphitron-rewrite-test} module.
 */
class TypeClassGeneratorTest {

    private static final List<ChildField.ColumnField> FILM_COLUMNS = List.of(
        TestFixtures.columnField("Film", "title", "title", "TITLE", "java.lang.String"),
        TestFixtures.columnField("Film", "filmId", "film_id", "FILM_ID", "java.lang.Integer")
    );

    private static TypeSpec spec() {
        return TypeClassGenerator.buildTypeSpec("Film",
            filmTable(List.of(col("id", "ID", "java.lang.Integer"))),
            FILM_COLUMNS,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
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

    // ===== NodeId fields =====

    @Test
    void $fields_nodeIdField_producesSwitchArm() {
        var spec = TypeClassGenerator.buildTypeSpec("Film",
            filmTable(),
            List.of(),
            List.of(nodeIdField("Film", "id", "Film", List.of(filmIdCol()))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        assertThat(TypeSpecAssertions.hasFieldsArm(spec, "id")).isTrue();
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
