package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_CONTEXT_PARAMETER;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentConditionContextArg;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldCondition;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldConditionArgMappingPair;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldConditionContextArg;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedInputField;
import static no.sikt.graphitron.model.test.SeededStore.seedMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedMethodParameter;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_context_parameter} returns: which of a condition method's parameters
 * receive a request-context value at one application of the directive.
 *
 * <p>The rule reads as a name match and the cases are mostly about the two exclusions that make it
 * more than one. A parameter typed to receive the source table receives it whatever it is called, so
 * a table position is out before any name is compared; and an argument binding beats a context key,
 * which means the exclusion has to read both halves of what binds an argument, an authored pair
 * naming the parameter and a slot in scope sharing its name. The second half is where the case that
 * looks like a contradiction lives: a pair claiming the slot takes the identity entry away, and the
 * same-named parameter falls through to the context key it also is.
 *
 * <p>The rest is grain and absence. Site-keyed, because a context key is written at the application
 * and one signature named twice answers differently; scope is the site's own rule, three rules for
 * three spellings, which is what makes a sibling argument in scope at one site and out of scope at
 * another; and a position with no row is one of four facts, none of which is a verdict.
 */
class ConditionContextParameterTest {

    // ===== The rule =====

    /** A declared context key naming a parameter is what a row says, and the whole of what it says. */
    @Test
    void aDeclaredContextKeyNamesItsParameter() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(positions(dsl)).containsExactly(1);
        });
    }

    /**
     * An application declaring no context arguments reaches no parameter. The signature is the one
     * above: what the store answers differently is the directive, which is the point of the grain.
     */
    @Test
    void anApplicationWithNoContextArgumentsReachesNothing() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    /** A key naming no parameter of the signature reaches nothing, and says nothing about the key. */
    @Test
    void aKeyNamingNoParameterReachesNothing() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "actor");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    /** Several keys reaching several parameters are several rows; nothing here ranks them. */
    @Test
    void severalKeysReachSeveralParameters() {
        withSources(dsl -> {
            conditionMethod(dsl, "byBoth", param("table", TABLE_FQN),
                param("tenant", STRING), param("locale", STRING));
            fieldSite(dsl, "byBoth");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 1, "locale");

            assertThat(positions(dsl)).containsExactly(1, 2);
        });
    }

    /**
     * One name written at two positions of the directive is one fact. The live rule reads the list
     * as a set keyed by name, so the position it was written at carries no ranking and cannot
     * multiply the answer.
     */
    @Test
    void oneNameWrittenTwiceIsOneRow() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 1, "tenant");

            assertThat(positions(dsl)).containsExactly(1);
        });
    }

    // ===== The table exclusion =====

    /**
     * A parameter that receives the source table receives it whatever it is called, so naming it as
     * a context key reaches nothing. This is what makes the roles disjoint at a position rather
     * than merely ordered: the table role is read from the declared type, and a name cannot
     * override a type.
     */
    @Test
    void aContextKeyNamingTheTableParameterReachesNothing() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "table");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    // ===== The argument exclusion, both halves =====

    /**
     * A slot in scope sharing the parameter's name claims it, and the claim beats the context key.
     * That claim is the identity entry the binding map fills for every unclaimed slot, so it needs
     * no authored pair to exist; the field's own argument is enough.
     */
    @Test
    void aSlotInScopeSharingTheNameClaimsTheParameter() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN), param("title", STRING));
            fieldSite(dsl, "byTitle");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "title");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    /**
     * The case that reads as a contradiction and is the rule working. An authored pair claiming the
     * slot takes its identity entry away, so the same-named parameter is no longer bound as an
     * argument and falls through to the context key it also is. The pair binds a different
     * parameter, which is what claiming a slot means.
     */
    @Test
    void aPairClaimingTheSlotRestoresTheContextReading() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTitle", param("table", TABLE_FQN),
                param("title", STRING), param("wanted", STRING));
            fieldSite(dsl, "byTitle");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "Query", "films", 0, "wanted", "title");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "title");

            assertThat(positions(dsl)).containsExactly(1);
        });
    }

    /** An authored pair naming the parameter itself claims it, which is the exclusion's other half. */
    @Test
    void aPairNamingTheParameterClaimsIt() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionArgMappingPair(dsl, GRAPH, "Query", "films", 0, "tenant", "title");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    // ===== Scope is the site's own rule =====

    /**
     * At an argument condition one slot is in scope, the argument the directive sits on, so a
     * parameter named after a sibling argument of the same field is claimed by nothing and the
     * context key reaches it.
     */
    @Test
    void aSiblingArgumentIsOutOfScopeAtAnArgumentCondition() {
        withSources(dsl -> {
            seedArgument(dsl, GRAPH, "Query", "films", "locale", "String", 1, 3);
            conditionMethod(dsl, "byLocale", param("table", TABLE_FQN), param("locale", STRING));
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", CONDITIONS, "byLocale", null);
            seedArgumentConditionContextArg(dsl, GRAPH, "Query", "films", "title", 0, "locale");

            assertThat(positions(dsl)).containsExactly(1);
        });
    }

    /**
     * The same signature and the same key at a field condition, where every argument of the field is
     * in scope, so the sibling claims the parameter and the key reaches nothing. The pair with the
     * case above is the whole argument for keying this relation on the site.
     */
    @Test
    void theSameSiblingIsInScopeAtAFieldCondition() {
        withSources(dsl -> {
            seedArgument(dsl, GRAPH, "Query", "films", "locale", "String", 1, 3);
            conditionMethod(dsl, "byLocale", param("table", TABLE_FQN), param("locale", STRING));
            fieldSite(dsl, "byLocale");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "locale");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    /**
     * At an input-field condition the only slot in scope is the input field itself, so a parameter
     * named after it is claimed and one named anything else is reachable. Both halves in one case,
     * because the scope rule is what the case is about and a single expectation states it.
     */
    @Test
    void anInputFieldConditionHasItsOwnFieldInScopeAndNothingElse() {
        withSources(dsl -> {
            seedInputField(dsl, GRAPH, "FilmFilter", "title", "String", 0, false, false, null);
            conditionMethod(dsl, "byFilter", param("table", TABLE_FQN),
                param("title", STRING), param("tenant", STRING));
            seedFieldCondition(dsl, GRAPH, "FilmFilter", "title", CONDITIONS, "byFilter", null);
            seedFieldConditionContextArg(dsl, GRAPH, "FilmFilter", "title", 0, "title");
            seedFieldConditionContextArg(dsl, GRAPH, "FilmFilter", "title", 1, "tenant");

            assertThat(sites(dsl)).containsExactly("INPUT_FIELD_CONDITION FilmFilter.title 2");
        });
    }

    // ===== Grain =====

    /**
     * Two applications naming one signature answer separately, and only the one that declared the
     * key carries a row. A method-keyed relation could not say this, which is why this role is not
     * on the one beside it.
     */
    @Test
    void twoApplicationsOfOneSignatureAnswerSeparately() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedArgumentCondition(dsl, GRAPH, "Query", "films", "title", CONDITIONS, "byTenant", null);
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(sites(dsl)).containsExactly("FIELD_CONDITION Query.films 1");
        });
    }

    /**
     * Two overloads one application names are two rows, kept apart by the descriptor. The position
     * alone would collapse them where the overloads happen to declare the key at the same position
     * and would say nothing about which signature it belongs to where they do not.
     */
    @Test
    void twoOverloadsAreTwoRowsKeptApartByTheDescriptor() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN),
                param("locale", STRING), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(withDescriptors(dsl)).containsExactly(
                "(Lpkg/tables/Film;Ljava/lang/String;)Lorg/jooq/Condition; 1",
                "(Lpkg/tables/Film;Ljava/lang/String;Ljava/lang/String;)Lorg/jooq/Condition; 2");
        });
    }

    /** One graph's application says nothing about another's, the partition being the leading key. */
    @Test
    void anotherGraphSeesNothing() {
        withSources(dsl -> {
            conditionMethod(dsl, "byTenant", param("table", TABLE_FQN), param("tenant", STRING));
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(positionsIn(dsl, "other")).isEmpty();
        });
    }

    // ===== Absence =====

    /**
     * A reference the census holds no method for reaches nothing, which is the classpath scan's own
     * silence rather than an answer about the key. It reads exactly like a key naming no parameter,
     * and distinguishing the two is not this relation's job: absence here is four facts and a reader
     * needing to tell them apart asks the relations that state each.
     */
    @Test
    void aReferenceTheCensusHasNoMethodForReachesNothing() {
        withSources(dsl -> {
            fieldSite(dsl, "byTenant");
            seedFieldConditionContextArg(dsl, GRAPH, "Query", "films", 0, "tenant");

            assertThat(positions(dsl)).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String CONDITIONS = "com.example.Conditions";
    private static final String TABLE_FQN = "pkg.tables.Film";
    private static final String STRING = "java.lang.String";

    /** One parameter: its name and the classes its declared type names, keyed by type path. */
    private record Param(String name, String classFqn) {}

    private static Param param(String name, String classFqn) {
        return new Param(name, classFqn);
    }

    /** One classpath entry and one generated package, so the table role resolves from the catalog. */
    private static void withSources(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, PKG);
            seedGraphSource(dsl, GRAPH, JAR);
            seedTable(dsl, PKG, "public", "Film");
            seedField(dsl, GRAPH, "Query", "films", "Film", true);
            seedArgument(dsl, GRAPH, "Query", "films", "title", "String");
            seedClass(dsl, JAR, CONDITIONS, "CLASS");
            body.accept(dsl);
        });
    }

    /**
     * A condition method on the shared class, its descriptor derived from the parameters' classes so
     * two overloads of one name stay apart. Naming the method from a directive is a separate call,
     * a directive naming a method by name and not by signature.
     */
    private static void conditionMethod(DSLContext dsl, String methodName, Param... params) {
        var descriptor = new StringBuilder("(");
        for (Param p : params) {
            descriptor.append('L').append(p.classFqn().replace('.', '/')).append(';');
        }
        descriptor.append(")Lorg/jooq/Condition;");
        seedMethod(dsl, JAR, CONDITIONS, methodName, descriptor.toString());
        for (int position = 0; position < params.length; position++) {
            seedMethodParameter(dsl, JAR, CONDITIONS, methodName, descriptor.toString(), position,
                params[position].name(), Map.of("", params[position].classFqn()));
        }
    }

    /** A field-site {@code @condition} on the shared coordinate, naming the given method. */
    private static void fieldSite(DSLContext dsl, String methodName) {
        seedFieldCondition(dsl, GRAPH, "Query", "films", CONDITIONS, methodName, null);
    }

    /** The positions the declared context keys reach, which is the whole of what the relation states. */
    private static List<Integer> positions(DSLContext dsl) {
        return positionsIn(dsl, GRAPH);
    }

    private static List<Integer> positionsIn(DSLContext dsl, String graphName) {
        derive(dsl);
        var t = INTENT_CONDITION_CONTEXT_PARAMETER;
        return dsl.select(t.POSITION)
            .from(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .orderBy(t.POSITION)
            .fetch(t.POSITION);
    }

    /** The site-grain projection: which application a row belongs to. */
    private static List<String> sites(DSLContext dsl) {
        derive(dsl);
        var t = INTENT_CONDITION_CONTEXT_PARAMETER;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.SITE, t.USE_SITE, t.POSITION)
            .fetch(row -> row.get(t.SITE) + " " + row.get(t.USE_SITE) + " " + row.get(t.POSITION));
    }

    /** The overload projection: the descriptor is what tells the two rows apart. */
    private static List<String> withDescriptors(DSLContext dsl) {
        derive(dsl);
        var t = INTENT_CONDITION_CONTEXT_PARAMETER;
        return dsl.select(t.fields())
            .from(t)
            .where(t.GRAPH_NAME.eq(GRAPH))
            .orderBy(t.POSITION)
            .fetch(row -> row.get(t.DESCRIPTOR) + " " + row.get(t.POSITION));
    }
}
