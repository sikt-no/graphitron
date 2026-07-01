package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R255 regression coverage: a composite {@code @node(keyColumns: [...])} type with a sibling
 * {@code @field} that overlaps one of the key columns classifies into two emit arms whose
 * projections collide on the same column.
 *
 * <p>Pre-fix the generated {@code $fields} method appended the column twice and jOOQ's
 * {@code FieldsImpl.indexOf} logged an INFO "Ambiguous match" on every fetched row. The fix is
 * a {@link java.util.LinkedHashSet} accumulator that dedupes by jOOQ {@code Field} identity at
 * runtime. This test pins the structural contract: the classifier surfaces both overlapping
 * arms, and the generator emits a case arm for each. Runtime dedupe is asserted at the
 * execution tier; see {@code graphitron-sakila-example}.
 */
@PipelineTier
class DedupeReferenceProjectionPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    private static final String OVERLAP_SDL = """
        type Foo implements Node @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_1", "id_2"]) {
            id: ID! @nodeId
            overlappingKey: String @field(name: "id_1")
            name: String
        }
        type Query { foos: [Foo!]! }
        """;

    @Test
    void compositeNodeIdAndSiblingColumnField_classifyAsSeparateOverlappingArms() {
        var schema = TestSchemaHelper.buildSchema(OVERLAP_SDL, FIXTURE_CTX);

        var idField = schema.fieldsOf("Foo").stream()
            .filter(f -> f.name().equals("id"))
            .findFirst().orElseThrow();
        var overlapField = schema.fieldsOf("Foo").stream()
            .filter(f -> f.name().equals("overlappingKey"))
            .findFirst().orElseThrow();

        assertThat(idField)
            .as("composite-key @nodeId surfaces as CompositeColumnField")
            .isInstanceOf(ChildField.CompositeColumnField.class);
        assertThat(((ChildField.CompositeColumnField) idField).columns())
            .extracting(ColumnRef::sqlName)
            .containsExactly("id_1", "id_2");

        assertThat(overlapField)
            .as("sibling @field on a key column surfaces as ColumnField on the same column")
            .isInstanceOf(ChildField.ColumnField.class);
        assertThat(((ChildField.ColumnField) overlapField).column().sqlName())
            .isEqualTo("id_1");
    }

    @Test
    void generated$fieldsEmitsCaseArmForBothOverlappingFields() {
        var schema = TestSchemaHelper.buildSchema(OVERLAP_SDL, FIXTURE_CTX);
        var fooClass = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Foo"))
            .findFirst().orElseThrow();

        assertThat(TypeSpecAssertions.hasFieldsArm(fooClass, "id"))
            .as("Foo.$fields must contain a case arm for the composite @nodeId field")
            .isTrue();
        assertThat(TypeSpecAssertions.hasFieldsArm(fooClass, "overlappingKey"))
            .as("Foo.$fields must contain a case arm for the sibling overlapping @field")
            .isTrue();
    }
}
