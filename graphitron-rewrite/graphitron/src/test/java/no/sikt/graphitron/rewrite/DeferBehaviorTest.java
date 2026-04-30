package no.sikt.graphitron.rewrite;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.incremental.DelayedIncrementalPartialResult;
import graphql.incremental.DeferPayload;
import graphql.incremental.IncrementalExecutionResult;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.SelectedField;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Exploratory tests pinning down what graphql-java's {@code @defer} implementation exposes
 * to DataFetchers. The results inform the check-then-fetch design in Deliverable 4 of the
 * record-based output plan.
 *
 * <p>Key questions under test:
 * <ol>
 *   <li>Baseline: without {@code @defer}, are child fields present in the parent's selection set?</li>
 *   <li>With {@code @defer}: are deferred child fields absent from the parent's initial selection set?</li>
 *   <li>With {@code @defer}: is the child DataFetcher skipped in the initial pass?</li>
 *   <li>With {@code @defer}: does the child DataFetcher run in the deferred pass, and does it
 *       receive the parent record as its {@code source}?</li>
 * </ol>
 */
@UnitTier
public class DeferBehaviorTest {

    private static final String SCHEMA_SDL = """
            type Query {
                customer: Customer
            }
            type Customer {
                id: String
                payments: [Payment]
            }
            type Payment {
                amount: String
            }
            """;

    // Captured per-test from inside the DataFetchers
    private DataFetchingEnvironment capturedCustomerEnv;
    private DataFetchingEnvironment capturedPaymentsEnv;

    private GraphQL graphQL;

    @BeforeEach
    void setup() {
        capturedCustomerEnv = null;
        capturedPaymentsEnv = null;

        TypeDefinitionRegistry registry = new SchemaParser().parse(SCHEMA_SDL);

        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("customer", env -> {
                    capturedCustomerEnv = env;
                    return Map.of("id", "42");
                }))
            .type(newTypeWiring("Customer")
                .dataFetcher("payments", env -> {
                    capturedPaymentsEnv = env;
                    return List.of(Map.of("amount", "9.99"));
                }))
            .build();

