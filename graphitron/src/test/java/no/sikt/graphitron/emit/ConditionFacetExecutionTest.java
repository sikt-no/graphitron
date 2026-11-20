package no.sikt.graphitron.emit;

import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A faceted field's condition split as its facets' counts read it, emitted from the store's rows and
 * run against PostgreSQL: the base every facet counts under, the field's other filters without the
 * facets' own, and each facet's own filter alone. Read over the {@code scenarios/connection-shared-facets}
 * fact document, whose {@code Query.filmsById} filters by the films it is handed beside its
 * {@code originalLanguageId} facet.
 */
@ExecutionTier
class ConditionFacetExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "facets";
    private static final no.sikt.graphitron.rewrite.test.jooq.public_.tables.Film FILM = Tables.FILM;
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> com.palantir.javapoet.CodeBlock.of("null"),
        coordinate -> com.palantir.javapoet.CodeBlock.of("null"),
        (member, coordinate) -> com.palantir.javapoet.CodeBlock.of("null"),
        new NodeIdDecodeEmitter.Runtime(com.palantir.javapoet.ClassName.get(PACKAGE, "Encoder"), PACKAGE));

    private static EmittedCodeHarness harness;
    private static Map<String, Method> conditions;
    private static Map<String, ConditionEmitter.Result> results;
    private static List<String> facets;

    @BeforeAll
    static void emit() throws Exception {
        record Read(List<ConditionQuery.Row> rows, NodeIdDecodeQuery decodes) {}
        var read = FACTS.read("scenarios/connection-shared-facets",
            graph -> new Read(ConditionQuery.read(graph.handle()), NodeIdDecodeQuery.read(graph.handle())));
        var row = read.rows().stream().filter(r -> r.coordinate().equals("Query.filmsById")).findFirst().orElseThrow();
        facets = row.facets();
        var type = TypeSpec.classBuilder("Conditions").addModifiers(Modifier.PUBLIC);
        results = new HashMap<>();
        var aliases = no.sikt.graphitron.render.TableAliasFragments.tableAliasesClass(PACKAGE);
        results.put("base", ConditionEmitter.emit(row.facetBase(), "base", read.decodes(), RUNTIME, aliases));
        for (var facet : facets) {
            results.put(facet, ConditionEmitter.emit(row.facetOwn(facet), facet, read.decodes(), RUNTIME, aliases));
        }
        results.values().forEach(result -> {
            if (result instanceof ConditionEmitter.Result.Emitted emitted) {
                type.addMethod(emitted.method());
                emitted.helpers().forEach(type::addMethod);
            }
        });
        var units = new HashMap<String, TypeSpec>();
        units.put(PACKAGE + ".Conditions", type.build());
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.put(PACKAGE + ".schema." + spec.name(), spec);
        }
        for (TypeSpec spec : no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator.generate(PACKAGE)) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            units.put(PACKAGE + ".util." + spec.name(), spec);
        }
        harness = EmittedCodeHarness.compileStrictly(units);
        conditions = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Conditions").getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) conditions.put(m.getName(), m);
        }
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Condition condition(String name, Map<String, Object> args) throws Exception {
        return (Condition) conditions.get(name).invoke(null, FILM, args);
    }

    private static List<Integer> films(Condition condition) {
        return TestDatabase.dsl().select(FILM.FILM_ID).from(FILM).where(condition).orderBy(FILM.FILM_ID)
            .fetch(FILM.FILM_ID);
    }

    @Test
    @DisplayName("the field's one facet is read off the store, and the base and its own filter are both emitted")
    void theFacetIsStatedAndBothHalvesEmitted() {
        assertThat(facets).containsExactly("originalLanguageId");
        assertThat(results).allSatisfy((name, result) ->
            assertThat(result).as(name).isInstanceOf(ConditionEmitter.Result.Emitted.class));
    }

    @Test
    @DisplayName("the base filters by the films handed in and not by the facet's value")
    void theBaseLeavesTheFacetOut() throws Exception {
        var args = Map.<String, Object>of("filmIds", List.of(1, 2, 3),
            "filter", Map.of("originalLanguageId", List.of(-1)));
        assertThat(films(condition("base", args))).isEqualTo(films(FILM.FILM_ID.in(1, 2, 3))).isNotEmpty();
    }

    @Test
    @DisplayName("the facet's own filter compares its value alone, the films handed in left out")
    void theFacetsOwnFilterIsItsTermAlone() throws Exception {
        // An original language some film holds, or one none does, where the seed holds none.
        var held = TestDatabase.dsl().select(FILM.ORIGINAL_LANGUAGE_ID).from(FILM)
            .where(FILM.ORIGINAL_LANGUAGE_ID.isNotNull()).limit(1).fetchOne(FILM.ORIGINAL_LANGUAGE_ID);
        var language = held == null ? 1 : held;
        var args = Map.<String, Object>of("filmIds", List.of(1),
            "filter", Map.of("originalLanguageId", List.of(language)));
        assertThat(films(condition("originalLanguageId", args)))
            .isEqualTo(films(FILM.ORIGINAL_LANGUAGE_ID.in(language)));
        assertThat(films(condition("originalLanguageId", Map.of()))).isEqualTo(films(DSL.noCondition()));
    }
}
