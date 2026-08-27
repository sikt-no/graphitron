package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_PARAM_EXTRACTION;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedEnumBinding;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_param_extraction} returns: the extraction a value bound to a
 * condition method's parameter takes, which the parameter's declared type decides on its own.
 *
 * <p>Two kinds of case. The first kind is the rule itself, and its content is that the relation
 * agrees with {@code Class.forName(declaredType).isEnum()} on the shapes where a declared type
 * fails to load: a parameterised type, an array, and a type naming no class at all. Each of those
 * is a case, because the decomposition the relation reads answers them by a different route than
 * the load does and the agreement is therefore worth pinning rather than assuming.
 *
 * <p>The second kind is about the population and the grain. The relation is keyed on a method and
 * not on the site that named it, so a signature written at two sites is one set of rows; every
 * parameter position is a row, the relation deliberately not deciding which position carries an
 * argument; and overloads stay apart by the descriptor the census keys on.
 */
class ConditionParamExtractionTest {

    // ===== The rule =====

    /** The two outcomes, side by side on one signature. */
    @Test
    void anEnumParameterExtractsByValueOfAndEverythingElseDirectly() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Lcom/example/Rating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", ENUM_FQN),
                param("title", "java.lang.String"));
            seedFieldConditionNaming(dsl, "byRating");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 rating com.example.Rating ENUM_VALUE_OF",
                "2 title java.lang.String DIRECT");
        });
    }

    /**
     * The parameter this whole relation was blocked on. A generated enum is in the package the
     * classpath scan drops, so nothing about it reaches the census; the catalog arm of the enum
     * relation is what makes the answer here match what the generator emits.
     */
    @Test
    void aGeneratedEnumTheClasspathCensusCannotSeeStillExtractsByValueOf() {
        withSources(dsl -> {
            seedEnumBinding(dsl, PKG, "pkg.enums.MpaaRating", "public", "mpaa_rating");
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Lpkg/enums/MpaaRating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", "pkg.enums.MpaaRating"));
            seedFieldConditionNaming(dsl, "byRating");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 rating pkg.enums.MpaaRating ENUM_VALUE_OF");
        });
    }

    /**
     * A parameter naming no class is a row and not an absence. The live rule hands
     * {@code Class.forName} a spelling that cannot load and falls to the plain extraction, so a
     * primitive parameter has an answer; dropping it here would make a reader unable to tell a
     * primitive position from one the census never captured.
     */
    @Test
    void aParameterNamingNoClassIsADirectRowRatherThanNoRow() {
        withSources(dsl -> {
            conditionMethod(dsl, "byLength", "(Lpkg/tables/Film;I)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), new Param("length", Map.of()));
            seedFieldConditionNaming(dsl, "byLength");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 length null DIRECT");
        });
    }

    /**
     * A parameterised type is read at its raw head, which is never an enum because no enum is
     * generic. The element under it may well be one, and the case states exactly that: a
     * {@code List} of an enum extracts directly, matching a live rule that cannot load the
     * declared spelling at all.
     */
    @Test
    void aParameterisedTypeIsReadAtItsRawHeadAndSoExtractsDirectly() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRatings", "(Lpkg/tables/Film;Ljava/util/List;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN),
                new Param("ratings", Map.of("", "java.util.List", "0", ENUM_FQN)));
            seedFieldConditionNaming(dsl, "byRatings");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 ratings java.util.List DIRECT");
        });
    }

    /**
     * An array of an enum extracts directly, for a different reason than the case above and by the
     * same agreement. The census names an array's component one step down and nothing at the root,
     * so the root join finds no class; the live rule cannot load the bracketed spelling either.
     * Reading the component instead would answer {@code ENUM_VALUE_OF} for a parameter the
     * generator hands the array to verbatim.
     */
    @Test
    void anArrayOfAnEnumExtractsDirectly() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRatings", "(Lpkg/tables/Film;[Lcom/example/Rating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN),
                new Param("ratings", Map.of("[]", ENUM_FQN)));
            seedFieldConditionNaming(dsl, "byRatings");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 ratings null DIRECT");
        });
    }

    // ===== The population and the grain =====

    /**
     * The same signature written at a field site and at an argument site is one set of rows. The
     * rule does not vary by site, so the relation is keyed on the method, which is what lets a
     * reader join it once however many directives named the pair.
     */
    @Test
    void theSameMethodWrittenAtTwoSitesIsOneSetOfRows() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Lcom/example/Rating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", ENUM_FQN));
            seedFieldConditionNaming(dsl, "byRating");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "rating", CONDITIONS, "byRating", null);

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 rating com.example.Rating ENUM_VALUE_OF");
        });
    }

    /**
     * A path element naming a condition method reaches the population too. Such a site has no
     * GraphQL slots in scope and so binds no value parameter, but that is a fact about the site;
     * pruning it here would put a site fact in a method-keyed relation, and would answer
     * differently for a signature written at both kinds of site.
     */
    @Test
    void aPathElementConditionReachesThePopulation() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Lcom/example/Rating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", ENUM_FQN));
            seedField(dsl, GRAPH, "Film", "sequel");
            seedFieldReference(dsl, GRAPH, "Film", "sequel", 0);
            seedFieldReferenceCall(dsl, GRAPH, "Film", "sequel", 0, 0, CONDITIONS, "byRating");

            assertThat(extractions(dsl, GRAPH)).containsExactly(
                "0 table pkg.tables.Film DIRECT",
                "1 rating com.example.Rating ENUM_VALUE_OF");
        });
    }

    /**
     * Two overloads of one name are two sets of rows, kept apart by the descriptor. Without it in
     * the key their position 1 would collide into two rows a reader could not tell apart, which is
     * the multiplicity the route relation leaves to its consumers because it has no positions in
     * it to collide.
     */
    @Test
    void twoOverloadsAreKeptApartByTheirDescriptor() {
        withSources(dsl -> {
            seedClass(dsl, JAR, ENUM_FQN, "ENUM");
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Lcom/example/Rating;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", ENUM_FQN));
            conditionMethod(dsl, "byRating", "(Lpkg/tables/Film;Ljava/lang/String;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("rating", "java.lang.String"));
            seedFieldConditionNaming(dsl, "byRating");

            assertThat(withDescriptors(dsl)).containsExactly(
                "(Lpkg/tables/Film;Lcom/example/Rating;)Lorg/jooq/Condition; 1 ENUM_VALUE_OF",
                "(Lpkg/tables/Film;Ljava/lang/String;)Lorg/jooq/Condition; 1 DIRECT");
            assertThat(candidates(dsl))
                .as("an overload is a separate key and not an ambiguous one")
                .containsOnly(1);
        });
    }

    /**
     * A method the census never reached has no rows, which is the silence a non-public method
     * produces, the scan being public-only. It is the same absence a parameter of a captured method
     * never has, every position of one being a row.
     */
    @Test
    void aMethodTheCensusDoesNotHoldHasNoRows() {
        withSources(dsl -> {
            seedFieldConditionNaming(dsl, "byRating");

            assertThat(extractions(dsl, GRAPH)).isEmpty();
        });
    }

    /** The graph partition, on a relation whose census side scopes through it. */
    @Test
    void aSiblingGraphReadsNoExtraction() {
        withSources(dsl -> {
            seedGraph(dsl, "other");
            conditionMethod(dsl, "byTitle", "(Lpkg/tables/Film;Ljava/lang/String;)Lorg/jooq/Condition;",
                param("table", TABLE_FQN), param("title", "java.lang.String"));
            seedFieldConditionNaming(dsl, "byTitle");

            assertThat(extractions(dsl, GRAPH)).hasSize(2);
            assertThat(extractions(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String CONDITIONS = "com.example.Conditions";
    private static final String ENUM_FQN = "com.example.Rating";
    private static final String TABLE_FQN = "pkg.tables.Film";

    /** One parameter: its name and the classes its declared type names, keyed by type path. */
    private record Param(String name, Map<String, String> declaredType) {}

    /** The ordinary parameter, whose declared type names one class at the root. */
    private static Param param(String name, String classFqn) {
        return new Param(name, Map.of("", classFqn));
    }

    /** One classpath entry and one generated package, so either census can be seeded on its own. */
    private static void withSources(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, PKG);
            seedGraphSource(dsl, GRAPH, JAR);
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "rating", "String");
            seedClass(dsl, JAR, CONDITIONS, "CLASS");
            body.accept(dsl);
        });
    }

    /**
     * A condition method on the shared class with named parameters, which is what a consumer
     * compiled with {@code -parameters} hands the census and what a binding by name needs.
     */
    private static void conditionMethod(DSLContext dsl, String methodName, String descriptor,
                                        Param... params) {
        seedMethod(dsl, JAR, CONDITIONS, methodName, descriptor);
        for (int position = 0; position < params.length; position++) {
            seedMethodParameter(dsl, JAR, CONDITIONS, methodName, descriptor, position,
                params[position].name(), params[position].declaredType());
        }
    }

    /** A field-site {@code @condition} naming the shared class and the given method. */
    private static void seedFieldConditionNaming(DSLContext dsl, String method) {
        seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, method, null);
    }

    /** Each row as "position name javaType extractionKind", the four columns the rule decides. */
    private static List<String> extractions(DSLContext dsl, String graphName) {
        derive(dsl);
        var x = INTENT_CONDITION_PARAM_EXTRACTION;
        return dsl.select(x.fields())
            .from(x)
            .where(x.GRAPH_NAME.eq(graphName))
            .orderBy(x.DESCRIPTOR, x.POSITION)
            .fetch(row -> row.get(x.POSITION) + " " + row.get(x.PARAM_NAME) + " "
                + row.get(x.JAVA_TYPE) + " " + row.get(x.EXTRACTION_KIND));
    }

    /** The overload case's projection: the descriptor is what tells the two sets apart. */
    private static List<String> withDescriptors(DSLContext dsl) {
        derive(dsl);
        var x = INTENT_CONDITION_PARAM_EXTRACTION;
        return dsl.select(x.fields())
            .from(x)
            .where(x.GRAPH_NAME.eq(GRAPH)).and(x.POSITION.eq(1))
            .orderBy(x.DESCRIPTOR)
            .fetch(row -> row.get(x.DESCRIPTOR) + " " + row.get(x.POSITION) + " "
                + row.get(x.EXTRACTION_KIND));
    }

    private static List<Integer> candidates(DSLContext dsl) {
        derive(dsl);
        var x = INTENT_CONDITION_PARAM_EXTRACTION;
        return dsl.select(x.CANDIDATES).from(x).where(x.GRAPH_NAME.eq(GRAPH))
            .fetch(x.CANDIDATES);
    }
}
