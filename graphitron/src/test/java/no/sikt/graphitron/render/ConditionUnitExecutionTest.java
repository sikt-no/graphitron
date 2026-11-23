package no.sikt.graphitron.render;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.plan.GeneratedUnits;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.TableAliasesClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Conditions classes {@link ConditionUnitRenderer} renders for shapes of their own, compiled strictly
 * and run against PostgreSQL: a composite node id two keys from the rows it filters, two node types
 * over one table projecting their key into one @condition, two tables of one name in two schemas, and
 * a filter on a nesting type's field. Read over the {@code scenarios/condition-unit-shapes} fact
 * document.
 */
@ExecutionTier
class ConditionUnitExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "unitshapes";
    private static final GeneratedUnits UNITS = new GeneratedUnits(PACKAGE);

    private static EmittedCodeHarness harness;
    private static Method encode;

    @BeforeAll
    static void render() throws Exception {
        var units = FACTS.read("scenarios/condition-unit-shapes",
            graph -> ConditionUnitRenderer.render(graph.handle(), PACKAGE,
                no.sikt.graphitron.rewrite.GraphQLRewriteGenerator.conditionAddresses(new GeneratedUnits(PACKAGE))));
        var dir = Files.createTempDirectory("unitshapes");
        var files = new ArrayList<Path>();
        for (var unit : units) {
            files.add(JavaFile.builder(unit.ref().packageName(), unit.spec()).build().writeToPath(dir));
        }
        for (TypeSpec spec : NodeIdEncoderClassGenerator.generate(List.of(), PACKAGE, List.of())) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : TableAliasesClassGenerator.generate(PACKAGE)) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : ColumnValuesClassGenerator.generate()) {
            files.add(JavaFile.builder(PACKAGE + ".util", spec).build().writeToPath(dir));
        }
        for (TypeSpec spec : GraphitronClientExceptionClassGenerator.generate()) {
            files.add(JavaFile.builder(PACKAGE + ".schema", spec).build().writeToPath(dir));
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
        encode = harness.load(PACKAGE + ".util." + NodeIdEncoderClassGenerator.CLASS_NAME)
            .getMethod("encode", String.class, Object[].class);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static Condition condition(no.sikt.graphitron.command.UnitMethodRef ref, Table<?> table,
                                       Map<String, Object> args) throws Exception {
        var owner = harness.load(ref.owner().packageName() + "." + ref.owner().simpleName());
        for (var m : owner.getDeclaredMethods()) {
            if (m.getName().equals(ref.methodName()) && java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                return (Condition) m.invoke(null, table, args);
            }
        }
        throw new AssertionError("no method " + ref.methodName() + " on " + owner.getName());
    }

    private static String id(String typeId, Object... key) throws Exception {
        return (String) encode.invoke(null, typeId, key);
    }

    private static <K> List<K> keys(Table<?> table, Field<K> key, Condition condition) {
        return TestDatabase.dsl().select(key).from(table).where(condition).orderBy(key).fetch(key);
    }

    @Test
    @DisplayName("a project's node id selects the leaves two keys from it, over its two key columns")
    void aCompositeIdTwoKeysAwaySelectsItsLeaves() throws Exception {
        var leaf = Tables.DESCRIBE_HUB_LEAF;
        var ref = UNITS.conditionMethod("Query", "hubLeaves");

        assertThat(keys(leaf, leaf.LEAF_ID, condition(ref, leaf,
            Map.of("in", Map.of("projectIds", List.of(id("P", 1, 100)))))))
            .containsExactly(10, 11);
        assertThat(keys(leaf, leaf.LEAF_ID, condition(ref, leaf,
            Map.of("in", Map.of("projectIds", List.of(id("P", 1, 100), id("P", 2, 100)))))))
            .containsExactly(10, 11, 20);
        assertThat(keys(leaf, leaf.LEAF_ID, condition(ref, leaf,
            Map.of("in", Map.of("projectIds", List.of(id("P", 1, 101)))))))
            .isEmpty();
    }

    @Test
    @DisplayName("two node types over film each decode their own id into the condition, and refuse the other's")
    void siblingNodeTypesDecodeTheirOwnIds() throws Exception {
        var film = Tables.FILM;
        var films = UNITS.conditionMethod("Query", "films");
        var aliasFilms = UNITS.conditionMethod("Query", "aliasFilms");

        assertThat(keys(film, film.FILM_ID, condition(films, film, Map.of("in", Map.of("filmId", id("F", 1))))))
            .containsExactly(1);
        assertThat(keys(film, film.FILM_ID, condition(aliasFilms, film, Map.of("in", Map.of("filmId", id("FA", 2))))))
            .containsExactly(2);
        assertThatThrownBy(() -> condition(films, film, Map.of("in", Map.of("filmId", id("FA", 1)))))
            .isInstanceOf(InvocationTargetException.class);
        assertThatThrownBy(() -> condition(aliasFilms, film, Map.of("in", Map.of("filmId", id("F", 2)))))
            .isInstanceOf(InvocationTargetException.class);
    }

    @Test
    @DisplayName("tables of one name in two schemas each filter by their own class")
    void twoSchemasTablesOfOneName() throws Exception {
        var eventA = no.sikt.graphitron.rewrite.test.jooq.multischema_a.Tables.EVENT;
        var eventB = no.sikt.graphitron.rewrite.test.jooq.multischema_b.Tables.EVENT;

        assertThat(keys(eventA, eventA.EVENT_ID, condition(UNITS.conditionMethod("Query", "eventsA"), eventA,
            Map.of("name", "launch-a")))).containsExactly(10);
        assertThat(keys(eventB, eventB.EVENT_ID, condition(UNITS.conditionMethod("Query", "eventsB"), eventB,
            Map.of("code", "B-001")))).containsExactly(20);
    }

    @Test
    @DisplayName("a filter on a nesting type's field is on the nesting type's conditions class")
    void aNestingTypesFieldFilters() throws Exception {
        var language = Tables.LANGUAGE;

        assertThat(TestDatabase.dsl().select(language.NAME).from(language)
            .where(condition(UNITS.conditionMethod("FilmMeta", "languages"), language, Map.of("name", "English")))
            .fetch(language.NAME))
            .extracting(String::trim).containsExactly("English");
    }
}
