package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.FillRuntime;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.jooq.public_.Tables;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service call's body with its lift composed in, emitted from the store's rows for
 * {@link LiftEmitterExecutionTest}'s schema and run against PostgreSQL: at a root the records the
 * service hands back are lifted to their rows, one or a list in the service's order; under an object
 * every parent's records are lifted in one statement and each parent's rows come back under its key,
 * one row per parent or a list per parent.
 */
@ExecutionTier
class CallLiftExecutionTest {

    private static final String PACKAGE = "calllift";
    private static final FillEmitter.Runtime RUNTIME = new FillEmitter.Runtime(
        key -> CodeBlock.of("$T.contextArgument(env, $S)", FillRuntime.class, key),
        coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", FillRuntime.class, coordinate),
        (member, coordinate) -> CodeBlock.of("$T.sessionMember_$L(dsl, $S)", FillRuntime.class, member, coordinate));
    private static final CallEmitter.FrameKey FILM_KEY = new CallEmitter.FrameKey(
        ParameterizedTypeName.get(ClassName.get(Record1.class), ClassName.get(Integer.class)),
        ClassName.get(Tables.class), "FILM");

    @TempDir
    static Path tmp;

    private static EmittedCodeHarness harness;
    private static Map<String, Method> bodies;

    /** The method name a coordinate's lift and body are emitted under. */
    private static String name(String coordinate) {
        return coordinate.replace("Query.", "").replace(".", "");
    }

    @BeforeAll
    static void emit() throws Exception {
        List<LiftQuery.Row> lifts;
        List<CallQuery.Row> calls;
        List<FillQuery.Row> fills;
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, LiftEmitterExecutionTest.SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            var handle = new StoreHandle(store.dsl(), CapturedStore.GRAPH);
            lifts = LiftQuery.read(handle);
            calls = CallQuery.read(handle);
            fills = FillQuery.read(handle);
        }
        var liftHost = ClassName.get(PACKAGE, "Lifts");
        var selection = ClassName.get(PACKAGE, "SelectionOccurrences");
        var liftType = TypeSpec.classBuilder(liftHost).addModifiers(Modifier.PUBLIC);
        var bodyType = TypeSpec.classBuilder("Bodies").addModifiers(Modifier.PUBLIC);
        var units = new HashMap<String, TypeSpec>();
        for (var lift : lifts) {
            var source = ClassName.get(PACKAGE, lift.targetType() + "Source");
            units.putIfAbsent(source.canonicalName(), LiftEmitter.sourceTable(source, lift.table(), lift.key()));
            units.putIfAbsent(PACKAGE + "." + lift.targetType(), LiftEmitterPipelineTest.projectionStub(lift.targetType()));
            liftType.addMethod(LiftEmitter.lift(lift, name(lift.coordinate()), source,
                ClassName.get(PACKAGE, lift.targetType()), selection));
            var call = calls.stream().filter(c -> c.coordinate().equals(lift.coordinate())).findFirst().orElseThrow();
            var filled = fills.stream().filter(f -> f.coordinate().equals(lift.coordinate())).findFirst().orElseThrow();
            var result = CallEmitter.body(call, filled, Map.of(), RUNTIME, "call" + name(lift.coordinate()),
                lift.root() ? Optional.empty() : Optional.of(FILM_KEY),
                Optional.of(new CallEmitter.Lift(liftHost, name(lift.coordinate()), lift.list())));
            assertThat(result).as(lift.coordinate()).isInstanceOf(CallEmitter.Result.Emitted.class);
            var emitted = (CallEmitter.Result.Emitted) result;
            bodyType.addMethod(emitted.body());
            emitted.helpers().forEach(bodyType::addMethod);
        }
        units.put(liftHost.canonicalName(), liftType.build());
        units.put(PACKAGE + ".Bodies", bodyType.build());
        units.put(selection.canonicalName(), LiftEmitterPipelineTest.selectionStub());
        harness = EmittedCodeHarness.compileStrictly(units);
        bodies = new HashMap<>();
        for (var m : harness.load(PACKAGE + ".Bodies").getDeclaredMethods()) bodies.put(m.getName(), m);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment().arguments(arguments).build();
    }

    private static Object root(String body, Map<String, Object> arguments) throws Throwable {
        try {
            return bodies.get(body).invoke(null, env(arguments), TestDatabase.dsl());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Set<Record1<Integer>> films(int... ids) {
        var out = new LinkedHashSet<Record1<Integer>>();
        for (int id : ids) out.add(DSL.using(SQLDialect.POSTGRES).newRecord(Tables.FILM.FILM_ID).values(id));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Record1<Integer>, Object> batch(String body, Set<Record1<Integer>> keys) throws Throwable {
        try {
            return (Map<Record1<Integer>, Object>) bodies.get(body).invoke(null, keys, env(Map.of()), TestDatabase.dsl());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object valueFor(Map<Record1<Integer>, Object> answered, int id) {
        for (var entry : answered.entrySet()) {
            if (entry.getKey().value1() == id) return entry.getValue();
        }
        return "missing";
    }

    @Test
    void aRootListIsLiftedInTheServicesOrder() throws Throwable {
        @SuppressWarnings("unchecked")
        var rows = (List<Record>) root("callfilmsByIds", Map.of("ids", List.of(3, 1, 2)));
        assertThat(rows).extracting(r -> r.get(Tables.FILM.FILM_ID.getName())).containsExactly(3, 1, 2);
    }

    @Test
    void aRootRecordIsLiftedToItsRow() throws Throwable {
        var row = (Record) root("callfilmById", Map.of("id", 1));
        assertThat(row.get(Tables.FILM.FILM_ID.getName())).isEqualTo(1);
    }

    @Test
    void oneRecordPerParentIsLiftedInOneBatchAndHandedBackByKey() throws Throwable {
        var answered = batch("callFilmlanguageByService", films(1, 2));
        assertThat(((Record) valueFor(answered, 1)).get(Tables.LANGUAGE.LANGUAGE_ID.getName()))
            .isEqualTo(TestDatabase.dsl().select(Tables.FILM.LANGUAGE_ID).from(Tables.FILM)
                .where(Tables.FILM.FILM_ID.eq(1)).fetchOne(Tables.FILM.LANGUAGE_ID));
        assertThat(valueFor(answered, 2)).isInstanceOf(Record.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aListPerParentIsLiftedAndHandedBackToItsParent() throws Throwable {
        var answered = batch("callFilmcastMembers", films(1, 2));
        var cast = TestDatabase.dsl().select(Tables.FILM_ACTOR.ACTOR_ID).from(Tables.FILM_ACTOR)
            .where(Tables.FILM_ACTOR.FILM_ID.eq(1)).orderBy(Tables.FILM_ACTOR.ACTOR_ID).fetch(Tables.FILM_ACTOR.ACTOR_ID);
        assertThat((List<Record>) valueFor(answered, 1))
            .extracting(r -> r.get(Tables.FILM_ACTOR.ACTOR_ID.getName())).containsExactlyElementsOf(cast);
        assertThat(valueFor(answered, 2)).isInstanceOf(List.class);
    }
}
