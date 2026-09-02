package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentPathSegments;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedService;
import static no.sikt.graphitron.model.test.SeededStore.seedServiceArgmappingEntry;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedType;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_type_backing_seed} returns: which of a graph's types a producer method grounds
 * on a Java class, and with which class.
 *
 * <p>Two arms, one per axis, and neither is a special case of the other. On the result axis a field
 * whose producer resolves backs the type the field names with the class that method delivers. On the
 * input axis a parameter of that same method backs the type of the argument it is fed from with the
 * class the parameter delivers, which is the only way anything reaches an input object at all: no
 * return type names one.
 *
 * <p>Both arms read a class off a declared type rather than off a descriptor, so the delivery comes
 * off first. A method handing back a list of records grounds its type on the record, the list being
 * how many and not what.
 *
 * <p>Which argument feeds a parameter is the parameter's own name, unless an {@code argMapping}
 * entry naming that parameter on its left redirects it, in which case it is the argument the right
 * side's first segment names. A path descending inside that argument is a descent and not a second
 * coordinate, so nothing below the head is fed.
 *
 * <p>A grounding is not a backing. Every row here is also a row of
 * {@code intent_type_backing_class}, which closes over these and over the accessor hops besides;
 * what this relation adds is which of that relation's rows a producer answered for. That closure is
 * a materialization a writer fills, so what it makes of these rows is pinned where the writer runs,
 * in {@code no.sikt.graphitron.rewrite.derive.TypeBackingClassTest}.
 *
 * <p>Several cases assert that a coordinate grounds nothing. Those are the relation's own claim: a
 * class stands for a composite type by answering its fields, so a producer at a field naming any
 * other kind grounds nothing, and a parameter with no name is fed by no argument rather than by the
 * one sitting at its ordinal.
 */
class TypeBackingSeedTest {

    private static final String GRAPH = "g";
    private static final String OTHER_GRAPH = "g2";
    private static final String APP = "app/target/classes";
    private static final String OTHER = "other/target/classes";
    private static final String SERVICE = "app.FilmService";

    // ===== The result axis =====

    /**
     * The first arm, and the peel with it: a field names {@code Film} and its method hands back a
     * list of records, so the type is grounded on the record the list carries. Two fields of the
     * one type carry a producer apiece, since what a producer grounds is the type its own field
     * names and a rule reading the two apart from each other would ground both types twice.
     */
    @Test
    void aProducersReturnGroundsTheTypeItsOwnFieldNames() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            seedMethod(dsl, APP, SERVICE, "findAll", "()Ljava/util/List;",
                Map.of("", "java.util.List", "0", "app.FilmRecord"));
            seedType(dsl, GRAPH, "Film", "OBJECT");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedService(dsl, GRAPH, "Query", "films", SERVICE, "findAll");

            seedMethod(dsl, APP, SERVICE, "spoken", "()Lapp/LanguageRecord;",
                Map.of("", "app.LanguageRecord"));
            seedType(dsl, GRAPH, "Language", "OBJECT");
            seedField(dsl, GRAPH, "Query", "spoken", "Language", false);
            seedService(dsl, GRAPH, "Query", "spoken", SERVICE, "spoken");

