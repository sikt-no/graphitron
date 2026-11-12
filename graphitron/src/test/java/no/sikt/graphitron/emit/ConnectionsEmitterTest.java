package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.util.PageInfoClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated connection classes, compiled as a consumer compiles them and driven: an edge's
 * cursor is the cursor function's answer for its element, a page's info is bounded by its first and
 * last element, the count runs only when it is read, and a class has a member for exactly the
 * positions its type fills. Read over the {@code scenarios/connection-positions} fact document.
 */
@PipelineTier
class ConnectionsEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PKG = "emitted";

    private static ConnectionsQuery.Shapes shapes;

    @BeforeAll
    static void read() {
        shapes = FACTS.read("scenarios/connection-positions", graph -> ConnectionsQuery.read(graph.handle()));
    }

    private static EmittedCodeHarness compile() {
        var units = new HashMap<String, TypeSpec>();
        var runtime = new ConnectionsEmitter.Runtime(
            ClassName.get(PKG + ".util", PageInfoClassGenerator.CLASS_NAME),
            ClassName.get(PKG + ".util", "Facets"));
        for (TypeSpec spec : ConnectionsEmitter.emit(shapes, PKG, runtime)) {
            units.put(PKG + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : PageInfoClassGenerator.generate()) {
            units.put(PKG + ".util." + spec.name(), spec);
        }
        return EmittedCodeHarness.compileStrictly(units);
    }

    private static List<Record> elements(int... ids) {
        var dsl = DSL.using(SQLDialect.DEFAULT);
        var id = DSL.field("id", Integer.class);
        return Arrays.stream(ids).mapToObj(n -> {
            Record r = dsl.newRecord(id);
            r.set(id, n);
            return r;
        }).toList();
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new AssertionError(target.getClass().getSimpleName() + " has no member " + name);
    }

    private static List<String> members(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
            .map(Method::getName).sorted().toList();
    }

    @Test
    void theQueryReadsEachTypesPositions() {
        assertThat(shapes.connections()).extracting(ConnectionsQuery.Connection::typeName)
            .containsExactly("ActorsConnection", "FilmsConnection");
        assertThat(shapes.edges()).extracting(ConnectionsQuery.Edge::typeName)
            .containsExactly("ActorsEdge", "FilmsEdge");
    }

    @Test
    void aClassHasAMemberForEachPositionItsTypeFillsAndNoOther() throws Exception {
        try (var harness = compile()) {
            var outer = harness.load(PKG + ".util." + ConnectionsEmitter.CLASS_NAME);
            var films = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("FilmsConnection"))
                .findFirst().orElseThrow();
            var actors = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("ActorsConnection"))
                .findFirst().orElseThrow();
            var edge = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("FilmsEdge"))
                .findFirst().orElseThrow();
            assertThat(members(films)).containsExactly("edges", "pageInfo", "totalCount");
            assertThat(members(actors)).containsExactly("edges", "pageInfo");
            assertThat(members(edge)).containsExactly("cursor", "node");
        }
    }

    @Test
    void aPagesEdgesPageInfoAndCountReadTheClosure() throws Exception {
        try (var harness = compile()) {
            var outer = harness.load(PKG + ".util." + ConnectionsEmitter.CLASS_NAME);
            var films = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("FilmsConnection"))
                .findFirst().orElseThrow();
            var counted = new AtomicInteger();
            Function<Record, String> cursorOf = r -> "c" + r.get(0);
            Function<Object, Integer> countOf = env -> {
                counted.incrementAndGet();
                return 42;
            };
            var page = films.getConstructors()[0].newInstance(elements(1, 2, 3), true, false, cursorOf, countOf);

            var edges = (List<?>) invoke(page, "edges");
            assertThat(edges).hasSize(3);
            assertThat(invoke(edges.get(1), "cursor")).isEqualTo("c2");
            assertThat(((Record) invoke(edges.get(2), "node")).get(0)).isEqualTo(3);

            var info = invoke(page, "pageInfo");
            assertThat(invoke(info, "hasNextPage")).isEqualTo(true);
            assertThat(invoke(info, "hasPreviousPage")).isEqualTo(false);
            assertThat(invoke(info, "startCursor")).isEqualTo("c1");
            assertThat(invoke(info, "endCursor")).isEqualTo("c3");

            assertThat(counted).as("the count runs only when read").hasValue(0);
            assertThat(invoke(page, "totalCount", new Object[] {null})).isEqualTo(42);
            assertThat(counted).hasValue(1);
        }
    }

    @Test
    void anEmptyPageHasNoCursors() throws Exception {
        try (var harness = compile()) {
            var outer = harness.load(PKG + ".util." + ConnectionsEmitter.CLASS_NAME);
            var actors = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("ActorsConnection"))
                .findFirst().orElseThrow();
            Function<Record, String> cursorOf = r -> "c" + r.get(0);
            var page = actors.getConstructors()[0].newInstance(List.of(), false, false, cursorOf);
            var info = invoke(page, "pageInfo");
            assertThat(invoke(info, "startCursor")).isNull();
            assertThat(invoke(info, "endCursor")).isNull();
            assertThat((List<?>) invoke(page, "edges")).isEmpty();
        }
    }
}
