package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.emit.stub.OutboxFailureRuntime;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.compile.EmittedCodeHarness;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OutboxEmitter}'s failure companion and its registration, compiled and run against
 * PostgreSQL. The rows are read from the {@code scenarios/outbox-error-outcome} fact document:
 * {@code Mutation.createFilm} writes a success message and a failure message whose {@code $error}
 * keys read a union of two {@code @error} types, {@code Mutation.createDirect} two failure messages
 * over one {@code @error} type. A fetcher stand-in registers each field's companion and the test runs
 * what it registered, as settle does once the field's transaction rolled back.
 *
 * <p>The generated runtime the companion names, the carrier it registers with, the outbox exception
 * and the reads of a claimed error, is the stub {@link OutboxFailureRuntime}, and the transaction
 * provider a stand-in that opens a plain jOOQ transaction; neither has a store origin. The stub's
 * type choice answers the claimed exception's message, and its field read names the member and the
 * field, so a row says which read the companion made.
 */
@ExecutionTier
class OutboxFailureEmitterExecutionTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String PACKAGE = "outboxfailurerun";
    private static final ClassName RUNTIME = ClassName.get(OutboxFailureRuntime.class);

    private static EmittedCodeHarness harness;
    private static Map<String, Method> methods;

    @BeforeAll
    static void emit() throws Exception {
        var rows = FACTS.read("scenarios/outbox-error-outcome", graph -> OutboxQuery.read(graph.handle()));
        var runtime = new OutboxEmitter.Runtime(RUNTIME.nestedClass("EmitException"),
            (member, coordinate) -> {
                throw new AssertionError("the document's failure messages read no session member");
            },
            new OutboxEmitter.ErrorReads() {
                @Override
                public CodeBlock typeChoice(String containerName, String sourceLocal, String envLocal) {
                    return CodeBlock.of("$T.typeOf($S, $L)", RUNTIME, containerName, sourceLocal);
                }

                @Override
                public CodeBlock fieldRead(String memberType, String field, String sourceLocal, String envLocal) {
                    return CodeBlock.of("$T.read($S, $S, $L)", RUNTIME, memberType, field, sourceLocal);
                }
            });
        var host = TypeSpec.classBuilder("Fetchers").addModifiers(Modifier.PUBLIC)
            .addMethod(OutboxEmitter.jsonValueHelper());
        for (var field : List.of("createFilm", "createDirect")) {
            var row = rows.rowFor("Mutation", field).orElseThrow();
            host.addMethod(MethodSpec.methodBuilder(field)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(DataFetchingEnvironment.class, "env")
                    .addCode(OutboxEmitter.registration(row, RUNTIME))
                    .build())
                .addMethods(OutboxEmitter.renderFailed(row, PACKAGE, runtime));
        }
        // The provider a generated transaction is opened through, which marks it graphitron's own:
        // here it opens the transaction on the context it is handed and marks nothing.
        var provider = TypeSpec.classBuilder("GraphitronTransactionProvider").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodSpec.methodBuilder("owned")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(org.jooq.DSLContext.class)
                .addParameter(org.jooq.DSLContext.class, "dsl")
                .addStatement("return dsl")
                .build())
            .build();
        harness = EmittedCodeHarness.compile(Map.of(PACKAGE + ".Fetchers", host.build(),
            PACKAGE + ".schema.GraphitronTransactionProvider", provider));
        methods = new java.util.HashMap<>();
        for (var m : harness.load(PACKAGE + ".Fetchers").getDeclaredMethods()) methods.put(m.getName(), m);
    }

    @AfterAll
    static void close() {
        if (harness != null) harness.close();
        TestDatabase.dsl().execute("delete from outbox where type in ('Mutation.createFilm.failed', 'DirectFailed',"
            + " 'DirectAudited')");
    }

    @BeforeEach
    void forget() {
        OutboxFailureRuntime.registered = null;
        OutboxFailureRuntime.registeredEvents = null;
    }

    private static DataFetchingEnvironment env(Map<String, Object> arguments) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .graphQLContext(GraphQLContext.newContext().build())
            .arguments(() -> graphql.collect.ImmutableMapWithNullValues.copyOf(arguments))
            .build();
    }

    /** Calls the fetcher stand-in, which registers the field's failure companion, and returns it. */
    private static OutboxFailureRuntime.FieldRollback registered(String field, DataFetchingEnvironment env)
            throws Exception {
        methods.get(field).invoke(null, env);
        assertThat(OutboxFailureRuntime.registered).as("the fetcher registered its failure companion").isNotNull();
        return OutboxFailureRuntime.registered;
    }

    private static List<Map<String, Object>> messages(String type, String key, String value) {
        return TestDatabase.dsl().fetch("select payload -> 'kind' is not null as has_kind, payload -> 'why' is not null as has_why,"
                + " payload ->> 'kind' as kind, payload ->> 'why' as why, payload ->> 'title' as title"
                + " from outbox where type = ? and payload ->> ? = ?", type, key, value)
            .intoMaps();
    }

    private static int count(String type) {
        return TestDatabase.dsl().fetchCount(TestDatabase.dsl().selectFrom("outbox").where("type = ?", type));
    }

    @Test
    void aClaimedFailure_writesTheTypeItWasClaimedAs_andItsFieldReadAsThatTypesFetcherReadsIt() throws Exception {
        var title = "failure-emit-" + UUID.randomUUID();
        var write = registered("createFilm", env(Map.of("in", Map.of("title", title))));

        assertThat(OutboxFailureRuntime.registeredEvents)
            .as("the registration names the failure message's event, defaulted to the coordinate and .failed")
            .containsExactly("Mutation.createFilm.failed");
        write.write(TestDatabase.dsl(), new RuntimeException("FilmValueMissing"));

        var failed = messages("Mutation.createFilm.failed", "title", title);
        assertThat(failed).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("kind", "FilmValueMissing")
            .as("the member the type choice picked is the one whose field is read")
            .containsEntry("why", "FilmValueMissing.message"));
        assertThat(messages("FilmCreated", "title", title))
            .as("the companion writes the failure messages only")
            .isEmpty();
    }

    @Test
    void nothingClaimed_leavesEveryErrorKeyOut_andWritesTheRest() throws Exception {
        var title = "failure-emit-" + UUID.randomUUID();
        var write = registered("createFilm", env(Map.of("in", Map.of("title", title))));

        write.write(TestDatabase.dsl(), null);

        assertThat(messages("Mutation.createFilm.failed", "title", title)).singleElement()
            .satisfies(row -> assertThat(row)
                .containsEntry("has_kind", false)
                .containsEntry("has_why", false)
                .containsEntry("title", title));
    }

    @Test
    void aFieldsFailureMessages_commitTogether() throws Exception {
        var title = "failure-emit-" + UUID.randomUUID();
        int failedBefore = count("DirectFailed");
        var write = registered("createDirect", env(Map.of("in", Map.of("title", title))));

        assertThat(OutboxFailureRuntime.registeredEvents).containsExactly("DirectFailed", "DirectAudited");
        write.write(TestDatabase.dsl(), new RuntimeException("whatever"));

        assertThat(messages("DirectAudited", "title", title)).hasSize(1);
        assertThat(count("DirectFailed"))
            .as("the field's other failure message, which carries no title, committed with it")
            .isEqualTo(failedBefore + 1);
        assertThat(TestDatabase.dsl().fetchValue("select payload ->> 'why' from outbox where type = 'DirectFailed'"
                + " order by occurred_at desc limit 1"))
            .as("one @error type needs no type choice: its own field is read")
            .isEqualTo("FilmLanguageMissing.message");
    }

    @Test
    void aFieldsFailureMessages_orNotAtAll() throws Exception {
        // The outbox table refuses a message whose title is this marker, as it is written.
        var title = "outbox-failure-refused-on-write";
        int failedBefore = count("DirectFailed");
        int auditedBefore = count("DirectAudited");
        var write = registered("createDirect", env(Map.of("in", Map.of("title", title))));

        assertThatThrownBy(() -> write.write(TestDatabase.dsl(), null))
            .as("a failing statement surfaces as the outbox exception")
            .isInstanceOf(OutboxFailureRuntime.EmitException.class);
        assertThat(count("DirectFailed"))
            .as("the message written before the refused one is rolled back with it")
            .isEqualTo(failedBefore);
        assertThat(count("DirectAudited")).isEqualTo(auditedBefore);
    }
}
