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
    private static Method projectCategory;
    private static final String CHOOSING = "choosing";
    private static EmittedCodeHarness choosingHarness;
    private static Method projectChoosingCategory;

    @BeforeAll
    static void emit() throws Exception {
        units = FACTS.read("scenarios/projection-unit", graph -> ProjectionQuery.read(graph.handle()));
        var emitted = ProjectionEmitter.emitAll(units, PACKAGE);
        var compiled = new HashMap<String, TypeSpec>();
        for (var type : List.of("Film", "Category")) {
            compiled.put(PACKAGE + "." + type, ((ProjectionEmitter.Result.Emitted) emitted.get(type)).unit());
        }
        for (TypeSpec spec : SelectionOccurrencesClassGenerator.generate(PACKAGE)) {
            compiled.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            compiled.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(compiled);
        project = harness.load(PACKAGE + ".Film").getMethod("$project", Map.class, FILM.getClass(),
            DataFetchingEnvironment.class);
        projectCategory = harness.load(PACKAGE + ".Category").getMethod("$project", Map.class,
            Tables.CATEGORY.getClass(), DataFetchingEnvironment.class);

        // The choice is refused until the store's units replace the generated ones, so its schema
        // is a defect document's, read and emitted beside the scenario's.
        var choosing = ProjectionEmitter.emitAll(
            FACTS.read("defects/inline-list-choice", graph -> ProjectionQuery.read(graph.handle())), CHOOSING);
        var chosen = new HashMap<String, TypeSpec>();
        for (var type : List.of("Film", "Category")) {
            chosen.put(CHOOSING + "." + type, ((ProjectionEmitter.Result.Emitted) choosing.get(type)).unit());
        }
        for (TypeSpec spec : SelectionOccurrencesClassGenerator.generate(CHOOSING)) {
            chosen.put(CHOOSING + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            chosen.put(CHOOSING + ".schema." + spec.name(), spec);
        }
        choosingHarness = EmittedCodeHarness.compileStrictly(chosen);
        projectChoosingCategory = choosingHarness.load(CHOOSING + ".Category").getMethod("$project", Map.class,
            Tables.CATEGORY.getClass(), DataFetchingEnvironment.class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
        if (choosingHarness != null) choosingHarness.close();
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
    @DisplayName("a column down a path is read by the name its reader composes, within a nesting field's key below it")
    void aColumnDownAPath() throws Exception {
        var row = film(1, grouped(
            field("languageName"),
            aliased("spoken", "languageName"),
            field("facts", field("languageName"))));
        var language = TestDatabase.dsl().select(Tables.LANGUAGE.NAME).from(Tables.LANGUAGE)
            .join(FILM).on(FILM.LANGUAGE_ID.eq(Tables.LANGUAGE.LANGUAGE_ID)).where(FILM.FILM_ID.eq(1))
            .fetchOne(Tables.LANGUAGE.NAME);
        assertThat(row.get("__rk_languageName")).as("languageName").isEqualTo(language);
        assertThat(row.get("__rk_spoken")).as("languageName aliased spoken").isEqualTo(language);
        assertThat(row.get("__rk_facts/languageName")).as("facts.languageName").isEqualTo(language);
    }

    @Test
    @DisplayName("a split's keys are read off the film row by the parent table's fields, as its loader reads them")
    void aSplitsKeys() throws Exception {
        var row = film(1, grouped(field("languageSplit"), field("actors")));
        var expected = TestDatabase.dsl().selectFrom(FILM).where(FILM.FILM_ID.eq(1)).fetchOne();
        assertThat(row.get(FILM.LANGUAGE_ID)).as("languageSplit's key, the language's id the film holds")
            .isEqualTo(expected.getLanguageId());
        assertThat(row.get(FILM.FILM_ID)).as("actors' key, the film's own").isEqualTo(1);
    }

    @Test
    @DisplayName("a discriminated participant names an interface's field for the interface and its own for itself")
    void aliasOwners() {
        var owners = FACTS.read("scenarios/projection-unit", graph -> {
            var read = ProjectionQuery.read(graph.handle());
            var byCoordinate = new java.util.TreeMap<String, String>();
            for (var type : List.of("FilmItem", "ShortItem", "Film")) {
                for (var arm : read.get(type).arms()) byCoordinate.put(arm.coordinate(), String.valueOf(arm.aliasOwner()));
            }
            return byCoordinate;
        });
        assertThat(owners)
            .containsEntry("FilmItem.filmDescription", "Item")
            .containsEntry("ShortItem.filmDescription", "Item")
            .containsEntry("FilmItem.filmReleaseYear", "FilmItem")
            .containsEntry("Film.languageName", "null");
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

    @Test
    @DisplayName("a list read inline is its films in the order written, completed with the row key, each projected by Film's own unit")
    void anInlineListInItsWrittenOrder() throws Exception {
        assertThat(categoryFilms(projectCategory, field("films", field("title")), "__rk_films"))
            .isEqualTo(inCategory().orderBy(FILM.TITLE, FILM.FILM_ID).fetch(FILM.TITLE));
    }

    @Test
    @DisplayName("a client's choice orders an inline list: an ascending choice reversed by its direction, a written direction kept, by primary key where it picks none")
    void anInlineListInTheOrderAClientPicks() throws Exception {
        assertThat(categoryFilms(projectChoosingCategory, argued("filmsChosen", Map.of("order", List.of(
                Map.of("field", "LENGTH", "direction", "DESC"))), field("title")), "__rk_filmsChosen"))
            .as("LENGTH, DESC").isEqualTo(inCategory().orderBy(FILM.LENGTH.desc(), FILM.FILM_ID.desc()).fetch(FILM.TITLE));
        assertThat(categoryFilms(projectChoosingCategory, argued("filmsChosen", Map.of("order", List.of(
                Map.of("field", "TITLE", "direction", "ASC"))), field("title")), "__rk_filmsChosen"))
            .as("TITLE, written DESC, asked ASC, then the row key the same way")
            .isEqualTo(inCategory().orderBy(FILM.TITLE.desc(), FILM.FILM_ID.desc()).fetch(FILM.TITLE));
        assertThat(categoryFilms(projectChoosingCategory, field("filmsChosen", field("title")), "__rk_filmsChosen"))
            .as("nothing picked").isEqualTo(inCategory().orderBy(FILM.FILM_ID).fetch(FILM.TITLE));
        assertThat(categoryFilms(projectChoosingCategory, argued("filmsChosen", Map.of("order", List.of(
                Map.of("field", "NAME_C", "direction", "DESC"))), field("title")), "__rk_filmsChosen"))
            .as("NAME_C, collated C, DESC")
            .isEqualTo(inCategory().orderBy(FILM.TITLE.collate("C").desc(), FILM.FILM_ID.desc()).fetch(FILM.TITLE));
    }

    /** Category 1's films, joined as the inline lists read them. */
    private static org.jooq.SelectConditionStep<org.jooq.Record1<String>> inCategory() {
        return TestDatabase.dsl().select(FILM.TITLE).from(FILM)
            .join(Tables.FILM_CATEGORY).on(Tables.FILM_CATEGORY.FILM_ID.eq(FILM.FILM_ID))
            .where(Tables.FILM_CATEGORY.CATEGORY_ID.eq(1));
    }

    /** The titles of category 1's films the inline list selected under {@code alias} reads, in its order. */
    private static List<String> categoryFilms(Method project, SelectedField list, String alias) throws Exception {
        @SuppressWarnings("unchecked")
        var fields = (List<Field<?>>) project.invoke(null, grouped(list), Tables.CATEGORY, null);
        var row = TestDatabase.dsl().select(fields).from(Tables.CATEGORY)
            .where(Tables.CATEGORY.CATEGORY_ID.eq(1)).fetchOne();
        return ((org.jooq.Result<?>) row.get(alias)).map(film -> film.get("title", String.class));
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
        return selected(resultKey, name, Map.of(), children);
    }

    /** A selected field with the arguments a client passed. */
    private static SelectedField argued(String name, Map<String, Object> arguments, SelectedField... children) {
        return selected(name, name, arguments, children);
    }

    private static SelectedField selected(String resultKey, String name, Map<String, Object> arguments,
                                          SelectedField... children) {
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
                case "getArguments" -> arguments;
                case "toString" -> resultKey + ":" + name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
