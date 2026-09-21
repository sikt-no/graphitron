package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphitronStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_EXCEPTION;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD_PARAMETER_TABLE;
import static no.sikt.graphitron.model.Tables.CODE_EXTERNAL_FIELD_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.CODE_SERVICE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four arms of the code family: what an author may name in {@code @scalarType(scalar:)}, the
 * throwables an {@code @error} handler may name, the methods {@code @condition(condition:)} may
 * name, and the lifters {@code @externalField(reference:)} may name.
 *
 * <p>What these pin is the arm's admission rule rather than a census's completeness. The
 * predecessor wrote every public class on the classpath and left the filtering to whoever read it;
 * the claim here is the opposite one, that a row means "this may be written at this directive", so
 * the cases are mostly about what is left out.
 */
class CodeCaptureTest {

    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final LocalDateTime SECOND = FIRST.plusMinutes(1);

    /** The graphql-java jar, which declares the scalars every schema starts from. */
    private static final List<ClasspathEntry> CLASSPATH = List.of(
        ClasspathEntry.project(Path.of("target", "classes")),
        new ClasspathEntry(graphqlJavaJar(), ClasspathEntry.Origin.DECLARED,
            "com.graphql-java:graphql-java"));

    /** Where the condition fixture is compiled to; this module's own output, so reactor-built. */
    private static final Path TEST_CLASSES = Path.of("target", "test-classes");

    private static final String FIXTURE =
        "no.sikt.graphitron.model.capture.code.fixtures.ConditionFixture";

    private static final String LIFTERS =
        "no.sikt.graphitron.model.capture.code.fixtures.ExternalFieldFixture";

    private static final String SERVICES =
        "no.sikt.graphitron.model.capture.code.fixtures.ServiceFixture";

    private static final String SLOT_RECORD =
        "no.sikt.graphitron.model.capture.code.fixtures.SlotRecord";

    private static final String SLOT_BEAN =
        "no.sikt.graphitron.model.capture.code.fixtures.SlotBean";

    /**
     * The reactor's own output beside a dependency that declares a great many condition methods.
     * jOOQ is the sharpest case the classpath has for the scope rule: every {@code Condition} the
     * arm must not admit is on it, so a scope that leaked would not merely be wrong, it would bury
     * the consumer's own methods under thousands.
     */
    private static final List<ClasspathEntry> REACTOR_AND_JOOQ = List.of(
        ClasspathEntry.project(TEST_CLASSES),
        new ClasspathEntry(jooqJar(), ClasspathEntry.Origin.DECLARED, "org.jooq:jooq"));

    /**
     * The same jar as {@link #CLASSPATH} carries, reclassified as something a declared dependency
     * dragged in. Every class the two classpath arms admit is on it, so an arm that reads by path
     * rather than by classification admits all of them and the difference is unmissable.
     */
    private static final List<ClasspathEntry> TRANSITIVE_GRAPHQL_JAVA = List.of(
        ClasspathEntry.project(Path.of("target", "classes")),
        new ClasspathEntry(graphqlJavaJar(), ClasspathEntry.Origin.TRANSITIVE,
            "com.graphql-java:graphql-java"));

    // ===== The corpus: which classpath entries an arm may read at all =====

    /**
     * An arm answers what may be written at a coordinate, so its corpus is bounded by what may be
     * written at all. A class reachable only through a transitive dependency may not: naming one is
     * the undeclared-dependency antipattern, {@code ClasspathEntry.Origin#TRANSITIVE} says the
     * census does not read such an entry, and the build refuses a schema that names a class from
     * one. An arm admitting it would offer an author a completion the build then rejects.
     *
     * <p>Asserted over the two classpath arms rather than the reactor ones. The reactor arms scope
     * themselves to what the build compiled, which excludes a transitive jar on the way to
     * excluding every other jar, so they would pass this whatever the corpus were. The two that
     * read beyond the reactor are the ones the classification is load-bearing for.
     */
    @Test
    @DisplayName("a transitive dependency is nobody's to name, so no arm reads its classes")
    void aTransitiveEntryIsNotRead() {
        withCapture(TRANSITIVE_GRAPHQL_JAVA, null, FIRST, dsl -> {
            assertThat(dsl.fetchCount(CODE_SCALAR_CONSTANT,
                    CODE_SCALAR_CONSTANT.CLASS_NAME.eq("graphql.Scalars")))
                .as("the constants an author may not name, because this jar is undeclared")
                .isZero();
            assertThat(dsl.fetchCount(CODE_THROWABLE,
                    CODE_THROWABLE.CLASS_NAME.eq("graphql.AssertException")))
                .as("and the throwables, on the same grounds")
                .isZero();
        });
    }

    /**
     * The entry registry says what the reading read, so an entry no arm may read draws no row
     * either. A row here would claim a source the store holds no facts from and cannot ever hold
     * any from, and {@code store_source} is what a currency scan walks.
     */
    @Test
    @DisplayName("an entry the reading never opens registers no source")
    void aTransitiveEntryRegistersNoSource() {
        withCapture(TRANSITIVE_GRAPHQL_JAVA, null, FIRST, dsl ->
            assertThat(dsl.fetchCount(STORE_SOURCE,
                    STORE_SOURCE.SOURCE_NAME.eq(graphqlJavaJar().toString())))
                .as("an entry read by nothing, registered by nothing")
                .isZero());
    }

