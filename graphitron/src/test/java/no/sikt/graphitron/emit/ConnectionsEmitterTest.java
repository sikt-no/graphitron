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
 * last element, the count runs only when it is read, a class has a member for exactly the
 * positions its type fills, and {@code register} wires exactly the position-filling fields, each
 * to its member under its own name. Read over the {@code scenarios/connection-positions} fact
 * document.
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
            ClassName.get(PKG + ".util", "Facets"),
            ClassName.get(PKG + ".schema", "ErrorRouter"));
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

    @Test
    void registerWiresExactlyThePositionFillingFieldsToTheirMembers() throws Exception {
        try (var harness = compile()) {
            var outer = harness.load(PKG + ".util." + ConnectionsEmitter.CLASS_NAME);
            var registry = graphql.schema.GraphQLCodeRegistry.newCodeRegistry();
            outer.getMethod("register", graphql.schema.GraphQLCodeRegistry.Builder.class).invoke(null, registry);

            for (var coordinate : List.of("FilmsConnection.edges", "FilmsConnection.pageInfo",
                    "FilmsConnection.totalCount", "FilmsEdge.node", "FilmsEdge.cursor",
                    "ActorsConnection.edges", "ActorsConnection.pageInfo", "ActorsEdge.node",
                    "ActorsEdge.cursor")) {
                assertThat(registry.hasDataFetcher(coordinates(coordinate))).as(coordinate).isTrue();
            }
            // A field an author added fills no position, and a position the type does not fill
            // has no field to register.
            for (var coordinate : List.of("FilmsConnection.label", "FilmsEdge.rank",
                    "ActorsConnection.totalCount", "FilmsConnection.nodes")) {
                assertThat(registry.hasDataFetcher(coordinates(coordinate))).as(coordinate).isFalse();
            }

            var films = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("FilmsConnection"))
                .findFirst().orElseThrow();
            Function<Record, String> cursorOf = r -> "c" + r.get(0);
            Function<Object, Integer> countOf = env -> 7;
            var page = films.getConstructors()[0].newInstance(elements(4, 5), false, true, cursorOf, countOf);
            var built = registry.build();
            assertThat(fetch(built, "FilmsConnection.totalCount", page)).isEqualTo(7);
            var edges = (List<?>) fetch(built, "FilmsConnection.edges", page);
            assertThat(edges).hasSize(2);
            assertThat(fetch(built, "FilmsEdge.cursor", edges.get(0))).isEqualTo("c4");
            assertThat(((Record) fetch(built, "FilmsEdge.node", edges.get(1))).get(0)).isEqualTo(5);
            var info = fetch(built, "FilmsConnection.pageInfo", page);
            assertThat(invoke(info, "hasPreviousPage")).isEqualTo(true);
        }
    }

    /**
     * The members no authored connection of the fact document fills, nodes and facets, over a
     * connection that fills every position: what register hands each field. A facets field's
     * failure is routed, here through a router stand-in that answers a marker.
     */
    @Test
    void registerHandsNodesAndFacetsTheirMembers_andRoutesAFailedFacets() throws Exception {
        var shapes = new ConnectionsQuery.Shapes(
            List.of(new ConnectionsQuery.Connection("BooksConnection", "BooksEdge", java.util.Map.of(
                "edges", "EDGES", "nodes", "NODES", "pageInfo", "PAGE_INFO", "totalCount", "TOTAL_COUNT",
                "facets", "FACETS"), "BooksConnectionFacets")),
            List.of(new ConnectionsQuery.Edge("BooksEdge", java.util.Map.of("node", "NODE", "cursor", "CURSOR"))));
        var units = new HashMap<String, TypeSpec>();
        var runtime = new ConnectionsEmitter.Runtime(
            ClassName.get(PKG + ".util", PageInfoClassGenerator.CLASS_NAME),
            ClassName.get(PKG + ".util", "Facets"),
            ClassName.get(PKG + ".schema", "ErrorRouter"));
        for (TypeSpec spec : ConnectionsEmitter.emit(shapes, PKG, runtime)) {
            units.put(PKG + ".util." + spec.name(), spec);
        }
        for (TypeSpec spec : PageInfoClassGenerator.generate()) {
            units.put(PKG + ".util." + spec.name(), spec);
        }
        var mod = new javax.lang.model.element.Modifier[] {
            javax.lang.model.element.Modifier.PUBLIC, javax.lang.model.element.Modifier.STATIC};
        units.put(PKG + ".util.Facets", TypeSpec.classBuilder("Facets")
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .addType(TypeSpec.classBuilder("BooksConnectionFacets").addModifiers(mod).build())
            .build());
        units.put(PKG + ".schema.ErrorRouter", TypeSpec.classBuilder("ErrorRouter")
            .addModifiers(javax.lang.model.element.Modifier.PUBLIC)
            .addMethod(com.palantir.javapoet.MethodSpec.methodBuilder("surfaceClientErrorOrRedact")
                .addModifiers(mod)
                .returns(Object.class)
                .addParameter(Throwable.class, "thrown")
                .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
                .addStatement("return $S + thrown.getMessage()", "routed: ")
                .build())
            .build());
        try (var harness = EmittedCodeHarness.compileStrictly(units)) {
            var outer = harness.load(PKG + ".util." + ConnectionsEmitter.CLASS_NAME);
            var registry = graphql.schema.GraphQLCodeRegistry.newCodeRegistry();
            outer.getMethod("register", graphql.schema.GraphQLCodeRegistry.Builder.class).invoke(null, registry);
            var built = registry.build();
            var books = Arrays.stream(outer.getClasses()).filter(c -> c.getSimpleName().equals("BooksConnection"))
                .findFirst().orElseThrow();
            var facetsRecord = harness.load(PKG + ".util.Facets$BooksConnectionFacets")
                .getDeclaredConstructor().newInstance();
            Function<Record, String> cursorOf = r -> "c" + r.get(0);
            Function<Object, Integer> countOf = env -> 3;
            Function<Object, Object> facetsOf = env -> facetsRecord;
            Function<Object, Object> failing = env -> {
                throw new IllegalStateException("no facets");
            };
            var page = books.getConstructors()[0].newInstance(elements(8, 9), false, false, cursorOf, countOf, facetsOf);
            var broken = books.getConstructors()[0].newInstance(elements(8), false, false, cursorOf, countOf, failing);

            assertThat(((List<?>) fetch(built, "BooksConnection.nodes", page)))
                .extracting(r -> ((Record) r).get(0)).containsExactly(8, 9);
            assertThat(fetch(built, "BooksConnection.facets", page)).isSameAs(facetsRecord);
            assertThat(fetch(built, "BooksConnection.facets", broken)).isEqualTo("routed: no facets");
            assertThat(fetch(built, "BooksConnection.totalCount", page)).isEqualTo(3);
            var edges = (List<?>) fetch(built, "BooksConnection.edges", page);
            assertThat(fetch(built, "BooksEdge.cursor", edges.get(1))).isEqualTo("c9");
            assertThat(((Record) fetch(built, "BooksEdge.node", edges.get(0))).get(0)).isEqualTo(8);
            assertThat(invoke(fetch(built, "BooksConnection.pageInfo", page), "endCursor")).isEqualTo("c9");
        }
    }

    private static graphql.schema.FieldCoordinates coordinates(String coordinate) {
        var parts = coordinate.split("\\.");
        return graphql.schema.FieldCoordinates.coordinates(parts[0], parts[1]);
    }

    private static Object fetch(graphql.schema.GraphQLCodeRegistry registry, String coordinate, Object source)
            throws Exception {
        var parts = coordinate.split("\\.");
        var type = graphql.schema.GraphQLObjectType.newObject().name(parts[0])
            .field(graphql.schema.GraphQLFieldDefinition.newFieldDefinition().name(parts[1])
                .type(graphql.Scalars.GraphQLString))
            .build();
        var fetcher = registry.getDataFetcher(type, type.getFieldDefinition(parts[1]));
        return fetcher.get(graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .source(source).build());
    }
}
