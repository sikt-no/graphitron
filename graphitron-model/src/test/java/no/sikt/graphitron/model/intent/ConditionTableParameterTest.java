package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_TABLE_PARAMETER;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSupertype;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_table_parameter} returns: which of a condition method's parameters
 * receive the source table.
 *
 * <p>The rule is one type test with two arms, and the cases are mostly about why it needs two. A
 * generated table class reaches the store through the catalog and through nothing else, the
 * classpath scan excluding that package; anything else an author writes reaches it through the
 * classpath census and through nothing else, the catalog knowing only what it generated. Neither
 * census subsumes the other, so each arm gets a case and so does the shape that would be missed if
 * either were dropped.
 *
 * <p>The rest is grain and absence. The relation is keyed on the method rather than on the site,
 * because this role is the one of the three the generator decides without consulting the site at
 * all; and a signature with no row is a method declaring no table parameter, which is a refusal the
 * consumer states rather than a verdict this relation carries.
 */
class ConditionTableParameterTest {

    // ===== The two arms of the type test =====

    /**
     * The ordinary case, and the arm the closure cannot answer. A generated table class is outside
     * the classpath census by that census's own rule, so it declares no supertypes there and reaches
     * the jOOQ table interface through nothing; the catalog is what knows it is a table.
     */
    @Test
    void aGeneratedTableClassIsTheTableParameter() {
        withSources(dsl -> {
            seedTable(dsl, PKG, "public", "Film");
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN), param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    /**
     * A parameter typed as the bare jOOQ table interface is a table parameter. The closure answers
     * it reflexively, which is the whole reason that relation carries self-rows: without them this
     * arm would need an equality of its own beside the existence test.
     *
     * <p>What the generator does with such a signature at a filter site is a different question, and
     * one the route relation already answers with a refusal. Receiving the alias and resolving an
     * arrival are not the same demand, and this relation answers only the first.
     */
    @Test
    void theBareJooqTableInterfaceIsATableParameter() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTitle", param("table", JOOQ_TABLE), param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    /**
     * An author's own supertype over generated tables is a table parameter, and this is the arm the
     * catalog cannot answer: no table is generated as that class, so only the declared hierarchy
     * says what it is. The live rule admits it for exactly this reason, asking assignability rather
     * than identity.
     */
    @Test
    void anAuthorsOwnTableSupertypeIsATableParameter() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.BaseTable", "CLASS");
            seedSupertype(dsl, JAR, "com.example.BaseTable", "org.jooq.impl.TableImpl", "EXTENDS");
            seedClass(dsl, JAR, "org.jooq.impl.TableImpl", "CLASS");
            seedSupertype(dsl, JAR, "org.jooq.impl.TableImpl", JOOQ_TABLE, "IMPLEMENTS");
            conditionMethod(dsl, "byTitle", param("table", "com.example.BaseTable"),
                param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    /**
     * A parameterised table is read at its raw head, which is where the live rule reads it too: the
     * reflective test runs on the erased parameter class and the type argument never reaches it.
     */
    @Test
    void aParameterisedTableIsReadAtItsRawHead() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTitle",
                new Param("table", Map.of("", JOOQ_TABLE, "0", "pkg.tables.records.FilmRecord")),
                param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    /** A parameter neither census answers for is not a table parameter, and draws no row. */
    @Test
    void aParameterThatIsNeitherDrawsNoRow() {
        withSources(dsl -> {
            seedClass(dsl, JAR, "com.example.NotATable", "CLASS");
            conditionMethod(dsl, "byTitle", param("table", "com.example.NotATable"),
                param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * A parameter naming no class at all draws no row, a primitive one being what that is. The
     * relation reads the root of a declared type, and where nothing is named there is nothing to
     * test rather than something to guess at.
     */
    @Test
    void aParameterNamingNoClassDrawsNoRow() {
        withSources(dsl -> {
            seedTable(dsl, PKG, "public", "Film");
            conditionMethod(dsl, "byLength", param("table", TABLE_FQN),
                new Param("length", Map.of()));
            named(dsl, "byLength");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    // ===== Multiplicity, grain and absence =====

    /**
     * Two table parameters are two rows. The generator passes the alias to each rather than picking
     * one, so a consumer reading this relation as a single answer per signature would emit a call
     * short of an argument.
     */
    @Test
    void twoTableParametersAreTwoRows() {
        withSources(dsl -> {
            seedTable(dsl, PKG, "public", "Film");
            seedTable(dsl, PKG, "public", "Actor");
            conditionMethod(dsl, "join", param("left", TABLE_FQN),
                param("right", "pkg.tables.Actor"));
            named(dsl, "join");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0, 1);
        });
    }

    /**
     * A method declaring no table parameter has no rows, which is the absence the consumer reads as
     * the generator's outright refusal of such a signature. Stated here rather than as a verdict
     * column: it is a fact about a method the schema named, and a vocabulary for a population of one
     * would say nothing the absence does not.
     */
    @Test
    void aMethodWithNoTableParameterHasNoRows() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTitle", param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).isEmpty();
        });
    }

    /**
     * The same signature written at a field site and at an argument site is one set of rows. This
     * role is decided by the declared type alone, so unlike the two roles beside it there is nothing
     * about the site to key on, and the relation says so by not carrying a site.
     */
    @Test
    void theSameMethodWrittenAtTwoSitesIsOneSetOfRows() {
        withSources(dsl -> {
            seedTable(dsl, PKG, "public", "Film");
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN), param("title", STRING));
            named(dsl, "byTitle");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", CONDITIONS, "byTitle", null);

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
        });
    }

    /**
     * Two overloads of one name are kept apart by the descriptor, so a table parameter at position 0
     * of one and at position 1 of the other are two rows a reader can tell apart.
     */
    @Test
    void twoOverloadsAreKeptApartByTheirDescriptor() {
        withSources(dsl -> {
            seedTable(dsl, PKG, "public", "Film");
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN), param("title", STRING));
            conditionMethod(dsl, "byTitle", param("title", STRING), param("table", TABLE_FQN));
            named(dsl, "byTitle");

            assertThat(withDescriptors(dsl)).containsExactly(
                "(Lpkg/tables/Film;Ljava/lang/String;)Lorg/jooq/Condition; 0",
                "(Ljava/lang/String;Lpkg/tables/Film;)Lorg/jooq/Condition; 1");
        });
    }

    /** The graph partition, on a relation both of whose arms scope through it. */
    @Test
    void aSiblingGraphReadsNoTableParameter() {
        withSources(dsl -> {
            seedGraph(dsl, "other");
            seedTable(dsl, PKG, "public", "Film");
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN), param("title", STRING));
            named(dsl, "byTitle");

            assertThat(tableParameters(dsl, GRAPH)).containsExactly(0);
            assertThat(tableParameters(dsl, "other")).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String CONDITIONS = "com.example.Conditions";
    private static final String TABLE_FQN = "pkg.tables.Film";
    private static final String JOOQ_TABLE = "org.jooq.Table";
    private static final String STRING = "java.lang.String";

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
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedClass(dsl, JAR, CONDITIONS, "CLASS");
            body.accept(dsl);
        });
    }