    /**
     * The exclusion the caller passes is a package, and a package is a name plus a dot. Spelled as
     * a bare string prefix it also excludes every package and every class whose name merely starts
     * with those characters, which is not a corner: {@code graphql.Assert} is a class on this
     * classpath and {@code graphql.AssertException} is the throwable beside it, so a consumer whose
     * generated package is spelled like the shorter of the two loses the longer.
     *
     * <p>Both directions in one case, because only the pair says the rule. Excluding by a name that
     * is a prefix of a class must keep that class; excluding by a name that is genuinely the
     * package above it must still drop it, or the fix would have bought the parity by excluding
     * nothing.
     */
    @Test
    @DisplayName("the excluded package is a package, not any name starting with those characters")
    void theExclusionIsAPackageAndNotAPrefix() {
        withCapture(CLASSPATH, "graphql.Assert", FIRST, dsl ->
            assertThat(dsl.fetchCount(CODE_THROWABLE,
                    CODE_THROWABLE.CLASS_NAME.eq("graphql.AssertException")))
                .as("a class the excluded name is a prefix of, which is a different class")
                .isEqualTo(1));
        withCapture(CLASSPATH, "graphql", FIRST, dsl ->
            assertThat(dsl.fetchCount(CODE_THROWABLE,
                    CODE_THROWABLE.CLASS_NAME.eq("graphql.AssertException")))
                .as("and the package above it, which is the exclusion doing its job")
                .isZero());
    }

