package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.generators.util.OrderByResultClassGenerator;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.QOM;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderByHelperEmitter}'s helpers, compiled and called: each hands back the sort fields and the
 * cursor columns of the order the request's {@code @orderBy} argument names, or of the default where it
 * names none, each completed by the row key as the store states it. A picked order follows the
 * request's direction unless its author fixed one; a key that
 * may be {@code NULL} sorts last either way; and a key down a path is read back under its cursor alias.
 * The root is read from the {@code scenarios/order-by-helper} fact document; compiled strictly, as a consumer
 * compiles generated sources.
 */
@UnitTier
class OrderByHelperEmitterTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "orderbyrun";

    private static final OrderByHelperEmitter.Names NAMES = new OrderByHelperEmitter.Names() {
        @Override
        public String orderByHelper(String parentType, String fieldName) {
            return fieldName + "OrderBy";
        }

        @Override
        public ClassName orderByResult() {
            return ClassName.get(PACKAGE, OrderByResultClassGenerator.CLASS_NAME);
        }
    };

    private static EmittedCodeHarness harness;
    private static Map<String, Method> helpers;

    @BeforeAll
    static void emit() throws Exception {
        var roots = FACTS.read("scenarios/order-by-helper", graph -> RootQuery.read(graph.handle()));
        var host = TypeSpec.classBuilder("Helpers").addModifiers(Modifier.PUBLIC)
            .addMethod(OrderByHelperEmitter.render((RootQuery.Emitted) roots.get("Query.filmsPicked"), NAMES))
            .build();
        var dir = Files.createTempDirectory("orderby");
        var files = new ArrayList<Path>();
        files.add(JavaFile.builder(PACKAGE, host).build().writeToPath(dir));
        for (var unit : OrderByResultClassGenerator.generate()) {
            files.add(JavaFile.builder(PACKAGE, unit).build().writeToPath(dir));
        }
        harness = EmittedCodeHarness.compileFilesStrictly(files);
        helpers = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Helpers").getDeclaredMethods()) {
            m.setAccessible(true);
            helpers.put(m.getName(), m);
        }
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    /** The sort fields and cursor columns a helper hands back. */
    private record Ordered(List<SortField<?>> sortFields, List<Field<?>> columns) {}

    @SuppressWarnings("unchecked")
    private static Ordered call(String helper, Object order) throws Exception {
        var arguments = new HashMap<String, Object>();
        if (order != null) arguments.put("order", order);
        DataFetchingEnvironment env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments).build();
        var result = helpers.get(helper).invoke(null, env, Tables.FILM);
        var type = result.getClass();
        return new Ordered((List<SortField<?>>) type.getMethod("sortFields").invoke(result),
            (List<Field<?>>) type.getMethod("columns").invoke(result));
    }

    private static Map<String, Object> order(String field, String direction) {
        var entry = new HashMap<String, Object>();
        entry.put("field", field);
        if (direction != null) entry.put("direction", direction);
        return entry;
    }

    private static List<String> sorts(Ordered ordered) {
        return ordered.sortFields().stream()
            .map(s -> s.getName() + " " + s.getOrder() + (s.$nullOrdering() == QOM.NullOrdering.NULLS_LAST ? " nulls last" : ""))
            .toList();
    }

    private static List<String> columns(Ordered ordered) {
        return ordered.columns().stream().map(Field::getName).toList();
    }

    @Test
    void noArgumentSortsByTheDefault() throws Exception {
        var ordered = call("filmsPickedOrderBy", null);
        assertThat(sorts(ordered)).containsExactly("title ASC", "film_id ASC");
        assertThat(columns(ordered)).containsExactly("title", "film_id");
    }

    @Test
    void aPickedOrderFollowsTheRequestsDirection() throws Exception {
        assertThat(sorts(call("filmsPickedOrderBy", order("TITLE", "DESC")))).containsExactly("title DESC", "film_id DESC");
        assertThat(sorts(call("filmsPickedOrderBy", order("TITLE", null)))).containsExactly("title ASC", "film_id ASC");
    }

    @Test
    void anOrderItsAuthorFixedKeepsItsDirection() throws Exception {
        assertThat(sorts(call("filmsPickedOrderBy", order("TITLE_DESC", "ASC")))).containsExactly("title DESC", "film_id DESC");
    }

    @Test
    void aKeyThatMayBeNullSortsLastEitherWay() throws Exception {
        assertThat(sorts(call("filmsPickedOrderBy", order("LENGTH", "DESC")))).containsExactly("length DESC nulls last", "film_id DESC");
        assertThat(sorts(call("filmsPickedOrderBy", order("LENGTH", "ASC")))).containsExactly("length ASC nulls last", "film_id ASC");
    }

    @Test
    void aKeyDownAPathIsReadBackUnderItsCursorAlias() throws Exception {
        var ordered = call("filmsPickedOrderBy", order("ORIGINAL_LANGUAGE", "ASC"));
        assertThat(ordered.sortFields().getFirst().$nullOrdering()).isEqualTo(QOM.NullOrdering.NULLS_LAST);
        assertThat(columns(ordered)).containsExactly(OrderKeys.cursorAlias(0), "film_id");
    }
}