            assertThat(seeds(dsl))
                .as("the container is how many rather than what, so it comes off before the class "
                    + "is read")
                .containsExactlyInAnyOrder("Film=app.FilmRecord", "Language=app.LanguageRecord");
        });
    }

    /**
     * A class stands for an object or an input object, so a producer at a field naming any other
     * kind grounds nothing. No reject list over Java classes states that: each of these methods
     * delivers a perfectly ordinary class, and it is the SDL side that is not something a class can
     * answer the fields of.
     */
    @Test
    void onlyAnObjectOrAnInputObjectIsGrounded() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            producing(dsl, "count", "Int", "SCALAR", "java.lang.Integer");
            producing(dsl, "node", "Node", "INTERFACE", "app.NodeRecord");
            producing(dsl, "rating", "Rating", "ENUM", "app.RatingValue");
            producing(dsl, "any", "Any", "UNION", "app.AnyRecord");

            assertThat(seeds(dsl)).isEmpty();
        });
    }

    // ===== The input axis =====

    /** The second arm: the argument a parameter is fed from is the one sharing its name. */
    @Test
    void aParameterGroundsTheTypeOfTheArgumentSharingItsName() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "search", param("filter", "app.FilmFilterInput"));
            argument(dsl, "search", "filter", "FilmFilter");

            assertThat(seeds(dsl)).containsExactly("FilmFilter=app.FilmFilterInput");
        });
    }

    /**
     * An {@code argMapping} entry naming the parameter on its left redirects it to the argument the
     * right side names. An argument spelled like the parameter is seeded beside the one the mapping
     * names, so a rule that ignored the mapping would ground a type rather than nothing at all.
     *
     * <p>A second parameter the entry does not name keeps being fed by the argument sharing its own
     * name. The redirect is a fact about the pair, so an entry that applied to the field rather than
     * to the parameter it names would move that one too.
     */
    @Test
    void anArgMappingRedirectsTheParameterItNamesAndNoOther() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "mapped", param("f", "app.ActorFilterInput"),
                param("g", "app.PlainFilterInput"));
            argument(dsl, "mapped", "other", "ActorFilter");
            argument(dsl, "mapped", "f", "Decoy");
            argument(dsl, "mapped", "g", "PlainFilter");
            mapping(dsl, "mapped", "f", "other");

            assertThat(seeds(dsl)).containsExactlyInAnyOrder(
                "ActorFilter=app.ActorFilterInput", "PlainFilter=app.PlainFilterInput");
        });
    }

    /**
     * A dotted right side is fed by the argument its head names, the tail being a descent inside
     * that argument rather than a coordinate of its own. The argument the tail spells is seeded too,
     * so reading the path's last segment would ground a type here.
     */
    @Test
    void aDottedPathIsFedByItsHead() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "dotted", param("v", "app.DeepFilterInput"));
            argument(dsl, "dotted", "deep", "DeepFilter");
            argument(dsl, "dotted", "inner", "InnerFilter");
            mapping(dsl, "dotted", "v", "deep.inner");

            assertThat(seeds(dsl)).containsExactly("DeepFilter=app.DeepFilterInput");
        });
    }

    /**
     * A parameter the consumer compiled without {@code -parameters} carries no name, and a name is
     * the whole of what feeds it. The field's sole argument sits at the ordinal the parameter does,
     * which is where a positional fallback would find it, and there is no positional fallback.
     */
    @Test
    void aParameterWithNoNameFeedsNothing() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "nameless", param(null, "app.PlainFilterInput"));
            argument(dsl, "nameless", "plain", "PlainFilter");

            assertThat(seeds(dsl)).isEmpty();
        });
    }

    /**
     * Each parameter is decomposed under its own ordinal. Two of them here, whose classes are told
     * apart only by the position they sit at, so a rule reading a method's parameters without
     * keeping the ordinal would ground each argument with both classes.
     */
    @Test
    void eachParameterIsReadAtItsOwnPosition() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "paired", param("first", "app.FirstInput"),
                param("second", "app.SecondInput"));
            argument(dsl, "paired", "first", "FirstFilter");
            argument(dsl, "paired", "second", "SecondFilter");

            assertThat(seeds(dsl)).containsExactlyInAnyOrder(
                "FirstFilter=app.FirstInput", "SecondFilter=app.SecondInput");
        });
    }

    /**
     * A scalar argument is not grounded, on the result axis's terms and for the same reason: what a
     * class can stand for is a type whose fields it answers.
     */
    @Test
    void aScalarArgumentIsNotGrounded() {
        withSeededStore(GRAPH, dsl -> {
            entry(dsl, GRAPH, APP);
            taking(dsl, "byTitle", param("q", "java.lang.String"));
            seedType(dsl, GRAPH, "String", "SCALAR");
            seedArgument(dsl, GRAPH, "Query", "byTitle", "q", "String");

            assertThat(seeds(dsl)).isEmpty();
        });
    }

    // ===== The partition =====

    /**
     * Two graphs, each with a classpath entry of its own declaring the same class under the same
     * method and descriptor, so the entry is the only thing telling the two apart. Both arms are
     * grounded from that one method, and each arm reaches the census by a join of its own.
     *
     * <p>Every graph join the relation makes has somewhere to land here if it stopped holding. The
     * two graphs' {@code search} fields name different types and each graph declares both names, so
     * a producer paired with the sibling's field row would ground the sibling's type. Their
     * parameters carry different names and each graph declares an argument under both, so a
     * producer reading the sibling entry's parameter would feed an argument of its own that nothing
     * names.
     */
    @Test
    void aGraphIsGroundedOnItsOwnMembershipOnly() {
        withSeededStore(GRAPH, dsl -> {
            seedGraph(dsl, OTHER_GRAPH);
            grounding(dsl, GRAPH, APP, "Film", "filter", "app.FilmRecord", "app.FilmFilterInput");
            decoyArgument(dsl, GRAPH, "criteria");
            grounding(dsl, OTHER_GRAPH, OTHER, "Trailer", "criteria", "lib.FilmDto",
                "lib.FilterDto");
            decoyArgument(dsl, OTHER_GRAPH, "filter");

            assertThat(allSeeds(dsl)).containsExactlyInAnyOrder(
                GRAPH + " Film=app.FilmRecord",
                GRAPH + " FilmFilter=app.FilmFilterInput",
                OTHER_GRAPH + " Trailer=lib.FilmDto",
                OTHER_GRAPH + " FilmFilter=lib.FilterDto");
        });
    }

    /** The one spelling both graphs' producers carry, the entry being what tells them apart. */
    private static final String SHARED_DESCRIPTOR = "(Lapp/Filter;)Lapp/Result;";

    /**
     * One graph's whole surface: a {@code Query.search} field naming the type given, produced by a
     * method on an entry of this graph's own that takes one parameter under the name given. Both
     * type names either graph uses are declared here, so a pairing across the partition would find
     * a type rather than falling away for want of one.
     */
    private static void grounding(DSLContext dsl, String graphName, String sourceName,
                                  String namedType, String parameterName, String returned,
                                  String parameterClass) {
        entry(dsl, graphName, sourceName);
        seedMethod(dsl, sourceName, SERVICE, "search", SHARED_DESCRIPTOR, Map.of("", returned));
        seedMethodParameter(dsl, sourceName, SERVICE, "search", SHARED_DESCRIPTOR, 0, parameterName,
            Map.of("", parameterClass));
        seedType(dsl, graphName, "Film", "OBJECT");
        seedType(dsl, graphName, "Trailer", "OBJECT");
        seedField(dsl, graphName, "Query", "search", namedType, false);
        seedService(dsl, graphName, "Query", "search", SERVICE, "search");
        seedDeclaredType(dsl, graphName, "FilmFilter", "INPUT_OBJECT");
        seedArgument(dsl, graphName, "Query", "search", parameterName, "FilmFilter");
    }

    /** The argument the sibling graph's parameter is named for, which this graph's producer is not. */
    private static void decoyArgument(DSLContext dsl, String graphName, String argumentName) {
        seedDeclaredType(dsl, graphName, "Decoy", "INPUT_OBJECT");
        seedArgument(dsl, graphName, "Query", "search", argumentName, "Decoy");
    }

    // ===== Readings =====

    /** Every grounding of this graph, as {@code Type=class}. */
    private static List<String> seeds(DSLContext dsl) {
        return dsl.selectFrom(INTENT_TYPE_BACKING_SEED)
            .where(INTENT_TYPE_BACKING_SEED.GRAPH_NAME.eq(GRAPH))
            .fetch(r -> r.getTypeName() + "=" + r.getClassName());
    }

    /** The same over the whole store, graph first, so the partition is read as a value. */
    private static List<String> allSeeds(DSLContext dsl) {
        return dsl.selectFrom(INTENT_TYPE_BACKING_SEED)
            .fetch(r -> r.getGraphName() + " " + r.getTypeName() + "=" + r.getClassName());
    }

    // ===== Fixtures =====

    /** A classpath entry the graph reads, carrying the one service class these cases name. */
    private static void entry(DSLContext dsl, String graphName, String sourceName) {
        seedSource(dsl, sourceName, "DIRECTORY");
        seedGraphSource(dsl, graphName, sourceName);
        seedClass(dsl, sourceName, SERVICE, "CLASS");
    }

    /**
     * A {@code Query} field of the named kind, produced by a method delivering the class given. The
     * kind is the case's to state, this being where the arm's one guard is read.
     */
    private static void producing(DSLContext dsl, String fieldName, String namedType, String kind,
                                  String className) {
        String descriptor = "()L" + className.replace('.', '/') + ";";
        seedMethod(dsl, APP, SERVICE, fieldName, descriptor, Map.of("", className));
        seedType(dsl, GRAPH, namedType, kind);
        seedField(dsl, GRAPH, "Query", fieldName, namedType, false);
        seedService(dsl, GRAPH, "Query", fieldName, SERVICE, fieldName);
    }

    /**
     * A {@code Query} field produced by a method taking the parameters given, each delivering its
     * own class at its own position. The method's own return names no class, so the result axis
     * stays out of every answer these cases read.
     */
    private static void taking(DSLContext dsl, String fieldName, Param... parameters) {
        String descriptor = Arrays.stream(parameters)
            .map(p -> "L" + p.className().replace('.', '/') + ";")
            .collect(Collectors.joining("", "(", ")Ljava/util/List;"));
        seedMethod(dsl, APP, SERVICE, fieldName, descriptor, Map.of());
        seedType(dsl, GRAPH, "Film", "OBJECT");
        seedField(dsl, GRAPH, "Query", fieldName, "Film", true);
        seedService(dsl, GRAPH, "Query", fieldName, SERVICE, fieldName);
        for (int position = 0; position < parameters.length; position++) {
            seedMethodParameter(dsl, APP, SERVICE, fieldName, descriptor, position,
                parameters[position].name(), Map.of("", parameters[position].className()));
        }
    }

    /** One parameter of such a method; a null name is one compiled without {@code -parameters}. */
    private record Param(String name, String className) {}

    private static Param param(String name, String className) {
        return new Param(name, className);
    }

    /** An input-object argument on one of those fields, its type declared as the input object. */
    private static void argument(DSLContext dsl, String fieldName, String argumentName,
                                 String namedType) {
        seedDeclaredType(dsl, GRAPH, namedType, "INPUT_OBJECT");
        seedArgument(dsl, GRAPH, "Query", fieldName, argumentName, namedType);
    }

    /** One {@code argMapping} pair on a field's service, with the path its right side spells. */
    private static void mapping(DSLContext dsl, String fieldName, String paramName,
                                String argumentPath) {
        seedServiceArgmappingEntry(dsl, GRAPH, "Query", fieldName, 0, paramName, argumentPath);
        seedArgumentPathSegments(dsl, GRAPH, "Query", fieldName, argumentPath);
    }
}