    @Test
    @DisplayName("a GraphQLScalarType constant is admitted, with the type its scalar coerces to")
    void aScalarConstantIsAdmitted() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.select(CODE_SCALAR_CONSTANT.FIELD_NAME, CODE_SCALAR_CONSTANT.INPUT_TYPE)
                    .from(CODE_SCALAR_CONSTANT)
                    .where(CODE_SCALAR_CONSTANT.CLASS_NAME.eq("graphql.Scalars"))
                    .and(CODE_SCALAR_CONSTANT.FIELD_NAME.eq("GraphQLInt"))
                    .fetchOne())
                .as("the engine's own Int constant, and what a value of it arrives as")
                .satisfies(row -> {
                    assertThat(row.value1()).isEqualTo("GraphQLInt");
                    assertThat(row.value2()).isEqualTo("java.lang.Integer");
                });
        });
    }

    /**
     * The admission is the point of the arm, so the case that discriminates it is a class that the
     * predecessor census would have written and this does not: one holding no scalar constant at
     * all. A relation keyed on what may be named has no row for it; an index of every public class
     * would.
     */
    @Test
    @DisplayName("a class declaring no scalar constant draws no row")
    void aClassWithNoConstantIsNotAdmitted() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.fetchCount(CODE_SCALAR_CONSTANT,
                    CODE_SCALAR_CONSTANT.CLASS_NAME.eq("no.sikt.graphitron.model.run.GraphitronStore")))
                .as("a class of this module, on the classpath and naming no scalar")
                .isZero();
            assertThat(dsl.fetchCount(CODE_SCALAR_CONSTANT))
                .as("and the arm is not empty, so the case above is a filter and not a failure")
                .isPositive();
        });
    }

    /** The entry a row hangs on keeps the classification its producer gave it. */
    @Test
    @DisplayName("the constants' own classpath entry is registered with its origin")
    void theEntryIsRegisteredWithItsOrigin() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.select(STORE_SOURCE.ORIGIN, STORE_SOURCE.COORDINATE)
                    .from(STORE_SOURCE)
                    .where(STORE_SOURCE.SOURCE_NAME.eq(graphqlJavaJar().toString()))
                    .fetchOne())
                .as("a declared dependency, told from this module's own output by its origin")
                .satisfies(row -> {
                    assertThat(row.value1()).isEqualTo("DECLARED");
                    assertThat(row.value2()).isEqualTo("com.graphql-java:graphql-java");
                });
        });
    }

    /**
     * The arm's whole difficulty in one case. A classfile names its superclass and stops, so the
     * chain above {@code AssertException} is readable from the jar for exactly one hop and leaves
     * the classpath at {@code RuntimeException}, which is in no entry. An arm that only read
     * classfiles would meet a class it could not prove was a throwable and write nothing.
     */
    @Test
    @DisplayName("a throwable is admitted with the ancestry its chain leaves the classpath for")
    void aThrowableIsAdmittedWithItsAncestry() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.fetchCount(CODE_THROWABLE,
                    CODE_THROWABLE.CLASS_NAME.eq("graphql.AssertException")))
                .as("a library throwable an @error handler could name").isEqualTo(1);
            assertThat(ancestorsOf(dsl, "graphql.AssertException"))
                .as("one hop inside the jar, and the rest read by loading what the jar points at")
                .contains("graphql.GraphQLException", "java.lang.RuntimeException",
                    "java.lang.Exception", "java.lang.Throwable", "java.lang.Object");
        });
    }

    /** The admission is a filter, as on the sibling arm: most of a classpath is not a throwable. */
    @Test
    @DisplayName("a class that is not a throwable draws no row")
    void aPlainClassIsNotAdmitted() {
        withCapture(FIRST, dsl -> {
            assertThat(dsl.fetchCount(CODE_THROWABLE,
                    CODE_THROWABLE.CLASS_NAME.eq("graphql.Scalars")))
                .as("a class on the same jar, holding scalars and extending nothing").isZero();
            assertThat(dsl.fetchCount(CODE_THROWABLE))
                .as("and the arm is not empty, so the case above is a filter and not a failure")
                .isPositive();
        });
    }

    /**
     * That the ancestry answers checked-ness is why it is stored rather than folded into a column,
     * so the derivation is pinned at the relation rather than left to whoever reads it first.
     */
    @Test
    @DisplayName("the closure is what says a throwable is unchecked")
    void theClosureSaysUnchecked() {
        withCapture(FIRST, dsl -> assertThat(ancestorsOf(dsl, "graphql.AssertException"))
            .as("java.lang.RuntimeException in the closure is exactly what unchecked means")
            .contains("java.lang.RuntimeException"));
    }

    @Test
    @DisplayName("reading twice restamps rather than doubling")
    void readingTwiceRestamps() {
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            CodeCapture.capture(dsl,
                ClasspathSourceCapture.read(dsl, CLASSPATH, null, FIRST), null, FIRST);
            int afterFirst = dsl.fetchCount(CODE_SCALAR_CONSTANT);
            int throwablesAfterFirst = dsl.fetchCount(CODE_THROWABLE);
            int ancestorsAfterFirst = dsl.fetchCount(CODE_THROWABLE_SUPERTYPE);
            CodeCapture.capture(dsl,
                ClasspathSourceCapture.read(dsl, CLASSPATH, null, SECOND), null, SECOND);

            assertThat(dsl.fetchCount(CODE_SCALAR_CONSTANT))
                .as("the same constants, not doubled and not emptied").isEqualTo(afterFirst);
            assertThat(dsl.fetchCount(CODE_SCALAR_CONSTANT,
                    CODE_SCALAR_CONSTANT.TOUCHED_AT.eq(FIRST)))
                .as("and none still carrying the older reading's instant").isZero();
            assertThat(dsl.fetchCount(CODE_THROWABLE))
                .as("the same throwables").isEqualTo(throwablesAfterFirst);
            assertThat(dsl.fetchCount(CODE_THROWABLE_SUPERTYPE))
                .as("and the same ancestry, which is swept on its own instant rather than by"
                    + " cascade and would empty if that sweep were wrong")
                .isEqualTo(ancestorsAfterFirst);
            assertThat(dsl.fetchCount(CODE_THROWABLE_SUPERTYPE,
                    CODE_THROWABLE_SUPERTYPE.TOUCHED_AT.eq(FIRST)))
                .as("none of it carrying the older reading's instant either").isZero();
        }
    }

    // ===== The condition arm: what a consumer may name at @condition(condition:) =====

    /**
     * The admission is the return type and nothing else, so the case that pins it is one class
     * carrying methods the arm must tell apart: overloads that qualify, one that returns something
     * else, and one that is not public. Stated as the whole set rather than a containment, because
     * what the arm leaves out is the half worth asserting.
     */
    @Test
    @DisplayName("a method returning a condition is admitted, its overloads told apart by descriptor")
    void aConditionMethodIsAdmitted() {
        withReactorCapture(dsl -> {
            assertThat(methodsOn(dsl, FIXTURE))
                .as("both overloads of the qualifying name, and neither of the two beside them")
                .containsExactlyInAnyOrder(
                    "titleContains(Ljava/lang/String;Ljava/lang/String;)Lorg/jooq/Condition;",
                    "titleContains(Ljava/lang/String;)Lorg/jooq/Condition;",
                    "nonStatic(Ljava/lang/String;)Lorg/jooq/Condition;",
                    "onAnyTable(Lorg/jooq/Table;Ljava/lang/String;)Lorg/jooq/Condition;",
                    "onOneTable(Lno/sikt/graphitron/model/capture/code/fixtures/"
                        + "ExternalFieldFixture$FixtureTable;Ljava/util/Map;)Lorg/jooq/Condition;",
                    "onAVariableTable(Lorg/jooq/Table;)Lorg/jooq/Condition;",
                    "onAnEnum(Lorg/jooq/Table;Lno/sikt/graphitron/model/config/"
                        + "ClasspathEntry$Origin;)Lorg/jooq/Condition;",
                    "onAnEnumArray(Lorg/jooq/Table;[Lno/sikt/graphitron/model/config/"
                        + "ClasspathEntry$Origin;)Lorg/jooq/Condition;");
        });
    }

    /**
     * Staticness is recorded and not required. An author can write a non-static condition method,
     * and the generator's refusal should be able to name it rather than behave as though no such
     * method existed.
     */
    @Test
    @DisplayName("a non-static condition method is a candidate, recorded as non-static")
    void staticnessIsRecordedNotRequired() {
        withReactorCapture(dsl -> assertThat(dsl.select(CODE_METHOD.IS_STATIC)
                .from(CODE_METHOD)
                .where(CODE_METHOD.CLASS_NAME.eq(FIXTURE))
                .and(CODE_METHOD.METHOD_NAME.eq("nonStatic"))
                .fetchOne(CODE_METHOD.IS_STATIC))
            .as("a candidate the generator may still refuse, told apart by this column")
            .isFalse());
    }

    /**
     * The parameters are the method's own fact, and this module is compiled without
     * {@code -parameters}, so it is also the case that pins what an absent name means: a compiler
     * flag, not an unnamed parameter.
     */
    @Test
    @DisplayName("a condition method's positions are recorded, nameless without -parameters")
    void parametersAreRecordedByPosition() {
        withReactorCapture(dsl -> {
            var rows = dsl.select(CODE_METHOD_PARAMETER.POSITION,
                    CODE_METHOD_PARAMETER.ROLE,
                    CODE_METHOD_PARAMETER.PARAMETER_NAME)
                .from(CODE_METHOD_PARAMETER)
                .where(CODE_METHOD_PARAMETER.CLASS_NAME.eq(FIXTURE))
                .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq("titleContains"))
                .and(CODE_METHOD_PARAMETER.DESCRIPTOR
                    .eq("(Ljava/lang/String;Ljava/lang/String;)Lorg/jooq/Condition;"))
                .orderBy(CODE_METHOD_PARAMETER.POSITION)
                .fetch();

            assertThat(rows).as("both positions, in order").hasSize(2);
            assertThat(rows.get(0).value1()).isZero();
            assertThat(rows.get(0).value2())
                .as("a value position, whose role at a site is the site's to decide")
                .isEqualTo("OTHER");
            assertThat(rows).as("this module compiles without -parameters, so no position is named")
                .allSatisfy(row -> assertThat(row.value3()).isNull());
        });
    }

    /**
     * The scope rule, against the one dependency that would bury the consumer's own methods if it
     * leaked. jOOQ declares {@code Condition} returns by the thousand and is a declared dependency,
     * so none of them is a candidate.
     */
    @Test
    @DisplayName("a declared dependency's condition methods are not the reactor's to admit")
    void onlyTheReactorIsAdmitted() {
        withReactorCapture(dsl -> {
            assertThat(dsl.fetchCount(CODE_CONDITION_METHOD,
                    CODE_CONDITION_METHOD.SOURCE_NAME.eq(jooqJar().toString())))
                .as("jOOQ's own condition-returning methods, which an author may not name here")
                .isZero();
            assertThat(dsl.fetchCount(CODE_CONDITION_METHOD))
                .as("and the reactor's are, so the case above is a scope and not an empty capture")
                .isPositive();
        });
    }

    /**
     * The three roles over one fixture, which is what a reader asking "does this position take the
     * source table" gets to read instead of climbing an ancestry. Every parameter of every admitted
     * method is asserted rather than three chosen ones, so a role decided wrongly for a shape this
     * fixture happens to carry cannot pass by not being looked at.
     */
    @Test
    @DisplayName("each parameter position says what it is for")
    void eachPositionCarriesItsRole() {
        withReactorCapture(dsl -> {
            assertThat(rolesOn(dsl, "onAnyTable"))
                .as("the bare interface takes any table, and a value is not a table at all")
                .containsExactly("TABLE_ANY", "OTHER");
            assertThat(rolesOn(dsl, "onOneTable"))
                .as("a generated table class names one table")
                .containsExactly("TABLE_CONCRETE", "OTHER");
            assertThat(rolesOn(dsl, "titleContains"))
                .as("a method with no table position at all, which the arm still admits")
                .containsOnly("OTHER");
        });
    }

    /**
     * A type variable erases to its bound, so the erasure reads {@code org.jooq.Table} where the
     * source named no class at all. The role follows the declaration, which is the answer an author
     * reading their own signature would give.
     */
    @Test
    @DisplayName("a type variable takes any table, whatever its erasure says")
    void aVariableTakesAnyTable() {
        withReactorCapture(dsl -> {
            assertThat(rolesOn(dsl, "onAVariableTable"))
                .as("the declaration names no class, so the position is bound to no one table")
                .containsExactly("TABLE_ANY");
        });
    }


    /**
     * The coercion a bound value takes, by the declared type alone. Six shapes in one case, because
     * the rule is one predicate and what makes it worth stating is everything it answers DIRECT
     * for: a plain class, a parameterised type read at its raw head, an array whose component is an
     * enum, and a position naming no class at all.
     *
     * <p>The enum is a nested one on purpose. The reading skips a nested class by name, so no row
     * here describes it and only a loader can say it is an enum. That is the same reach a generated
     * jOOQ enum needs, which is the population the view this replaced had to carry a second arm for
     * and still missed nested ones on.
     */
    @Test
    @DisplayName("an enum coerces by valueOf and everything else directly")
    void theExtractionIsTheDeclaredTypesAnswer() {
        withReactorCapture(dsl -> {
            assertThat(extractionsOn(dsl, "onAnEnum"))
                .as("a nested enum no row describes, answered by loading it")
                .containsExactly("DIRECT", "ENUM_VALUE_OF");
            assertThat(extractionsOn(dsl, "onAnEnumArray"))
                .as("an array of that enum is not an enum; its component is a step down")
                .containsExactly("DIRECT", "DIRECT");
            assertThat(extractionsOn(dsl, "onOneTable"))
                .as("a parameterised type is read at its raw head, which is no enum")
                .containsExactly("DIRECT", "DIRECT");
            assertThat(extractionsOn(dsl, "onAVariableTable"))
                .as("a type variable names no class, so there is nothing to ask")
                .containsExactly("DIRECT");
            assertThat(extractionsOn(dsl, "titleContains"))
                .as("and the ordinary case, so the ones above are a rule and not an empty capture")
                .containsOnly("DIRECT");
        });
    }

    /**
     * The clause is captured because the condition path reads it: a set of same-named declarations
     * that disagree on it is refused by name rather than being one target.
     */
    @Test
    @DisplayName("a declared exception is a row of the method's own")
    void theThrowsClauseIsCaptured() {
        withReactorCapture(dsl -> {
            assertThat(dsl.select(CODE_METHOD_EXCEPTION.EXCEPTION_CLASS)
                    .from(CODE_METHOD_EXCEPTION)
                    .where(CODE_METHOD_EXCEPTION.METHOD_NAME.eq("onOneTable"))
                    .fetch(CODE_METHOD_EXCEPTION.EXCEPTION_CLASS))
                .as("what the method declares it throws")
                .containsExactly("java.io.IOException");
            assertThat(dsl.fetchCount(CODE_METHOD_EXCEPTION,
                    CODE_METHOD_EXCEPTION.METHOD_NAME.eq("onAnyTable")))
                .as("and a method declaring none has no rows rather than an empty one")
                .isZero();
        });
    }

    /**
     * A concrete position names one table and the catalog is what says which. The role is the
     * declaration's answer and stands whether or not the catalog holds the class, so the two are
     * separate rows: this capture has no catalog behind it, and the position is still concrete.
     */
    @Test
    @DisplayName("a concrete position the catalog cannot place is concrete and unresolved")
    void aConcretePositionWithoutACatalogResolvesToNothing() {
        withReactorCapture(dsl -> {
            assertThat(rolesOn(dsl, "onOneTable")).containsExactly("TABLE_CONCRETE", "OTHER");
            assertThat(dsl.fetchCount(CODE_CONDITION_METHOD_PARAMETER_TABLE))
                .as("no catalog was read, so no position resolved to a table")
                .isZero();
        });
    }

    // ===== The service arm: what a consumer may name at @service(service:) =====

    /**
     * The arm admits by exclusion, which is unlike every other arm here and is what the directive
     * makes it. The generator picks a service method by name and applies no filter, so what makes
     * one a candidate is stated rather than read off, and what it leaves out is the members that
     * are answers to a different question.
     */
    @Test
    @DisplayName("a method that answers another directive is not a service candidate")
    void theOtherArmsMembersAreNotServiceCandidates() {
        withReactorCapture(dsl -> {
            assertThat(serviceMethodsOn(dsl, FIXTURE))
                .as("the condition fixture's condition-returning methods are the other arm's,"
                    + " and the one method on it that returns something else is not, which is what"
                    + " makes the exclusion a method's and not a class's")
                .doesNotContain("titleContains", "onAnyTable", "onOneTable")
                .containsExactly("notACondition");
            assertThat(serviceMethodsOn(dsl, LIFTERS))
                .as("the lifters are the other arm's; what is left of that class is not a lifter")
                .doesNotContain("titleUpper", "byInterface")
                .contains("notATable", "notAField");
            assertThat(serviceMethodsOn(dsl, SERVICES))
                .as("and an ordinary class's methods are candidates, all of them")
                .contains("manyStrings", "oneString", "nothingAtAll");
        });
    }

    /**
     * What a service delivers, which is the question a field backed by one turns on. Every shape in
     * one case, because the walk is one loop and what makes it worth stating is the set of places
     * it stops: at a type that is not a container, and at a container whose payload position names
     * nothing.
     */
    @Test
    @DisplayName("a return type delivers what is left when its containers are peeled off")
    void theReturnDeliversItsElement() {
        withReactorCapture(dsl -> {
            assertThat(deliveryOf(dsl, "manyStrings"))
                .as("a list delivers its element, many of them")
                .isEqualTo("java.lang.String many");
            assertThat(deliveryOf(dsl, "maybeAString"))
                .as("an optional delivers its element, one of it")
                .isEqualTo("java.lang.String one");
            assertThat(deliveryOf(dsl, "stringsByKey"))
                .as("a map delivers its value and not its key")
                .isEqualTo("java.lang.String one");
            assertThat(deliveryOf(dsl, "oneString"))
                .as("a type that is no container delivers itself")
                .isEqualTo("java.lang.String one");
            assertThat(deliveryOf(dsl, "anythingAtAll"))
                .as("an unbounded wildcard names nothing, so the walk stops at the container")
                .isEqualTo("java.util.List one");
        });
    }

    /**
     * The bound the rule had in SQL and does not have here. Peeling containers by self-joining a
     * type-reference relation has to be unrolled, so it answers to a depth and then reports the
     * container it stopped on as though it were the payload. Five is one past the four the unrolled
     * form reaches.
     */
    @Test
    @DisplayName("the walk has no depth to stop at")
    void theWalkIsNotUnrolled() {
        withReactorCapture(dsl ->
            assertThat(deliveryOf(dsl, "deeplyNested"))
                .as("five containers peeled, where an unrolled form reports the fifth")
                .isEqualTo("java.lang.String many"));
    }

    /** A return that names no class delivers nothing, and absence is how that is said. */
    @Test
    @DisplayName("a return naming no class has no delivery at all")
    void aReturnNamingNoClassDeliversNothing() {
        withReactorCapture(dsl -> {
            assertThat(deliveryOf(dsl, "aCount")).as("a primitive").isNull();
            assertThat(deliveryOf(dsl, "nothingAtAll")).as("and a void").isNull();
            assertThat(dsl.fetchCount(CODE_SERVICE_METHOD,
                    CODE_SERVICE_METHOD.CLASS_NAME.eq(SERVICES)
                        .and(CODE_SERVICE_METHOD.METHOD_NAME.eq("aCount"))))
                .as("while the method itself is a candidate, which is what absence must not hide")
                .isEqualTo(1);
        });
    }

    /** A parameter is asked the same question, and the context slot is decided by type. */
    @Test
    @DisplayName("a parameter delivers on the same terms, and the context slot is one by type")
    void parametersDeliverAndTheContextSlotIsTyped() {
        withReactorCapture(dsl -> {
            assertThat(dsl.select(CODE_METHOD_PARAMETER.ROLE)
                    .from(CODE_METHOD_PARAMETER)
                    .where(CODE_METHOD_PARAMETER.CLASS_NAME.eq(SERVICES))
                    .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq("fromManyInputs"))
                    .orderBy(CODE_METHOD_PARAMETER.POSITION)
                    .fetch(CODE_METHOD_PARAMETER.ROLE))
                .as("the run's own context first, then a position the site decides")
                .containsExactly("DSL_CONTEXT", "OTHER");
            assertThat(parameterDeliveryOf(dsl, "fromManyInputs", 1))
                .as("and the position typed as a list of them delivers the element")
                .isEqualTo("java.lang.String many");
        });
    }

    /** The clause is the service arm's too, for the @error channel-coverage check. */
    @Test
    @DisplayName("a service's declared exceptions are rows of its own")
    void theServiceThrowsClauseIsCaptured() {
        withReactorCapture(dsl -> {
            assertThat(dsl.select(CODE_METHOD_EXCEPTION.EXCEPTION_CLASS)
                    .from(CODE_METHOD_EXCEPTION)
                    .where(CODE_METHOD_EXCEPTION.CLASS_NAME.eq(SERVICES))
                    .and(CODE_METHOD_EXCEPTION.METHOD_NAME.eq("mayFail"))
                    .fetch(CODE_METHOD_EXCEPTION.EXCEPTION_CLASS))
                .as("what a field naming this method owes a handler for")
                .containsExactly("java.io.IOException");
            assertThat(dsl.fetchCount(CODE_METHOD_EXCEPTION,
                    CODE_METHOD_EXCEPTION.CLASS_NAME.eq(SERVICES)
                        .and(CODE_METHOD_EXCEPTION.METHOD_NAME.eq("oneString"))))
                .as("and a method declaring none has no rows rather than an empty one")
                .isZero();
        });
    }

    // ===== The slots: what a class offers @field(name:) =====

    /**
     * A record answers with its components, and the methods it generates beside them are the case
     * that matters. {@code toString}, {@code hashCode} and {@code equals} are public,
     * non-synthetic and take no argument, so a rule reading methods rather than the record
     * attribute would offer an author {@code toString} as a member of every record in the reactor.
     */
    @Test
    @DisplayName("a record offers its components and not what it generates beside them")
    void aRecordOffersItsComponents() {
        withReactorCapture(dsl -> {
            assertThat(slotsOn(dsl, SLOT_RECORD))
                .as("the three components, each read by the accessor of its own name")
                .containsExactlyInAnyOrder("title", "year", "tags");
            assertThat(originsOn(dsl, SLOT_RECORD))
                .as("and the arm is the class's, chosen by its declared form")
                .containsOnly("RECORD_COMPONENT");
        });
    }

    /**
     * The other arm is a rule about the name and not about the type: {@code get} or {@code is}
     * followed by an upper-case letter offers the remainder with its first letter lowered. Two
     * spellings of one property are two rows, which is what the key being the accessor buys.
     */
    @Test
    @DisplayName("a class that is no record offers what its getters name, twice where spelled twice")
    void aBeanOffersItsProperties() {
        withReactorCapture(dsl -> {
            assertThat(slotsOn(dsl, SLOT_BEAN))
                .as("both spellings of title, and tags; not the prefixless one, not the one that"
                    + " takes an argument, and not a bare get")
                .containsExactlyInAnyOrder("title", "title", "tags");
            assertThat(originsOn(dsl, SLOT_BEAN)).containsOnly("BEAN_ACCESSOR");
            assertThat(dsl.select(CODE_TYPE_SLOT.METHOD_NAME)
                    .from(CODE_TYPE_SLOT)
                    .where(CODE_TYPE_SLOT.CLASS_NAME.eq(SLOT_BEAN))
                    .and(CODE_TYPE_SLOT.SLOT_NAME.eq("title"))
                    .orderBy(CODE_TYPE_SLOT.METHOD_NAME)
                    .fetch(CODE_TYPE_SLOT.METHOD_NAME))
                .as("one slot name, two accessors, which is why the accessor is the key")
                .containsExactly("getTitle", "isTitle");
        });
    }

    /**
     * A slot carries no type of its own: it is read by a method, and that method's result already
     * names one. So what a slot offers is reached through the method rather than restated here.
     */
    @Test
    @DisplayName("what a slot carries is its accessor's result")
    void aSlotCarriesItsAccessorsResult() {
        withReactorCapture(dsl -> {
            var m = CODE_METHOD;
            var t = CODE_TYPE;
            assertThat(dsl.select(t.DISPLAY_NAME)
                    .from(CODE_TYPE_SLOT)
                    .join(m).on(m.SOURCE_NAME.eq(CODE_TYPE_SLOT.SOURCE_NAME),
                        m.CLASS_NAME.eq(CODE_TYPE_SLOT.CLASS_NAME),
                        m.METHOD_NAME.eq(CODE_TYPE_SLOT.METHOD_NAME),
                        m.DESCRIPTOR.eq(CODE_TYPE_SLOT.DESCRIPTOR))
                    .join(t).on(t.SOURCE_NAME.eq(m.SOURCE_NAME), t.TYPE_NAME.eq(m.RESULT_TYPE))
                    .where(CODE_TYPE_SLOT.CLASS_NAME.eq(SLOT_BEAN))
                    .and(CODE_TYPE_SLOT.SLOT_NAME.eq("tags"))
                    .fetchOne(t.DISPLAY_NAME))
                .as("rendered for a person, packages dropped and type arguments kept")
                .isEqualTo("List<String>");
        });
    }

    // ===== The externalField arm: what a consumer may name at @externalField(reference:) =====

    /**
     * The whole contract, admitted at once. Both spellings of a lifter qualify: one reaching
     * {@code Table} through a superclass chain, as a generated table class does, and one naming the
     * interface outright.
     */
    @Test
    @DisplayName("a lifter is admitted whether it names a table class or the table interface")
    void aLifterIsAdmitted() {
        withReactorCapture(dsl -> assertThat(liftersOn(dsl, LIFTERS))
            .as("the two methods satisfying every clause of the contract")
            .containsExactlyInAnyOrder("titleUpper", "byInterface"));
    }

    /**
     * The case the relation exists for. A method taking one argument and returning a
     * {@code Field} is the shape an editor with nothing to ask has to settle for, and this one is
     * not a lifter: its argument is not a table. The arm says so where a shape cannot.
     */
    @Test
    @DisplayName("a one-argument Field-returning method whose argument is no table is not a lifter")
    void theShapeIsNotTheContract() {
        withReactorCapture(dsl -> assertThat(liftersOn(dsl, LIFTERS))
            .as("the shape matches and the contract does not, so it is left out")
            .doesNotContain("notATable"));
    }

    /**
     * The other three clauses, one case each rather than one case listing them.
     *
     * <p>Worth the three methods: the contract is a conjunction, and a case per clause is what
     * makes a regression name itself. Asserted together, any clause going missing fails the same
     * test, so the failure says the arm admits too much and not which rule stopped holding.
     */
    @Test
    @DisplayName("an instance method is not a lifter, however well its signature reads")
    void aNonStaticMethodIsNotAdmitted() {
        withReactorCapture(dsl -> assertThat(liftersOn(dsl, LIFTERS)).doesNotContain("notStatic"));
    }

    @Test
    @DisplayName("a method returning something other than a Field is not a lifter")
    void aMethodReturningNoFieldIsNotAdmitted() {
        withReactorCapture(dsl -> assertThat(liftersOn(dsl, LIFTERS)).doesNotContain("notAField"));
    }

    @Test
    @DisplayName("a lifter takes the table and nothing else, so a second parameter disqualifies it")
    void aTwoParameterMethodIsNotAdmitted() {
        withReactorCapture(dsl -> assertThat(liftersOn(dsl, LIFTERS)).doesNotContain("twoParameters"));
    }

    /** The table lifted from is carried, being the one clause the site rather than the method decides. */
    @Test
    @DisplayName("the table a lifter lifts from is the sole position's role")
    void theTableParameterIsRecorded() {
        withReactorCapture(dsl -> assertThat(dsl.select(CODE_METHOD_PARAMETER.ROLE)
                .from(CODE_METHOD_PARAMETER)
                .where(CODE_METHOD_PARAMETER.CLASS_NAME.eq(LIFTERS))
                .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq("titleUpper"))
                .and(CODE_METHOD_PARAMETER.POSITION.eq(0))
                .fetchOne(CODE_METHOD_PARAMETER.ROLE))
            .as("a generated table class names one table, which is what the site compares against")
            .isEqualTo("TABLE_CONCRETE"));
    }

    private static void withCapture(LocalDateTime at, Consumer<DSLContext> body) {
        withCapture(CLASSPATH, null, at, body);
    }

    /** One reading of {@code entries} under {@code skipPrefix}, which is what a run hands in. */
    private static void withCapture(List<ClasspathEntry> entries, String skipPrefix,
                                    LocalDateTime at, Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            CodeCapture.capture(store.dsl(),
                ClasspathSourceCapture.read(store.dsl(), entries, skipPrefix, at), null, at);
            body.accept(store.dsl());
        }
    }

    /** Every type one throwable is, as the store holds it. */
    private static List<String> ancestorsOf(DSLContext dsl, String className) {
        return dsl.select(CODE_THROWABLE_SUPERTYPE.SUPERTYPE_NAME)
            .from(CODE_THROWABLE_SUPERTYPE)
            .where(CODE_THROWABLE_SUPERTYPE.CLASS_NAME.eq(className))
            .fetch(CODE_THROWABLE_SUPERTYPE.SUPERTYPE_NAME);
    }

    /** Where the graphql-java jar sits on this JVM's own classpath. */
    private static Path graphqlJavaJar() {
        for (String element : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (element.contains("graphql-java") && element.endsWith(".jar")) {
                return Path.of(element);
            }
        }
        throw new AssertionError("graphql-java is a compile dependency of this module and must be on "
            + "the test classpath; this arm has nothing to read without it");
    }

    /** Captures the reactor fixture beside jOOQ, which is the scope rule's own case. */
    private static void withReactorCapture(Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            CodeCapture.capture(store.dsl(),
                ClasspathSourceCapture.read(store.dsl(), REACTOR_AND_JOOQ, null, FIRST), null, FIRST);
            body.accept(store.dsl());
        }
    }

    /** What one method results in, rendered for comparison, or null where its type names none. */
    private static String deliveryOf(DSLContext dsl, String methodName) {
        var e = CODE_TYPE_ELEMENT;
        var m = CODE_METHOD;
        return dsl.select(e.ELEMENT_CLASS, e.IS_MANY)
            .from(m)
            .join(e).on(e.SOURCE_NAME.eq(m.SOURCE_NAME), e.TYPE_NAME.eq(m.RESULT_TYPE))
            .where(m.CLASS_NAME.eq(SERVICES))
            .and(m.METHOD_NAME.eq(methodName))
            .fetchOne(row -> row.value1() + (row.value2() ? " many" : " one"));
    }

    /** What one position's type resolves to, on {@link #deliveryOf}'s terms. */
    private static String parameterDeliveryOf(DSLContext dsl, String methodName, int position) {
        var e = CODE_TYPE_ELEMENT;
        var p = CODE_METHOD_PARAMETER;
        return dsl.select(e.ELEMENT_CLASS, e.IS_MANY)
            .from(p)
            .join(e).on(e.SOURCE_NAME.eq(p.SOURCE_NAME), e.TYPE_NAME.eq(p.PARAMETER_TYPE))
            .where(p.CLASS_NAME.eq(SERVICES))
            .and(p.METHOD_NAME.eq(methodName))
            .and(p.POSITION.eq(position))
            .fetchOne(row -> row.value1() + (row.value2() ? " many" : " one"));
    }

    /** The slot names one class offers. */
    private static List<String> slotsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_TYPE_SLOT.SLOT_NAME)
            .from(CODE_TYPE_SLOT)
            .where(CODE_TYPE_SLOT.CLASS_NAME.eq(className))
            .fetch(CODE_TYPE_SLOT.SLOT_NAME);
    }

    /** The arms those slots came from, which is a fact about the class. */
    private static List<String> originsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_TYPE_SLOT.ORIGIN)
            .from(CODE_TYPE_SLOT)
            .where(CODE_TYPE_SLOT.CLASS_NAME.eq(className))
            .fetch(CODE_TYPE_SLOT.ORIGIN);
    }

    /** The service candidates one class declares, by name. */
    private static List<String> serviceMethodsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_SERVICE_METHOD.METHOD_NAME)
            .from(CODE_SERVICE_METHOD)
            .where(CODE_SERVICE_METHOD.CLASS_NAME.eq(className))
            .fetch(CODE_SERVICE_METHOD.METHOD_NAME);
    }

    /** The extractions of one method's positions, in parameter order. */
    private static List<String> extractionsOn(DSLContext dsl, String methodName) {
        return dsl.select(CODE_METHOD_PARAMETER.EXTRACTION)
            .from(CODE_METHOD_PARAMETER)
            .where(CODE_METHOD_PARAMETER.CLASS_NAME.eq(FIXTURE))
            .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq(methodName))
            .orderBy(CODE_METHOD_PARAMETER.POSITION)
            .fetch(CODE_METHOD_PARAMETER.EXTRACTION);
    }

    /** The roles of one method's positions, in parameter order. */
    private static List<String> rolesOn(DSLContext dsl, String methodName) {
        return dsl.select(CODE_METHOD_PARAMETER.ROLE)
            .from(CODE_METHOD_PARAMETER)
            .where(CODE_METHOD_PARAMETER.CLASS_NAME.eq(FIXTURE))
            .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq(methodName))
            .orderBy(CODE_METHOD_PARAMETER.POSITION)
            .fetch(CODE_METHOD_PARAMETER.ROLE);
    }

    /** The condition methods one class declares, spelled as name plus descriptor. */
    private static List<String> methodsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_CONDITION_METHOD.METHOD_NAME, CODE_CONDITION_METHOD.DESCRIPTOR)
            .from(CODE_CONDITION_METHOD)
            .where(CODE_CONDITION_METHOD.CLASS_NAME.eq(className))
            .fetch(row -> row.value1() + row.value2());
    }

    /** Where the jOOQ jar sits on this JVM's own classpath. */
    private static Path jooqJar() {
        for (String element : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            String name = Path.of(element).getFileName().toString();
            if (name.startsWith("jooq-") && name.endsWith(".jar") && !name.contains("meta")
                && !name.contains("codegen")) {
                return Path.of(element);
            }
        }
        throw new AssertionError("jOOQ is a compile dependency of this module and must be on the "
            + "test classpath; the condition arm's scope case has nothing to exclude without it");
    }

    /** The lifter method names one class declares. */
    private static List<String> liftersOn(DSLContext dsl, String className) {
        return dsl.select(CODE_EXTERNAL_FIELD_METHOD.METHOD_NAME)
            .from(CODE_EXTERNAL_FIELD_METHOD)
            .where(CODE_EXTERNAL_FIELD_METHOD.CLASS_NAME.eq(className))
            .fetch(CODE_EXTERNAL_FIELD_METHOD.METHOD_NAME);
    }
}
