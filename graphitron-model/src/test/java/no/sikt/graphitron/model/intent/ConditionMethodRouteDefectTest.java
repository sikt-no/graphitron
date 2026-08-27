package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_METHOD_ROUTE_DEFECT;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedArgument;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReference;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedArgumentReferenceElement;
import static no.sikt.graphitron.model.test.SeededStore.seedClass;
import static no.sikt.graphitron.model.test.SeededStore.seedConditionMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_condition_method_route_defect} returns: why a condition method routed no hop,
 * one verdict per authored pair the rung answers nothing for.
 *
 * <p>The pairing with the rung is what these cases are for. {@code ConditionMethodRouteTest} states
 * every shape that routes and every shape that does not; here each shape that does not is named,
 * which is what lets the hop arm above both relations owe exactly one silence. So the case that
 * matters most is the last one: a pair that routes draws no row, so the two relations partition the
 * authored population between them rather than overlapping on it.
 */
class ConditionMethodRouteDefectTest {

    /** A class the classpath scan never reached: a misspelling, or one the scan drops. */
    @Test
    void aClassTheCensusDoesNotHoldIsNamedAsSuch() {
        withCatalog(dsl -> {
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(defects(dsl))
                .containsExactly("com.example.Conditions.customerToAddress CLASS_NOT_IN_CENSUS");
        });
    }

    /** The class is there and declares no method of that name, the census being public-only. */
    @Test
    void aMethodTheClassDoesNotDeclareIsNamedAsSuch() {
        withCatalog(dsl -> {
            seedClass(dsl, JAR, CONDITIONS, "CLASS");
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");

            assertThat(defects(dsl))
                .containsExactly("com.example.Conditions.customerToAddress METHOD_NOT_ON_CLASS");
        });
    }