    /**
     * A condition method on the shared class. The descriptor is derived from the parameters' root
     * classes so two overloads of one name stay apart, which is the only property the cases need
     * from it.
     *
     * <p>Naming the method from a directive is a separate call because a directive names a method by
     * name and not by signature: two overloads are named by one site, so folding the site in here
     * would make the overload case seed a site twice over.
     */
    private static void conditionMethod(DSLContext dsl, String methodName, Param... params) {
        var descriptor = new StringBuilder("(");
        for (Param p : params) {
            String root = p.declaredType().get("");
            descriptor.append(root == null ? "I" : "L" + root.replace('.', '/') + ";");
        }
        descriptor.append(")Lorg/jooq/Condition;");
        seedMethod(dsl, JAR, CONDITIONS, methodName, descriptor.toString());
        for (int position = 0; position < params.length; position++) {
            seedMethodParameter(dsl, JAR, CONDITIONS, methodName, descriptor.toString(), position,
                params[position].name(), params[position].declaredType());
        }
    }

    /** A field-site {@code @condition} naming the shared class and the given method. */
    private static void named(DSLContext dsl, String methodName) {
        seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, methodName, null);
    }

    /** The positions that receive the table, which is the whole of what the relation states. */
    private static List<Integer> tableParameters(DSLContext dsl, String graphName) {
        derive(dsl);
        var t = INTENT_CONDITION_TABLE_PARAMETER;
        return dsl.select(t.POSITION)
            .from(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .orderBy(t.POSITION)
            .fetch(t.POSITION);
    }

    /** The overload case's projection: the descriptor is what tells the two rows apart. */
    private static List<String> withDescriptors(DSLContext dsl) {
        derive(dsl);
        var t = INTENT_CONDITION_TABLE_PARAMETER;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.POSITION)
            .fetch(row -> row.get(t.DESCRIPTOR) + " " + row.get(t.POSITION));
    }
}
