package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.SelectionOccurrencesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Field;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projection unit emitted from the store's facts, compiled and run against PostgreSQL: each
 * response key is read the way its field's reader reads it, a column and a node id's key column by
 * the column's identity, so the values a fetcher would hand back are the film row's.
 */
@ExecutionTier
class ProjectionEmitterExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "projections";
    private static final no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film FILM = Tables.FILM;

    private static Map<String, ProjectionQuery.Unit> units;
    private static EmittedCodeHarness harness;
    private static Method project;

    @BeforeAll
    static void emit() throws Exception {
        units = FACTS.read("scenarios/projection-unit", graph -> ProjectionQuery.read(graph.handle()));
        var film = (ProjectionEmitter.Result.Emitted) ProjectionEmitter.emit(units.get("Film"), PACKAGE);
        var compiled = new HashMap<String, TypeSpec>();
        compiled.put(PACKAGE + ".Film", film.unit());
        for (TypeSpec spec : SelectionOccurrencesClassGenerator.generate(PACKAGE)) {
            compiled.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            compiled.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(compiled);
        project = harness.load(PACKAGE + ".Film").getMethod("$project", Map.class, FILM.getClass(),
            DataFetchingEnvironment.class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    @Test
    @DisplayName("a film's title, node id key and nested facts are read off its row by their readers")
    void columnsNodeIdAndANestingField() throws Exception {
        var row = film(1, grouped(
            field("id"),
            field("title"),
            field("facts", field("length"), field("releaseYear"))));
        var expected = TestDatabase.dsl().selectFrom(FILM).where(FILM.FILM_ID.eq(1)).fetchOne();
        assertThat(row.get(FILM.FILM_ID)).as("id's key column").isEqualTo(1);
        assertThat(row.get(FILM.TITLE)).as("title").isEqualTo(expected.getTitle());
        assertThat(row.get(FILM.LENGTH)).as("facts.length").isEqualTo(expected.getLength());
        assertThat(row.get(FILM.RELEASE_YEAR)).as("facts.releaseYear").isEqualTo(expected.getReleaseYear());
    }

    @Test
    @DisplayName("a field selected under two response keys projects its column once")
    void aliasedTwice() throws Exception {
        var fields = fields(grouped(field("title"), aliased("again", "title")));
        assertThat(fields).containsExactly(FILM.TITLE);
    }

    @Test
    @DisplayName("a selection projecting no column still selects one, the row-present sentinel")
    void nothingSelected() throws Exception {
        var fields = fields(grouped(field("__typename")));
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getName()).isEqualTo("__row_present__");
    }

    @Test
    @DisplayName("a nullable nesting field keeps its unit back, as the null contract it waits on")
    void aNullableNestingFieldIsNotYet() {
        assertThat(ProjectionEmitter.emit(units.get("Language"), PACKAGE))
            .isEqualTo(new ProjectionEmitter.Result.NotYet(List.of(
                new ProjectionEmitter.Refusal("Language.spoken", ProjectionEmitter.Reason.NULL_CONTRACT))));
    }

    @Test
    @DisplayName("a node's nullable nesting field named like a daterange column keeps its unit back")
    void aNodesNestingFieldNamedLikeARange() {
        var region = FACTS.read("scenarios/node-nesting-field-named-like-a-range",
            graph -> ProjectionQuery.read(graph.handle()).get("Region"));
        assertThat(ProjectionEmitter.emit(region, PACKAGE))
            .isEqualTo(new ProjectionEmitter.Result.NotYet(List.of(
                new ProjectionEmitter.Refusal("Region.validPeriod", ProjectionEmitter.Reason.NULL_CONTRACT))));
    }

    @SuppressWarnings("unchecked")
    private static List<Field<?>> fields(Map<String, List<SelectedField>> grouped) throws Exception {
        return (List<Field<?>>) project.invoke(null, grouped, FILM, null);
    }

    private static Record film(int id, Map<String, List<SelectedField>> grouped) throws Exception {
        return TestDatabase.dsl().select(fields(grouped)).from(FILM).where(FILM.FILM_ID.eq(id)).fetchOne();
    }

    private static Map<String, List<SelectedField>> grouped(SelectedField... fields) {
        var grouped = new LinkedHashMap<String, List<SelectedField>>();
        for (var f : fields) grouped.computeIfAbsent(f.getResultKey(), k -> new java.util.ArrayList<>()).add(f);
        return grouped;
    }

    private static SelectedField field(String name, SelectedField... children) {
        return aliased(name, name, children);
    }

    /** A selected field under {@code resultKey}, with its immediate children, as the selection adapters read one. */
    private static SelectedField aliased(String resultKey, String name, SelectedField... children) {
        var selectionSet = (DataFetchingFieldSelectionSet) Proxy.newProxyInstance(
            ProjectionEmitterExecutionTest.class.getClassLoader(), new Class<?>[] {DataFetchingFieldSelectionSet.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getImmediateFields" -> List.of(children);
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return (SelectedField) Proxy.newProxyInstance(ProjectionEmitterExecutionTest.class.getClassLoader(),
            new Class<?>[] {SelectedField.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> name;
                case "getResultKey" -> resultKey;
                case "getSelectionSet" -> selectionSet;
                case "getArguments" -> Map.of();
                case "toString" -> resultKey + ":" + name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