        graphQL = GraphQL.newGraphQL(
            new SchemaGenerator().makeExecutableSchema(registry, wiring)
        ).build();
    }

    // -------------------------------------------------------------------------
    // 0. Verify incremental support is actually active
    // -------------------------------------------------------------------------

    /**
     * Guards the rest of the suite: confirms that {@code enableIncrementalSupport(true)} produces
     * an {@link IncrementalExecutionResult} (not a plain {@link ExecutionResult}). If this fails,
     * the other {@code withDefer_*} tests would be testing a no-op {@code @defer}.
     */
    @Test
    void executeWithDeferSupport_returnsIncrementalExecutionResult() {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        assertThat(result).isInstanceOf(IncrementalExecutionResult.class);
    }

    /**
     * Without incremental support enabled, graphql-java executes {@code @defer} fragments
     * eagerly as if there were no directive: both the initial result and the child DataFetcher
     * behave identically to a query without {@code @defer}.
     */
    @Test
    void withoutIncrementalSupport_deferIsIgnoredAndPaymentsCalledImmediately() {
        // Plain execute — no unusualConfiguration, no enableIncrementalSupport
        ExecutionResult result = graphQL.execute(
            "{ customer { id ... @defer { payments { amount } } } }");

        assertThat(result).isNotInstanceOf(IncrementalExecutionResult.class);
        assertThat(capturedPaymentsEnv)
            .as("without incremental support, @defer is ignored and payments is resolved eagerly")
            .isNotNull();
    }

    // -------------------------------------------------------------------------
    // 1. Baseline — no @defer
    // -------------------------------------------------------------------------

    @Test
    void withoutDefer_paymentsIsPresentInCustomerSelectionSet() {
        graphQL.execute("{ customer { id payments { amount } } }");

        List<String> immediate = immediateFieldNames(capturedCustomerEnv);
        assertThat(immediate).contains("id", "payments");
    }

    @Test
    void withoutDefer_paymentsDataFetcherIsCalled() {
        graphQL.execute("{ customer { id payments { amount } } }");

        assertThat(capturedPaymentsEnv).isNotNull();
    }

    // -------------------------------------------------------------------------
    // 2. With @defer — parent selection set during initial pass
    // -------------------------------------------------------------------------

    /**
     * Verifies that deferred child fields ARE visible in the parent DataFetcher's selection set.
     *
     * <p>This is the most important finding: graphql-java does NOT exclude deferred fields from
     * the parent's {@code getSelectionSet()}. The deferral affects when child DataFetchers are
     * called (deferred pass, not initial pass), but the parent can still see and pre-fetch those
     * fields inline. The check-then-fetch pattern relies on this: when the deferred child
     * DataFetcher eventually runs, the data is already embedded in the parent record.
     */
    @Test
    void withDefer_deferredChildFieldStillPresentInParentSelectionSet() throws Exception {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        List<String> immediate = immediateFieldNames(capturedCustomerEnv);
        assertThat(immediate)
            .as("deferred child fields must still appear in parent's selection set " +
                "(parent can pre-fetch them; deferred fetcher will find the data already there)")
            .contains("id", "payments");

        drain(((IncrementalExecutionResult) result).getIncrementalItemPublisher());
    }

    // -------------------------------------------------------------------------
    // 3. With @defer — child DataFetcher scheduling
    // -------------------------------------------------------------------------

    @Test
    void withDefer_paymentsDataFetcherNotCalledInInitialPass() throws Exception {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        // At this point only the initial pass has run
        assertThat(capturedPaymentsEnv)
            .as("payments DataFetcher must not be called in the initial pass")
            .isNull();

        drain(((IncrementalExecutionResult) result).getIncrementalItemPublisher());
    }

    @Test
    void withDefer_paymentsDataFetcherCalledInDeferredPass() throws Exception {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        drain(((IncrementalExecutionResult) result).getIncrementalItemPublisher());

        assertThat(capturedPaymentsEnv)
            .as("payments DataFetcher must be called during the deferred incremental pass")
            .isNotNull();
    }

    // -------------------------------------------------------------------------
    // 4. With @defer — what the deferred DataFetcher receives
    // -------------------------------------------------------------------------

    @Test
    void withDefer_paymentsDataFetcherReceivesParentRecordAsSource() throws Exception {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        drain(((IncrementalExecutionResult) result).getIncrementalItemPublisher());

        assertThat((Object) capturedPaymentsEnv.getSource())
            .as("deferred DataFetcher must receive the parent Customer record as its source")
            .isInstanceOf(Map.class)
            .isEqualTo(Map.of("id", "42"));
    }

    @Test
    void withDefer_deferredPayloadContainsPaymentsData() throws Exception {
        ExecutionResult result = executeWithDeferSupport(
            "{ customer { id ... @defer { payments { amount } } } }");

        List<DelayedIncrementalPartialResult> items =
            drain(((IncrementalExecutionResult) result).getIncrementalItemPublisher());

        assertThat(items).hasSize(1);
        List<DeferPayload> payloads = items.get(0).getIncremental().stream()
            .filter(DeferPayload.class::isInstance)
            .map(DeferPayload.class::cast)
            .toList();
        assertThat(payloads).hasSize(1);
        assertThat((Object) payloads.get(0).getData())
            .as("deferred payload must contain the payments sub-tree")
            .isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> immediateFieldNames(DataFetchingEnvironment env) {
        return env.getSelectionSet().getImmediateFields()
            .stream().map(SelectedField::getName).toList();
    }

    /** Execute a query with graphql-java's incremental delivery support enabled. */
    private ExecutionResult executeWithDeferSupport(String query) {
        ExecutionInput.Builder inputBuilder = ExecutionInput.newExecutionInput().query(query);
        GraphQL.unusualConfiguration(inputBuilder)
            .incrementalSupport()
            .enableIncrementalSupport(true);
        return graphQL.execute(inputBuilder);
    }

    /** Block until the incremental publisher completes and return all emitted items. */
    private List<DelayedIncrementalPartialResult> drain(
            Publisher<DelayedIncrementalPartialResult> publisher) throws Exception {
        List<DelayedIncrementalPartialResult> items = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        publisher.subscribe(new Subscriber<>() {
            @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(DelayedIncrementalPartialResult item) { items.add(item); }
            @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
            @Override public void onComplete() { latch.countDown(); }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS))
            .as("incremental publisher should complete within 5 seconds").isTrue();
        if (error.get() != null) throw new RuntimeException(error.get());
        return items;
    }
}
