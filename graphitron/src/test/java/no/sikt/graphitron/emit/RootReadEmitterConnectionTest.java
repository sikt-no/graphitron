package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ColumnValuesClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.PageInfoClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RootReadEmitter}'s connection bodies, emitted from the store's facts, compiled as a consumer
 * compiles them and run against PostgreSQL: a page in the root's own order, the next one read after the
 * first's last cursor without repeating a row, a page in the order the request's {@code @orderBy}
 * argument chooses, a page ordered by a key read down a path, and the count, on the request's connection
 * where the store states the root none. The count on a root's own connection is the multi-tenant
 * example's, read by {@code TenantDivinedRoutingExecutionTest} over a routed root. The projection unit
 * and the selection scaffold are stubbed, so the page itself is what is checked. Read over the
 * {@code scenarios/root-connection} fact document.
 */
@ExecutionTier
class RootReadEmitterConnectionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "rootconnection";
    private static final ClassName CONTEXT = ClassName.get(PACKAGE + ".schema", "GraphitronContext");

    private static final RootReadEmitter.Names NAMES = new RootReadEmitter.Names() {
        @Override
        public String launcherMethod(String parentType, String fieldName) {
            return fieldName;
        }

        @Override
        public String orderByHelper(String parentType, String fieldName) {
            return fieldName + "OrderBy";
        }

        @Override
        public ClassName conditionsClass(String parentType) {
            return ClassName.get(PACKAGE, "Conditions");
        }

        @Override
        public String conditionMethod(String parentType, String fieldName) {
            return fieldName + "Condition";
        }

        @Override
        public ClassName projectionUnit(String typeName) {
            return ClassName.get(PACKAGE, typeName);
        }

        @Override
        public ClassName selectionOccurrences() {
            return ClassName.get(PACKAGE, "SelectionOccurrences");
        }

        @Override
        public ClassName connectionHelper() {
            return ClassName.get(PACKAGE + ".util", ConnectionHelperClassGenerator.CLASS_NAME);
        }

        @Override
        public ClassName orderByResult() {
            return ClassName.get(PACKAGE + ".util", OrderByResultClassGenerator.CLASS_NAME);
        }
    };

    private static final OrderByHelperEmitter.Names HELPER_NAMES = new OrderByHelperEmitter.Names() {
        @Override
        public String orderByHelper(String parentType, String fieldName) {
            return fieldName + "OrderBy";
        }

        @Override
        public ClassName orderByResult() {
            return ClassName.get(PACKAGE + ".util", OrderByResultClassGenerator.CLASS_NAME);
        }
    };

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;
    private static Class<?> context;

    @BeforeAll
    static void emit() throws Exception {
        var read = FACTS.read("scenarios/root-connection", graph -> new Read(RootQuery.read(graph.handle()),
            ConnectionsQuery.read(graph.handle()), ConnectionsQuery.byField(graph.handle())));
        var host = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        for (var field : List.of("filmsPage", "filmsPicked", "filmsByOriginalLanguage")) {
            var root = (RootQuery.Emitted) read.roots().get("Query." + field);
            host.addMethod(RootReadEmitter.render(root, NAMES, read.byField().at("Query", field)));
            if (root.choice() != null) {
                host.addMethod(OrderByHelperEmitter.render(root, HELPER_NAMES));
            }
        }

        var units = new ArrayList<JavaFile>();
        units.add(JavaFile.builder(PACKAGE, host.build()).build());
        units.add(JavaFile.builder(PACKAGE, projectionStub()).build());
        units.add(JavaFile.builder(PACKAGE, selectionStub()).build());
        units.add(JavaFile.builder(PACKAGE + ".schema", contextStub()).build());
        var runtime = new ConnectionsEmitter.Runtime(
            ClassName.get(PACKAGE + ".util", PageInfoClassGenerator.CLASS_NAME),
            ClassName.get(PACKAGE + ".util", "Facets"),
            ClassName.get(PACKAGE + ".schema", "ErrorRouter"));
        for (var spec : ConnectionsEmitter.emit(read.shapes(), PACKAGE, runtime)) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : PageInfoClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : ConnectionHelperClassGenerator.generate(PACKAGE, false, OptionalInt.empty())) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : ColumnValuesClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : OrderByResultClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".util", spec).build());
        }
        for (var spec : GraphitronClientExceptionClassGenerator.generate()) {
            units.add(JavaFile.builder(PACKAGE + ".schema", spec).build());
        }
        var dir = Files.createTempDirectory("rootconnection");
        var files = new ArrayList<Path>();
        for (var unit : units) {
            files.add(unit.writeToPath(dir));
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
        bodies = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Bodies").getDeclaredMethods()) {
            m.setAccessible(true);
            bodies.put(m.getName(), m);
        }
        context = harness.load(CONTEXT.canonicalName());
    }

    private record Read(Map<String, RootQuery.Result> roots, ConnectionsQuery.Shapes shapes,
                        ConnectionsQuery.FieldConnections byField) {}

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /** The film type's projection: its key and title, whatever the selection. */
    private static TypeSpec projectionStub() {
        var fieldList = ParameterizedTypeName.get(ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Field"), WildcardTypeName.subtypeOf(Object.class)));
        return TypeSpec.classBuilder("Film").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("$project").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(fieldList)
                .addParameter(Object.class, "occurrences")
                .addParameter(ClassName.get("no.sikt.graphitron.rewrite.test.jooq.public_.tables", "Film"), "table")
                .addParameter(DataFetchingEnvironment.class, "env")
                .addStatement("return $T.of(table.FILM_ID, table.TITLE)", List.class)
                .build())
            .build();
    }

    /** The selection scaffold, its groupings unread by the stubbed projection. */
    private static TypeSpec selectionStub() {
        var selection = ClassName.get("graphql.schema", "DataFetchingFieldSelectionSet");
        var builder = TypeSpec.classBuilder("SelectionOccurrences").addModifiers(Modifier.PUBLIC);
        for (var adapter : List.of("ownFields", "connectionNodes")) {
            builder.addMethod(MethodSpec.methodBuilder(adapter).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Object.class).addParameter(selection, "selection").addStatement("return null").build());
        }
        return builder.build();
    }

    /** The request's context, handing out the test database's {@code DSLContext}. */
    private static TypeSpec contextStub() {
        return TypeSpec.classBuilder(CONTEXT.simpleName()).addModifiers(Modifier.PUBLIC)
            .addField(DSLContext.class, "dsl", Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(MethodSpec.methodBuilder("getDslContext").addModifiers(Modifier.PUBLIC)
                .returns(DSLContext.class).addParameter(DataFetchingEnvironment.class, "env")
                .addStatement("return dsl").build())
            .build();
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) throws Exception {
        context.getField("dsl").set(null, TestDatabase.dsl());
        var graphQlContext = GraphQLContext.newContext().of(context, context.getConstructor().newInstance()).build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments)
            .graphQLContext(graphQlContext).build();
    }

    private static Object page(String body, Map<String, Object> arguments) throws Exception {
        return bodies.get(body).invoke(null, TestDatabase.dsl(), env(arguments));
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new AssertionError(target.getClass().getSimpleName() + " has no member " + name);
    }

    private static List<Integer> ids(Object connection) throws Exception {
        var out = new ArrayList<Integer>();
        for (var edge : (List<?>) invoke(connection, "edges")) {
            out.add(((Record) invoke(edge, "node")).get("film_id", Integer.class));
        }
        return out;
    }

    private static String endCursor(Object connection) throws Exception {
        return (String) invoke(invoke(connection, "pageInfo"), "endCursor");
    }

    private static List<Integer> expected(org.jooq.SortField<?>... order) {
        return TestDatabase.dsl().select(Tables.FILM.FILM_ID).from(Tables.FILM).orderBy(order).limit(6)
            .fetch(Tables.FILM.FILM_ID);
    }

    @Test
    void aPageInItsOwnOrderIsFollowedByTheNextWithoutRepeatingARow() throws Exception {
        var first = page("filmsPage", Map.of("first", 3));
        var next = page("filmsPage", Map.of("first", 3, "after", endCursor(first)));
        var both = new ArrayList<>(ids(first));
        both.addAll(ids(next));
        assertThat(both).isEqualTo(expected(Tables.FILM.TITLE.asc()));
        assertThat(invoke(invoke(first, "pageInfo"), "hasNextPage")).isEqualTo(true);
    }

    @Test
    void aPageFollowsTheOrderTheArgumentChooses() throws Exception {
        var chosen = page("filmsPicked", Map.of("first", 6, "order", Map.of("field", "TITLE", "direction", "DESC")));
        assertThat(ids(chosen)).isEqualTo(expected(Tables.FILM.TITLE.desc()));
        var defaulted = page("filmsPicked", Map.of("first", 6));
        assertThat(ids(defaulted)).isEqualTo(expected(Tables.FILM.FILM_ID.asc()));
    }

    /**
     * Sakila's films walked two at a time by their original language's name, then title: the second page
     * starts between the two films of one language, after the first's last cursor.
     */
    @Test
    void aPageOrderedDownAPathWalksOnWithoutRepeatingARow() throws Exception {
        var first = page("filmsByOriginalLanguage", Map.of("first", 2));
        var next = page("filmsByOriginalLanguage", Map.of("first", 2, "after", endCursor(first)));
        var walked = new ArrayList<>(ids(first));
        walked.addAll(ids(next));
        var expected = TestDatabase.dsl().select(Tables.FILM.FILM_ID).from(Tables.FILM)
            .leftJoin(Tables.LANGUAGE).on(Tables.LANGUAGE.LANGUAGE_ID.eq(Tables.FILM.ORIGINAL_LANGUAGE_ID))
            .orderBy(Tables.LANGUAGE.NAME.asc().nullsLast(), Tables.FILM.TITLE.asc()).limit(4)
            .fetch(Tables.FILM.FILM_ID);
        assertThat(walked).isEqualTo(expected);
    }

    @Test
    void theCountRunsOnTheRequestsConnectionWhereTheStoreStatesNoneOfItsOwn() throws Exception {
        var connection = page("filmsPage", Map.of("first", 1));
        assertThat(invoke(connection, "totalCount", env(Map.of())))
            .isEqualTo(TestDatabase.dsl().fetchCount(Tables.FILM));
    }
}
