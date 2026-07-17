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
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit tests for {@link TypeClassGenerator}. Tests verify structural properties of the generated
 * TypeSpec (method names, return types, parameter signatures) — not the generated code body.
 * Code correctness is verified by compiling the generated output against real jOOQ classes in
 * the {@code graphitron-sakila-example} module.
 */
@UnitTier
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
            List.of(),
            new TypeClassGenerator.RequiredProjection(false, List.of()),
            DEFAULT_OUTPUT_PACKAGE);
    }

    private static MethodSpec method(String methodName) {
        return spec().methodSpecs().stream()
            .filter(m -> m.name().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method not found: " + methodName));
    }

    private static MethodSpec fieldsOverload(String firstParamType) {
        return spec().methodSpecs().stream()
            .filter(m -> m.name().equals("$fields"))
            .filter(m -> m.parameters().get(0).type().toString().equals(firstParamType))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No $fields overload taking " + firstParamType));
    }

    // ===== Class structure =====

    @Test
    void generate_classNameMatchesTableName() {
        assertThat(spec().name()).isEqualTo("Film");
    }

    @Test
    void generate_allMethodsArePresent() {
        // Two public $fields entries (selection-set and occurrence-list) delegating into the
        // private $fieldsGrouped switch loop.
        assertThat(spec().methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("$fields", "$fields", "$fieldsGrouped");
    }

    // ===== NodeId fields (composite arity > 1; arity-1 lands on ColumnField switch arm) =====

    @Test
    void $fields_compositeNodeIdField_producesSwitchArm() {
        var idCol1 = col("id_1", "ID_1", "java.lang.Integer");
        var idCol2 = col("id_2", "ID_2", "java.lang.Integer");
        var spec = TypeClassGenerator.buildTypeSpec("Bar",
            filmTable(),
            List.of(),
            List.of(compositeNodeIdField("Bar", "id", "Bar", List.of(idCol1, idCol2))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new TypeClassGenerator.RequiredProjection(false, List.of()),
            DEFAULT_OUTPUT_PACKAGE);
        assertThat(TypeSpecAssertions.hasFieldsArm(spec, "id")).isTrue();
    }

    // ===== Signatures =====

    @Test
    void $fields_selectionSetEntrySignature() {
        var m = fieldsOverload("graphql.schema.DataFetchingFieldSelectionSet");
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

    @Test
    void $fields_occurrenceListOverloadSignature() {
        // The occurrence-list overload the inline arms descend through with the full result-key
        // bucket, so nested projections union every occurrence's sub-selection.
        var m = fieldsOverload("java.util.List<graphql.schema.SelectedField>");
        assertThat(m.modifiers()).contains(
            javax.lang.model.element.Modifier.PUBLIC,
            javax.lang.model.element.Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<graphql.schema.SelectedField>",
                DEFAULT_JOOQ_PACKAGE + ".tables.Film",
                "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void $fieldsGrouped_isThePrivateSwitchLoopBothEntriesDelegateTo() {
        var m = method("$fieldsGrouped");
        assertThat(m.modifiers()).contains(
            javax.lang.model.element.Modifier.PRIVATE,
            javax.lang.model.element.Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.Map<java.lang.String, java.util.List<graphql.schema.SelectedField>>",
                DEFAULT_JOOQ_PACKAGE + ".tables.Film",
                "graphql.schema.DataFetchingEnvironment");
    }
}
