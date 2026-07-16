package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier falsifiability for {@link MethodCommandRegistry}: the fact-derived family boundary
 * (Record-sourced arm commits, Table-sourced arm passes through the same seam uncommitted), the
 * name authority (the committed name is the model's regime-1 naming fact, returned to the
 * declaring caller), and the exactly-one guard (a second claim on the same emitted method
 * throws). The run-level joins live in {@link ReentryCommandClosureTest}.
 */
@UnitTier
class MethodCommandRegistryTest {

    private static final TableRef FILM_TABLE = TestFixtures.tableRef("film", "FILM", "Film", List.of());
    private static final List<ColumnRef> KEY_COLS =
        List.of(new ColumnRef("language_id", "LANGUAGE_ID", "java.lang.Integer"));
    private static final ReturnTypeRef.TableBoundReturnType RT_SINGLE =
        new ReturnTypeRef.TableBoundReturnType("Film", FILM_TABLE, new FieldWrapper.Single(true));

    private static ChildField.BatchedTableField recordSourced(String parentType, String name) {
        return new ChildField.BatchedTableField(parentType, name, null, RT_SINGLE,
            List.of(), List.of(), new OrderBySpec.None(), null,
            SourceShape.Record, TestFixtures.recordParentRowSourceKey(KEY_COLS),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(RT_SINGLE, false, false),
            /* parentCorrelation */ null);
    }

    private static ChildField.BatchedTableField tableSourced(String parentType, String name) {
        return new ChildField.BatchedTableField(parentType, name, null, RT_SINGLE,
            List.of(), List.of(), new OrderBySpec.None(), null,
            SourceShape.Table, TestFixtures.splitSourceKey(KEY_COLS),
            TestFixtures.fkColumnsLift(),
            TestFixtures.loaderRegistration(RT_SINGLE, false, false),
            /* parentCorrelation */ null);
    }

    @Test
    void recordSourcedDeclarationCommitsWithTheModelName() {
        var registry = new MethodCommandRegistry();
        var field = recordSourced("FilmPayload", "film");

        String name = registry.declareReentryRowsMethod(field, "com.example.fetchers.FilmPayloadFetchers");

        assertThat(name).as("declaration name is the model's naming fact").isEqualTo("rowsFilm");
        assertThat(registry.committed()).containsExactly(new MethodCommand(
            "FilmPayload.film", "com.example.fetchers.FilmPayloadFetchers", "", "rowsFilm"));
    }

    @Test
    void tableSourcedDeclarationPassesThroughUncommitted() {
        var registry = new MethodCommandRegistry();
        var field = tableSourced("Language", "films");

        String name = registry.declareReentryRowsMethod(field, "com.example.fetchers.LanguageFetchers");

        assertThat(name).isEqualTo("rowsFilms");
        assertThat(registry.committed())
            .as("the Table-sourced @splitQuery arm is not reentry; same seam, no command")
            .isEmpty();
    }

    @Test
    void secondClaimOnTheSameEmittedMethodThrows() {
        var registry = new MethodCommandRegistry();
        registry.declareReentryRowsMethod(recordSourced("FilmPayload", "film"), "com.example.fetchers.FilmPayloadFetchers");

        assertThatThrownBy(() -> registry.declareReentryRowsMethod(
                recordSourced("FilmPayload", "film"), "com.example.fetchers.FilmPayloadFetchers"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("two commands claim emitted method")
            .hasMessageContaining("com.example.fetchers.FilmPayloadFetchers#rowsFilm");
    }

    @Test
    void sameMethodNameInDifferentUnitsIsTwoCommands() {
        var registry = new MethodCommandRegistry();
        registry.declareReentryRowsMethod(recordSourced("FilmPayload", "film"), "com.example.fetchers.FilmPayloadFetchers");
        registry.declareReentryRowsMethod(recordSourced("OtherPayload", "film"), "com.example.fetchers.OtherPayloadFetchers");

        assertThat(registry.committed()).hasSize(2);
    }
}