    /** A method the generator cannot call positionally, having no second parameter to hand a target. */
    @Test
    void aMethodWithNoSecondParameterIsNamedAsSuch() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerOnly", tableClass("customer"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerOnly");

            assertThat(defects(dsl))
                .containsExactly(
                    "com.example.Conditions.customerOnly FEWER_THAN_TWO_PARAMETERS");
        });
    }

    /**
     * The wildcard target, which is a verdict about what this site demands rather than about the
     * method being ill-formed: the same signature resolves at a projection site, where the carrier
     * field's own binding supplies the target.
     */
    @Test
    void aWildcardTargetParameterIsNamedAsSuch() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAnything",
                tableClass("customer"), "org.jooq.Table");
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAnything");

            assertThat(defects(dsl))
                .containsExactly(
                    "com.example.Conditions.customerToAnything WILDCARD_TARGET_PARAMETER");
        });
    }

    /**
     * A second parameter naming something else, and a second parameter naming no class at all, are
     * one verdict: in both, nothing the graph's sources generate answers for the position.
     */
    @Test
    void aTargetParameterThatIsNoTableClassIsNamedAsSuch() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToWidget",
                tableClass("customer"), "com.example.Widget");
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToInt",
                tableClass("customer"), null);
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToWidget");
            seedBareConditionArgument(dsl, "other", CONDITIONS, "customerToInt");

            assertThat(defects(dsl))
                .containsExactly(
                    "com.example.Conditions.customerToInt TARGET_NOT_A_TABLE_CLASS",
                    "com.example.Conditions.customerToWidget TARGET_NOT_A_TABLE_CLASS");
        });
    }

    /**
     * Two declarations whose second parameters each resolve, to different tables. The build admits
     * the set on the binding shape and then rejects it, having one joined table to emit and no
     * consumer call site to defer the choice to; the census sees the same disagreement, routes
     * nothing, and this is the verdict that says so. It sits after the wildcard arm, so a set that
     * is both wildcard-mixed and disagreeing reads as the wildcard case.
     */
    @Test
    void twoOverloadsResolvingToDifferentTablesAreNamedAsSuch() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("address"), tableClass("customer"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(defects(dsl))
                .containsExactly(
                    "com.example.Conditions.bridge TARGET_DISAGREEMENT_ACROSS_OVERLOADS");
        });
    }

    /**
     * A wildcard declaration beside a concrete one is the wildcard case, and it reaches that verdict
     * by the untouched precedence rather than by a new arm: the wildcard test is an EXISTS over any
     * position-1 slot of the name, so it was set-wide already. The case is asserted on both
     * relations because this is where the two modules' rules could most easily be spelled apart.
     */
    @Test
    void aWildcardDeclarationBesideAConcreteOneIsTheWildcardCase() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), "org.jooq.Table");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(defects(dsl))
                .containsExactly("com.example.Conditions.bridge WILDCARD_TARGET_PARAMETER");
        });
    }

    /**
     * A declaration whose target names a class no table is generated as, beside a concrete one, is
     * the fall-through: only one slot of the pair resolves, so nothing disagrees among the resolving
     * ones and the verdict names the fact the census can see. For a set this is the build's
     * admission refusal rather than its routing refusal, and both are no route, which is the whole
     * of what this rung claims.
     */
    @Test
    void aNonTableTargetDeclarationBesideAConcreteOneIsTheFallThrough() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), "com.example.Widget");
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(defects(dsl))
                .containsExactly("com.example.Conditions.bridge TARGET_NOT_A_TABLE_CLASS");
        });
    }

    /**
     * Overloads are why the verdict is picked by one pass over the pair rather than by an arm per
     * refusal: a name carrying a one-parameter overload beside a wildcard-target one is one row and
     * not two, and the precedence says which, the parameter count preceding the parameter's type.
     */
    @Test
    void aNameCarryingTwoDefectiveOverloadsIsOneRowAtThePrecedingVerdict() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge", tableClass("customer"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "bridge",
                tableClass("customer"), "org.jooq.Table");
            seedBareConditionArgument(dsl, "district", CONDITIONS, "bridge");

            assertThat(defects(dsl))
                .as("one overload declares a second parameter, so the count test stands aside")
                .containsExactly("com.example.Conditions.bridge WILDCARD_TARGET_PARAMETER");
        });
    }

    /**
     * The partition with the rung: a pair that routes is not a defect, and a condition written
     * beside a key is not asked to route at all, so neither draws a row here.
     */
    @Test
    void aRoutedPairAndAConditionBesideAKeyAreBothNoDefect() {
        withCatalog(dsl -> {
            seedConditionMethod(dsl, JAR, CONDITIONS, "customerToAddress",
                tableClass("customer"), tableClass("address"));
            seedBareConditionArgument(dsl, "district", CONDITIONS, "customerToAddress");
            // An admitted set agreeing on its arrival routes, so it is not a defect either: the
            // pairing has to hold for a set exactly as it does for one declaration.
            seedConditionMethod(dsl, JAR, CONDITIONS, "agreeing",
                tableClass("customer"), tableClass("address"));
            seedConditionMethod(dsl, JAR, CONDITIONS, "agreeing",
                tableClass("address"), tableClass("address"));
            seedBareConditionArgument(dsl, "third", CONDITIONS, "agreeing");
            seedArgument(dsl, GRAPH, "Query", "customers", "other", "String");
            seedArgumentReference(dsl, GRAPH, "Query", "customers", "other", 0);
            seedArgumentReferenceElement(dsl, GRAPH, "Query", "customers", "other", 0, 0,
                null, "customer_address_id_fkey", CONDITIONS, "unknownMethod");

            assertThat(defects(dsl)).isEmpty();
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";
    private static final String CONDITIONS = "com.example.Conditions";

    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, PKG);
            seedGraphSource(dsl, GRAPH, JAR);
            for (String table : List.of("customer", "address")) {
                seedTable(dsl, PKG, PUBLIC, table);
            }
            seedField(dsl, GRAPH, "Query", "customers", "Customer", true);
            body.accept(dsl);
        });
    }

    private static String tableClass(String table) {
        return PKG + ".tables." + table;
    }

    private static void seedBareConditionArgument(DSLContext dsl, String argumentName,
                                                  String className, String method) {
        seedArgument(dsl, GRAPH, "Query", "customers", argumentName, "String");
        seedArgumentReference(dsl, GRAPH, "Query", "customers", argumentName, 0);
        seedArgumentReferenceCall(dsl, GRAPH, "Query", "customers", argumentName, 0, 0,
            className, method);
    }

    /** Each defect as "class.method VERDICT", which is the whole of what this relation says. */
    private static List<String> defects(DSLContext dsl) {
        derive(dsl);
        var d = INTENT_CONDITION_METHOD_ROUTE_DEFECT;
        return dsl.select(d.fields())
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH))
            .orderBy(d.CLASS_NAME, d.METHOD)
            .fetch(row -> row.get(d.CLASS_NAME) + "." + row.get(d.METHOD) + " "
                + row.get(d.VERDICT));
    }
}
