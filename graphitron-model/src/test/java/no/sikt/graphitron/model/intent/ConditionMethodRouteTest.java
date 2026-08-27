package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_METHOD_ROUTE;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceElement;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedConditionMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_method_route} returns: the hop a condition method's own signature
 * declares, read off the classpath census and resolved against the catalog.
 *
 * <p>Every case here seeds census and catalog rows directly. A real capture would have walked a
 * classfile to get them, and what the rule reads is the walked result rather than the walk, so the
 * shapes worth stating are the ones a name can take: both parameters concrete, either one naming no
 * table, a name carrying several declarations that agree on the arrival or do not, and a method the
 * census never reached.
 *
 * <p>The asymmetry between the two parameters is this relation's whole content and is asserted from
 * both sides. A second parameter naming no generated table class yields no row, because the arrival
 * is the question; a first parameter naming none yields every table in the graph's sources, because
 * a departure is a candidacy the chain narrows.
 */
class ConditionMethodRouteTest {

    // ===== Both parameters concrete =====

    /**
     * The ordinary filter-path signature: parameter 0 the departure, parameter 1 the arrival, which
     * is the order the emitter calls the method in.
     */
    @Test
    void aConcretelyTypedPairRoutesFromTheFirstParameterToTheSecond() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).containsExactly("customerToAddress customer->address");
        });
    }

    /**
     * The route is keyed on the pair and not on the site that wrote it: the same class and method
     * written at a field site and at an argument site is one row, which is what lets the two hop
     * views join one rung instead of each resolving the signature for itself.
     */
    @Test
    void theSamePairWrittenAtTwoSitesIsOneRoute() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");
            seedField(dsl, GRAPH, "Customer", "districtByCondition");
            seedFieldReference(dsl, GRAPH, "Customer", "districtByCondition", 0);
            seedFieldReferenceCall(dsl, GRAPH, "Customer", "districtByCondition", 0, 0,
                CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).containsExactly("customerToAddress customer->address");
        });
    }

    // ===== The two parameters read asymmetrically =====

    /**
     * A wildcard target parameter routes nothing at all. The census records the bare jOOQ interface
     * at the root of {@code Table<?>} and no generated table is that class, so the arrival does not
     * resolve, which is the refusal the generator makes on a filter path.
     */
    @Test
    void aWildcardTargetParameterRoutesNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), "org.jooq.Table");
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * A wildcard source parameter declares no departure constraint, so every table in the graph's
     * sources is a candidate departure and the chain narrows it, exactly as a name-match hop
     * enumerates every function result.
     */
    @Test
    void aWildcardSourceParameterDepartsFromEveryTableInTheGraphsSources() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "anythingToAddress",
                "org.jooq.Table", tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "anythingToAddress");

            assertThat(routes(dsl, GRAPH))
                .as("one row per table the graph's sources hold")
                .containsExactly(
                    "anythingToAddress actor->address",
                    "anythingToAddress address->address",
                    "anythingToAddress customer->address",
                    "anythingToAddress film_actor->address");
        });
    }

    /**
     * A primitive parameter names no class anywhere, so it has no census row at the root position.
     * On the target side that is the same no-route the wildcard draws; on the source side it is the
     * same unconstrained departure, absence of a resolving class being what both readings turn on
     * rather than the wildcard spelling itself.
     */
    @Test
    void aParameterNamingNoClassReadsAsTheSameAbsenceAWildcardDoes() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "intToAddress",
                null, tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToInt",
                tableClass("customer"), null);
            seedBareConditionArgument(dsl, "district", CONDITIONS, "intToAddress");
            seedBareConditionArgument(dsl, "other", CONDITIONS, "customerToInt");

            assertThat(routes(dsl, GRAPH))
                .containsExactly(
                    "intToAddress actor->address",
                    "intToAddress address->address",
                    "intToAddress customer->address",
                    "intToAddress film_actor->address");
        });
    }

    /**
     * A concrete parameter naming a class no table in the graph's sources is generated as routes
     * nothing. The comparison is against {@code sql_table.class_fqn}, which is the store's only
     * join key into the generated jOOQ package, so a plausible-looking class name that is not one
     * of those resolves no arrival.
     */
    @Test
    void aTargetClassNoTableIsGeneratedAsRoutesNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToWidget",
                tableClass("customer"), "com.example.Widget");
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToWidget");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    // ===== Overloads, and the one thing the set must agree on =====

    /**
     * The set must agree on its arrival, and a set that does routes. Two declarations of one name
     * both arriving at {@code address} are one route where they also share a departure: the outer
     * UNION collapses the two descriptors' identical rows, which is the collapse every case below
     * rests on.
     */
    @Test
    void twoOverloadsAgreeingOnBothSlotsAreOneRoute() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"), null);
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH)).containsExactly("bridge customer->address");
        });
    }

    /**
     * Departure multiplicity is still rows, which is the shape overload admission exists for: one
     * declaration per participant table, all arriving at the same table. Each is a candidate
     * departure the chain narrows, exactly as an unconstrained departure enumerates every table.
     */
    @Test
    void twoOverloadsAgreeingOnTheArrivalAreOneRoutePerDeparture() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("film_actor"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH))
                .containsExactly("bridge customer->address", "bridge film_actor->address");
        });
    }

    /**
     * Two overloads of one name arriving at two tables route nothing. The build admits the set on
     * the binding shape and then has one joined table to emit with no consumer call site to defer
     * the choice to, so it rejects the disagreement; a route with two candidate arrivals is no
     * route, which is what {@code to_table} claims for a single declaration too. Which refusal it
     * was is the defect relation's answer.
     */
    @Test
    void twoOverloadsArrivingAtTwoTablesRouteNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("film_actor"), tableClass("actor"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * A wildcard declaration beside a concrete one routes nothing, and the reason is not a count
     * over arrivals: the wildcard contributes no arrival row at all, so a test phrased over the
     * arrival rows would see one table and route it. The rule is over the declarations, which is why
     * the wildcard is caught here rather than only where it is the whole set.
     */
    @Test
    void aWildcardDeclarationBesideAConcreteOneRoutesNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), "org.jooq.Table");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * The same divergence one class name over: a declaration whose target names a class no table is
     * generated as is equally invisible to the arrival rows, so it suppresses the pair for the same
     * reason the wildcard does. This is the case a rule about arrivals cannot state and a rule about
     * declarations states without a second arm.
     */
    @Test
    void aNonTableTargetDeclarationBesideAConcreteOneRoutesNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), "com.example.Widget");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * A declaration carrying no position-1 parameter declares no arrival, so it makes no claim this
     * relation answers and the pair keeps routing off the declarations that do. Whether its arity
     * nevertheless makes the set ill-formed is the build's admission question, asked at all four
     * {@code @condition} coordinates rather than here.
     */
    @Test
    void aOneParameterOverloadDeclaresNoArrivalAndSuppressesNothing() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge", tableClass("customer"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(routes(dsl, GRAPH)).containsExactly("bridge customer->address");
        });
    }

    /**
     * No return-type guard, stated as a case because its absence is deliberate: the generator picks
     * by name and never by return type, so a method the census records as returning something other
     * than a condition routes here too. Filtering it out would refuse a chain the generator accepts.
     */
    @Test
    void aMethodTheCensusSaysReturnsNoConditionRoutesAnyway() {
        withCatalog(dsl -> {
            seedClass(dsl, JAR, CONDITIONS, "CLASS");
            seedMethod(dsl, JAR, CONDITIONS, "notACondition", "(LCustomer;LAddress;)Ljava/lang/Object;");
            seedMethodParameter(dsl, JAR, CONDITIONS, "notACondition",
                "(LCustomer;LAddress;)Ljava/lang/Object;", 0, Map.of("", tableClass("customer")));
            seedMethodParameter(dsl, JAR, CONDITIONS, "notACondition",
                "(LCustomer;LAddress;)Ljava/lang/Object;", 1, Map.of("", tableClass("address")));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "notACondition");

            assertThat(routes(dsl, GRAPH)).containsExactly("notACondition customer->address");
        });
    }

    // ===== The population, and the partition =====

    /**
     * A method the census never reached routes nothing, which is the silence a class the classpath
     * scan drops produces. Which silence it was is the defect relation's answer, not this one's.
     */
    @Test
    void aMethodTheCensusDoesNotHoldRoutesNothing() {
        withCatalog(dsl -> {
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * An element carrying a condition beside its key is never asked to route: the condition there is
     * that hop's filter and the key is its route, so the signature is not this relation's question
     * and the pair draws no row.
     */
    @Test
    void aConditionWrittenBesideAKeyIsNotAskedToRoute() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), tableClass("address"));
            seedArgument(dsl, GRAPH, "Query", "customers", "district", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "customers", "district", 0);
            seedArgumentReferenceElement(dsl, GRAPH, "Query", "customers", "district", 0, 0,
                null, "customer_address_id_fkey", CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).isEmpty();
        });
    }

    /** The graph partition, on a relation whose catalog and census sides both scope through it. */
    @Test
    void aSiblingGraphReadsNoRoute() {
        withCatalog(dsl -> {
            seedGraph(dsl, "other");
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(routes(dsl, GRAPH)).hasSize(1);
            assertThat(routes(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";
    private static final String CONDITIONS = "com.example.Conditions";

    /** The catalog every case resolves against: four tables, no keys, since no key is read here. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, PKG);
            seedGraphSource(dsl, GRAPH, JAR);
            for (String table : List.of("customer", "address", "film_actor", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedField(dsl, GRAPH, "Query", "customers", "Customer", true);
            body.accept(dsl);
        });
    }

    /** The generated table class the seeded catalog names for a table, its own join key. */
    private static String tableClass(String table) {
        return PKG + ".tables." + table;
    }

    /** An argument whose one path element is a bare condition naming the pair under assertion. */
    private static void seedBareConditionArgument(DSLContext dsl, String argumentName,
                                                  String className, String method) {
        seedArgument(dsl, GRAPH, "Query", "customers", argumentName, "String");
        seedArgumentReference(dsl, GRAPH, "Query", "customers", argumentName, 0);
        seedArgumentReferenceCall(dsl, GRAPH, "Query", "customers", argumentName, 0, 0,
            className, method);
    }

    /** Each route as "method from->to", the three columns every case here is about. */
    private static List<String> routes(DSLContext dsl, String graphName) {
        derive(dsl);
        var r = INTENT_CONDITION_METHOD_ROUTE;
        return dsl.select(r.fields())
            .from(r)
            .where(r.GRAPH_NAME.eq(graphName))
            .orderBy(r.METHOD, r.FROM_TABLE, r.TO_TABLE)
            .fetch(row -> row.get(r.METHOD) + " " + row.get(r.FROM_TABLE) + "->"
                + row.get(r.TO_TABLE));
    }
}
