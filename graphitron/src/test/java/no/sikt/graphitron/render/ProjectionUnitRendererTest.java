package no.sikt.graphitron.render;

import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.command.TermAlias;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.model.AliasOwner;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.rewrite.TestFixtures.col;
import static no.sikt.graphitron.rewrite.TestFixtures.filmTable;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-arm unit tests for {@link ProjectionUnitRenderer}: a total function whose inputs are
 * record literals, needing no schema, fixture, or catalog plumbing. Structural properties only
 * (method names, signatures, arm presence); code correctness is verified by compiling the
 * generated output against real jOOQ classes in {@code graphitron-sakila-example}, and SQL
 * behaviour by the execution tier.
 */
@UnitTier
class ProjectionUnitRendererTest {

    private static final GeneratedUnits UNITS = new GeneratedUnits(DEFAULT_OUTPUT_PACKAGE);

    private static ProjectionCommand.AnchorUnit filmRow(List<Contribution> contributions) {
        return new ProjectionCommand.AnchorUnit(
            UNITS.typeClass("Film"),
            filmTable(List.of(col("id", "ID", "java.lang.Integer"))),
            contributions);
    }

    private static TypeSpec render(ProjectionCommand row) {
        return ProjectionUnitRenderer.render(List.of(row), DEFAULT_OUTPUT_PACKAGE).get(0);
    }

    private static MethodSpec projectMethod(TypeSpec spec) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(ProjectionCall.METHOD_NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no $project method rendered"));
    }

    @Test
    void anchorUnit_classNameIsTheUnitsSimpleName() {
        assertThat(render(filmRow(List.of())).name()).isEqualTo("Film");
    }

    @Test
    void anchorUnit_rendersExactlyOneProjectMethod() {
        // One method per projection unit: grouped selection in, select list out. The two retired
        // public overloads' adapters now compose at call sites (ProjectionCall).
        assertThat(render(filmRow(List.of())).methodSpecs()).extracting(MethodSpec::name)
            .containsExactly("$project");
    }

    @Test
    void projectSignature_groupedSelectionTableEnv() {
        var m = projectMethod(render(filmRow(List.of())));
        assertThat(m.modifiers()).contains(
            javax.lang.model.element.Modifier.PUBLIC,
            javax.lang.model.element.Modifier.STATIC);
        assertThat(m.returnType().toString()).isEqualTo("java.util.List<org.jooq.Field<?>>");
        assertThat(m.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.Map<java.lang.String, java.util.List<graphql.schema.SelectedField>>",
                DEFAULT_JOOQ_PACKAGE + ".tables.Film",
                "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void tableContextBody_emptyAccumulationFallsBackToTheRowPresentSentinel() {
        // Every contribution is selection-gated, so a selection projecting nothing yields an
        // empty set; handing jOOQ an empty select list would project every known column. The
        // body answers it like the pivot body answers a slot-less selection: one inline
        // sentinel, deterministic and one-column.
        var body = projectMethod(render(filmRow(List.of()))).code().toString();
        assertThat(body).contains("if (fields.isEmpty())");
        assertThat(body).contains(".as(\"__row_present__\")");
    }

    @Test
    void compositeColumnProject_rendersOneArmAddingEveryKeyColumn() {
        var row = filmRow(List.of(new Contribution.Project("id", List.of(
            new SelectTerm.Column(col("id_1", "ID_1", "java.lang.Integer"), TermAlias.BY_COLUMN_IDENTITY),
            new SelectTerm.Column(col("id_2", "ID_2", "java.lang.Integer"), TermAlias.BY_COLUMN_IDENTITY)),
            AliasOwner.shared())));
        var body = projectMethod(render(row)).code().toString();
        assertThat(body).contains("case \"id\" ->");
        assertThat(body).contains("fields.add(table.ID_1)");
        assertThat(body).contains("fields.add(table.ID_2)");
    }

    @Test
    void resultKeyAliasedColumn_rendersTheStandaloneReferenceAlias() {
        // The one inherited reader-uniformity alias the term slot keeps representable: a
        // standalone reference projects the unit's own column aliased by result key.
        var row = filmRow(List.of(new Contribution.Project("original", List.of(
            new SelectTerm.Column(col("original_id", "ORIGINAL_ID", "java.lang.Integer"),
                TermAlias.BY_RESULT_KEY)),
            AliasOwner.shared())));
        var body = projectMethod(render(row)).code().toString();
        assertThat(body).contains("table.ORIGINAL_ID.as(\"__rk_\" + entry.getKey())");
    }

    @Test
    void nestedUnit_takesTheAnchorsTableClass() {
        // A nesting unit shares the anchor's table context by definition; the anchor fixes the
        // emitted parameter type.
        var row = new ProjectionCommand.NestedUnit(
            UNITS.nestingUnit("Film", "FilmDetails"),
            filmTable(List.of(col("id", "ID", "java.lang.Integer"))),
            List.of(new Contribution.Project("title", List.of(
                new SelectTerm.Column(col("title", "TITLE", "java.lang.String"),
                    TermAlias.BY_COLUMN_IDENTITY)),
                AliasOwner.shared())));
        var spec = render(row);
        assertThat(spec.name()).isEqualTo("FilmFilmDetails");
        assertThat(projectMethod(spec).parameters()).extracting(p -> p.type().toString())
            .contains(DEFAULT_JOOQ_PACKAGE + ".tables.Film");
    }

    @Test
    void pivotUnit_dedupesSlotsByNameAndCarriesTheSentinel() {
        var row = new ProjectionCommand.PivotUnit(
            UNITS.pivotUnit("Film", "titleTexts"),
            TestFixtures.filmTable(),
            List.of(new Contribution.Project("nn", List.of(
                new SelectTerm.Aggregate(
                    col("title_txt", "TITLE_TXT", "java.lang.String"),
                    col("lang_code", "LANG_CODE", "java.lang.String"),
                    "nn", "nn")),
                AliasOwner.shared())));
        var spec = render(row);
        assertThat(spec.name()).isEqualTo("FilmTitleTexts");
        var body = projectMethod(spec).code().toString();
        assertThat(body)
            .as("selected slots dedupe by name before the switch (one projected column serves "
                + "every alias of a slot)")
            .contains("slots.add(occ.get(0).getName())");
        assertThat(body).contains("max(table.TITLE_TXT)");
        assertThat(body).contains(".filterWhere(table.LANG_CODE.eq(");
        assertThat(body).contains("inline(\"nn\"))).as(\"nn\")");
        assertThat(body)
            .as("an introspection-only selection appends the one-record sentinel")
            .contains("__pivot_present__");
    }
}
