package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.GraphitronStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.jooq.DSLContext;

import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_CLASS;
import static no.sikt.graphitron.model.Tables.CODE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_EXCEPTION;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.CODE_CONDITION_METHOD_PARAMETER_TABLE;
import static no.sikt.graphitron.model.Tables.CODE_EXTERNAL_FIELD_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.CODE_SERVICE_METHOD;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.CODE_WRITE_SLOT;
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

    private static final String SLOT_INTERFACE =
        "no.sikt.graphitron.model.capture.code.fixtures.SlotInterface";

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
     * The class anchor is the whole reading, where the arms above it are the reactor's. A jOOQ
     * interface on a declared jar is a class an author can name, so whether the reading reached it
     * is a fact about the classpath rather than about what this build compiled.
     *
     * <p>Both halves in one case, because either alone would pass for the wrong reason. The row
     * being present says the anchor is not scoped; the method rows being absent beside it says the
     * arms still are, so a scope that had leaked upward would be caught here rather than read as
     * this rule working.
     *
     * <p>What the pair buys a reader is the difference between "no such method on that class" and
     * "no such class", which is a distinction no relation of candidates can draw by itself and
     * which a reader standing a verdict down on a half-captured store depends on.
     */
    @Test
    @DisplayName("every class read is anchored, including the ones no arm may admit")
    void theClassAnchorIsTheWholeReading() {
        withReactorCapture(dsl -> {
            assertThat(dsl.fetchCount(CODE_CLASS,
                    CODE_CLASS.CLASS_NAME.eq("org.jooq.Condition")))
                .as("a class on a declared jar is a class the reading reached")
                .isEqualTo(1);
            assertThat(dsl.fetchCount(CODE_METHOD,
                    CODE_METHOD.CLASS_NAME.eq("org.jooq.Condition")))
                .as("while what it declares stays the reactor's question, so nothing is admitted")
                .isZero();
            assertThat(dsl.fetchCount(CODE_CLASS, CODE_CLASS.CLASS_NAME.eq(SERVICES)))
                .as("and the reactor's own classes are anchored on the same terms")
                .isEqualTo(1);
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
                .as("both overloads of the qualifying name, the boolean field beside them, and"
                    + " none of the methods returning something else")
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
                        + "ClasspathEntry$Origin;)Lorg/jooq/Condition;",
                    "titleMatches(Ljava/lang/String;)Lorg/jooq/Field;");
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
                .doesNotContain("titleContains", "onAnyTable", "onOneTable", "titleMatches")
                .containsExactlyInAnyOrder("notACondition", "rawField", "notABooleanField");
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
                .isEqualTo("java.lang.String MANY");
            assertThat(deliveryOf(dsl, "maybeAString"))
                .as("an optional delivers one of its element, and says it was wrapped: the"
                    + " value cannot be handed over as it stands, which DIRECT would have claimed")
                .isEqualTo("java.lang.String WRAPPED");
            assertThat(deliveryOf(dsl, "stringsByKey"))
                .as("a map delivers its value and not its key, wrapped on the same terms")
                .isEqualTo("java.lang.String WRAPPED");
            assertThat(deliveryOf(dsl, "oneString"))
                .as("a type that is no container is handed over as it stands")
                .isEqualTo("java.lang.String DIRECT");
            assertThat(deliveryOf(dsl, "anythingAtAll"))
                .as("an unbounded wildcard names nothing, so the walk stops at the container and"
                    + " hands that over directly, having peeled nothing")
                .isEqualTo("java.util.List DIRECT");
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
                .isEqualTo("java.lang.String MANY"));
    }

    /**
     * What a type is, beside what it delivers. The two answer different questions and the cases
     * that matter are the ones where they differ: a list of strings is a list, and delivers
     * strings. A reader comparing a parameter against a class it must accept wants the first, and
     * a reader asking whether one value can be handed over wants the second, so a model carrying
     * only the peel would have made the first reader descend to an answer it does not want.
     */
    @Test
    @DisplayName("a type names a class at its root, which is not what it delivers")
    void theRootIsNotTheElement() {
        withReactorCapture(dsl -> {
            assertThat(rootOf(dsl, "manyStrings"))
                .as("the list is what the position is, whatever it holds")
                .isEqualTo("java.util.List");
            assertThat(rootOf(dsl, "oneString"))
                .as("and the two agree exactly where nothing was peeled")
                .isEqualTo("java.lang.String");
            assertThat(rootOf(dsl, "anythingAtAll"))
                .as("an unbounded wildcard leaves the container as both")
                .isEqualTo("java.util.List");
            assertThat(rootOf(dsl, "deeplyNested"))
                .as("and depth changes nothing here, the root being where a declaration starts")
                .isEqualTo("java.util.concurrent.CompletableFuture");
        });
    }

    /**
     * A root that names no class is absent rather than spelled, on the terms the delivery beside
     * it is. The spelling is still there to be read, which is what tells the silences apart for a
     * reader that has to: this column does not, and says so by being null for both.
     */
    @Test
    @DisplayName("a type naming no class at its root says so with a null")
    void aTypeNamingNoClassHasNoRoot() {
        withReactorCapture(dsl -> {
            assertThat(rootOf(dsl, "aCount")).as("a primitive").isNull();
            assertThat(rootOf(dsl, "nothingAtAll")).as("and a void").isNull();
            assertThat(spellingOf(dsl, "aCount"))
                .as("while the spelling still tells one from the other")
                .isEqualTo("int");
            assertThat(spellingOf(dsl, "nothingAtAll")).isEqualTo("void");
        });
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
                .isEqualTo("java.lang.String MANY");
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
                .as("the three components, each read by the accessor of its own name, and the"
                    + " hand-written getTitle beside them contributing nothing")
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
                .as("both spellings of title, and the three the other names offer; not the"
                    + " prefixless one, not the one that takes an argument, not a bare get, and"
                    + " not the one whose prefix is followed by a lower-case letter")
                .containsExactlyInAnyOrder("title", "title", "tags", "restricted", "uRL");
            assertThat(originsOn(dsl, SLOT_BEAN)).containsOnly("BEAN_ACCESSOR");
            assertThat(dsl.select(CODE_READ_SLOT.METHOD_NAME)
                    .from(CODE_READ_SLOT)
                    .where(CODE_READ_SLOT.CLASS_NAME.eq(SLOT_BEAN))
                    .and(CODE_READ_SLOT.SLOT_NAME.eq("title"))
                    .orderBy(CODE_READ_SLOT.METHOD_NAME)
                    .fetch(CODE_READ_SLOT.METHOD_NAME))
                .as("one slot name, two accessors, which is why the accessor is the key")
                .containsExactly("getTitle", "isTitle");
        });
    }

    /**
     * The bean arm is chosen by "not a record" and not by "a class", so an interface answers with
     * its accessors on the same terms. A type an author backs with an interface offers the same
     * members, and the declared form the reading records for one is INTERFACE.
     */
    @Test
    @DisplayName("an interface answers like anything else that is not a record")
    void anInterfaceAnswersLikeAnyNonRecord() {
        withReactorCapture(dsl -> {
            assertThat(slotsOn(dsl, SLOT_INTERFACE)).containsExactly("name");
            assertThat(originsOn(dsl, SLOT_INTERFACE)).containsExactly("BEAN_ACCESSOR");
        });
    }

    /**
     * What a slot yields is spelled as its accessor's result spells it, rendered the same way for
     * a record component as for a getter.
     */
    @Test
    @DisplayName("what a slot carries is its accessor's result")
    void aSlotCarriesItsAccessorsResult() {
        withReactorCapture(dsl -> {
            var t = CODE_TYPE;
            assertThat(dsl.select(t.DISPLAY_NAME)
                    .from(CODE_READ_SLOT)
                    .join(t).on(t.SOURCE_NAME.eq(CODE_READ_SLOT.SOURCE_NAME),
                        t.TYPE_NAME.eq(CODE_READ_SLOT.SLOT_TYPE))
                    .where(CODE_READ_SLOT.CLASS_NAME.eq(SLOT_BEAN))
                    .and(CODE_READ_SLOT.SLOT_NAME.eq("tags"))
                    .fetchOne(t.DISPLAY_NAME))
                .as("rendered for a person, packages dropped and type arguments kept")
                .isEqualTo("List<String>");
            assertThat(displayTypeOf(dsl, SLOT_BEAN, "restricted"))
                .as("a primitive is its own spelling and has no package to drop")
                .isEqualTo("boolean");
            assertThat(displayTypeOf(dsl, SLOT_RECORD, "year"))
                .as("and a record component is rendered from its accessor's result alike")
                .isEqualTo("int");
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

    // ===== Forgetting: an entry gone from disk takes what was read from it =====

    /**
     * An entry the filesystem no longer has is forgotten, and every row read from it goes with its
     * source row. The reading asks the filesystem rather than the classpath, an entry one run stopped
     * naming being one another graph may still read, so the case removes the directory itself.
     *
     * <p>Two entries rather than one, because a reading handed no classpath says nothing about one
     * and forgets nothing. The entry that stays is the control: it proves the second reading ran, so
     * an empty store is not what makes the gone entry's rows absent.
     *
     * <p>The gone entry holds types on purpose. The type dictionary keys the entry without a
     * cascade, so it is the relation that could refuse the deletion rather than follow it.
     */
    @Test
    @DisplayName("an entry gone from disk is forgotten, and every row read from it goes with it")
    void anEntryGoneFromDiskIsForgotten(@TempDir Path dir) throws IOException {
        Path gone = fixtureEntry(dir.resolve("gone"), SLOT_RECORD);
        Path stays = fixtureEntry(dir.resolve("stays"), SLOT_BEAN);
        try (var store = GraphitronStore.inMemory()) {
            var dsl = store.dsl();
            ModelCapture.writeGraph(dsl, new GraphIdentity("reclaim", dir), FIRST);
            CodeCapture.capture(dsl, ClasspathSourceCapture.capture(dsl, "reclaim",
                    List.of(ClasspathEntry.project(gone), ClasspathEntry.project(stays)), null, FIRST),
                null, FIRST);
            String goneName = gone.toString();
            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME.eq(goneName)))
                .as("the entry is a source under the name this case asks by")
                .isOne();
            assertThat(dsl.fetchCount(CODE_TYPE, CODE_TYPE.SOURCE_NAME.eq(goneName)))
                .as("and holds types, the rows that could refuse its deletion")
                .isPositive();

            deleteTree(gone);
            CodeCapture.capture(dsl, ClasspathSourceCapture.capture(dsl, "reclaim",
                    List.of(ClasspathEntry.project(stays)), null, SECOND),
                null, SECOND);

            assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME.eq(goneName)))
                .as("the entry gone from disk is forgotten")
                .isZero();
            assertThat(rowsPerSourceTable(dsl, goneName))
                .as("and nothing read from it outlives it")
                .allSatisfy((table, rows) -> assertThat(rows).as(table).isZero());
            assertThat(dsl.fetchCount(CODE_CLASS, CODE_CLASS.SOURCE_NAME.eq(stays.toString())))
                .as("while the entry that stays keeps its classes, so the reading ran")
                .isPositive();
        }
    }

    /** A directory entry holding one compiled fixture class, at the path its package names. */
    private static Path fixtureEntry(Path entry, String className) throws IOException {
        String relative = className.replace('.', '/') + ".class";
        Path target = entry.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(TEST_CLASSES.resolve(relative), target);
        return entry;
    }

    /** Removes a directory and everything under it, deepest first. */
    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * How many rows each source-keyed relation of the classpath families holds for one entry. Every
     * relation the reading writes, found by prefix and by column rather than listed, so one added
     * later is in scope without being named here.
     */
    private static java.util.Map<String, Integer> rowsPerSourceTable(DSLContext dsl, String source) {
        var counts = new java.util.TreeMap<String, Integer>();
        for (var table : Public.PUBLIC.getTables()) {
            String name = table.getName().toLowerCase(java.util.Locale.ROOT);
            var column = table.field("SOURCE_NAME", String.class);
            if (column == null || !(name.startsWith("code_") || name.equals("store_class_file"))) {
                continue;
            }
            counts.put(name, dsl.fetchCount(table, column.eq(source)));
        }
        return counts;
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

    /**
     * Every position's own parse says what the dictionary says for the spelling it carries.
     *
     * <p>The oracle the siting rests on, and it is runnable only while both exist, which is why the
     * columns went in beside {@code code_type} rather than instead of it. What the dictionary holds
     * is a parse of a spelling; what a position holds is the parse of its own type. They are the
     * same computation over the same input, so a disagreement is the siting having changed an
     * answer rather than moved one.
     *
     * <p>Both directions, because they fail differently: a position the dictionary has no row for
     * is a spelling the reading did not record, and a dictionary row no position agrees with is a
     * parse that drifted.
     */
    @Test
    @DisplayName("a position's parse and the dictionary's agree, both directions")
    void theSitedParseAgreesWithTheDictionary() {
        withReactorCapture(dsl -> {
            var m = CODE_METHOD;
            var p = CODE_METHOD_PARAMETER;
            var t = CODE_TYPE;
            var e = CODE_TYPE_ELEMENT;

            assertThat(dsl.select(m.CLASS_NAME, m.METHOD_NAME, m.RESULT_TYPE)
                    .from(m)
                    .join(t).on(t.SOURCE_NAME.eq(m.SOURCE_NAME), t.TYPE_NAME.eq(m.RESULT_TYPE))
                    .where(m.RESULT_ERASED_CLASS.isDistinctFrom(t.ROOT_CLASS))
                    .fetch())
                .as("a result whose erasure differs from the dictionary's for the same spelling")
                .isEmpty();

            assertThat(dsl.select(p.CLASS_NAME, p.METHOD_NAME, p.POSITION, p.PARAMETER_TYPE)
                    .from(p)
                    .join(t).on(t.SOURCE_NAME.eq(p.SOURCE_NAME), t.TYPE_NAME.eq(p.PARAMETER_TYPE))
                    .where(p.ERASED_CLASS.isDistinctFrom(t.ROOT_CLASS))
                    .fetch())
                .as("a parameter whose erasure differs from the dictionary's")
                .isEmpty();

            assertThat(dsl.select(m.CLASS_NAME, m.METHOD_NAME, m.RESULT_TYPE)
                    .from(m)
                    .leftJoin(e).on(e.SOURCE_NAME.eq(m.SOURCE_NAME), e.TYPE_NAME.eq(m.RESULT_TYPE))
                    .where(m.RESULT_ELEMENT_CLASS.isDistinctFrom(e.ELEMENT_CLASS)
                        .or(m.RESULT_DELIVERY.isDistinctFrom(e.DELIVERY)))
                    .fetch())
                .as("a result whose peel differs from the dictionary's, in either column")
                .isEmpty();

            assertThat(dsl.select(p.CLASS_NAME, p.METHOD_NAME, p.POSITION)
                    .from(p)
                    .leftJoin(e).on(e.SOURCE_NAME.eq(p.SOURCE_NAME), e.TYPE_NAME.eq(p.PARAMETER_TYPE))
                    .where(p.ELEMENT_CLASS.isDistinctFrom(e.ELEMENT_CLASS)
                        .or(p.DELIVERY.isDistinctFrom(e.DELIVERY)))
                    .fetch())
                .as("a parameter whose peel differs from the dictionary's")
                .isEmpty();

            var s = CODE_READ_SLOT;
            assertThat(dsl.select(s.CLASS_NAME, s.METHOD_NAME, s.SLOT_TYPE)
                    .from(s)
                    .join(t).on(t.SOURCE_NAME.eq(s.SOURCE_NAME), t.TYPE_NAME.eq(s.SLOT_TYPE))
                    .where(s.ERASED_CLASS.isDistinctFrom(t.ROOT_CLASS))
                    .fetch())
                .as("a slot whose erasure differs from the dictionary's")
                .isEmpty();
            assertThat(dsl.select(s.CLASS_NAME, s.METHOD_NAME, s.SLOT_TYPE)
                    .from(s)
                    .leftJoin(e).on(e.SOURCE_NAME.eq(s.SOURCE_NAME), e.TYPE_NAME.eq(s.SLOT_TYPE))
                    .where(s.ELEMENT_CLASS.isDistinctFrom(e.ELEMENT_CLASS)
                        .or(s.DELIVERY.isDistinctFrom(e.DELIVERY)))
                    .fetch())
                .as("a slot whose peel differs from the dictionary's")
                .isEmpty();

            var w = CODE_WRITE_SLOT;
            assertThat(dsl.select(w.CLASS_NAME, w.METHOD_NAME, w.POSITION, w.SLOT_TYPE)
                    .from(w)
                    .join(t).on(t.SOURCE_NAME.eq(w.SOURCE_NAME), t.TYPE_NAME.eq(w.SLOT_TYPE))
                    .where(w.ERASED_CLASS.isDistinctFrom(t.ROOT_CLASS))
                    .fetch())
                .as("a write slot whose erasure differs from the dictionary's")
                .isEmpty();
            assertThat(dsl.select(w.CLASS_NAME, w.METHOD_NAME, w.POSITION)
                    .from(w)
                    .leftJoin(e).on(e.SOURCE_NAME.eq(w.SOURCE_NAME), e.TYPE_NAME.eq(w.SLOT_TYPE))
                    .where(w.ELEMENT_CLASS.isDistinctFrom(e.ELEMENT_CLASS)
                        .or(w.DELIVERY.isDistinctFrom(e.DELIVERY)))
                    .fetch())
                .as("a write slot whose peel differs from the dictionary's")
                .isEmpty();

            assertThat(dsl.fetchCount(m, m.RESULT_ELEMENT_CLASS.isNotNull()))
                .as("the fixture reaches resolved results, so the comparison is not over nothing")
                .isPositive();
            assertThat(dsl.fetchCount(s, s.ELEMENT_CLASS.isNotNull()))
                .as("and resolved slots")
                .isPositive();
            assertThat(dsl.fetchCount(w, w.ELEMENT_CLASS.isNotNull()))
                .as("and resolved write slots")
                .isPositive();
            assertThat(dsl.fetchCount(m, m.RESULT_DELIVERY.eq("MANY")))
                .as("and reaches a delivering container, which is the arm the peel exists for")
                .isPositive();
        });
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
        return dsl.select(e.ELEMENT_CLASS, e.DELIVERY)
            .from(m)
            .join(e).on(e.SOURCE_NAME.eq(m.SOURCE_NAME), e.TYPE_NAME.eq(m.RESULT_TYPE))
            .where(m.CLASS_NAME.eq(SERVICES))
            .and(m.METHOD_NAME.eq(methodName))
            .fetchOne(row -> row.value1() + " " + row.value2());
    }

    /** The class one method's result type names at its root, or null where it names none. */
    private static String rootOf(DSLContext dsl, String methodName) {
        return resultType(dsl, methodName).get(CODE_TYPE.ROOT_CLASS);
    }

    /** The same result type's own spelling, which is what tells the rootless cases apart. */
    private static String spellingOf(DSLContext dsl, String methodName) {
        return resultType(dsl, methodName).get(CODE_TYPE.TYPE_NAME);
    }

    /** The type row one method results in. */
    private static org.jooq.Record resultType(DSLContext dsl, String methodName) {
        var m = CODE_METHOD;
        return dsl.select(CODE_TYPE.TYPE_NAME, CODE_TYPE.ROOT_CLASS)
            .from(m)
            .join(CODE_TYPE).on(CODE_TYPE.SOURCE_NAME.eq(m.SOURCE_NAME),
                CODE_TYPE.TYPE_NAME.eq(m.RESULT_TYPE))
            .where(m.CLASS_NAME.eq(SERVICES))
            .and(m.METHOD_NAME.eq(methodName))
            .fetchSingle();
    }

    /** What one position's type resolves to, on {@link #deliveryOf}'s terms. */
    private static String parameterDeliveryOf(DSLContext dsl, String methodName, int position) {
        var e = CODE_TYPE_ELEMENT;
        var p = CODE_METHOD_PARAMETER;
        return dsl.select(e.ELEMENT_CLASS, e.DELIVERY)
            .from(p)
            .join(e).on(e.SOURCE_NAME.eq(p.SOURCE_NAME), e.TYPE_NAME.eq(p.PARAMETER_TYPE))
            .where(p.CLASS_NAME.eq(SERVICES))
            .and(p.METHOD_NAME.eq(methodName))
            .and(p.POSITION.eq(position))
            .fetchOne(row -> row.value1() + " " + row.value2());
    }

    /** The slot names one class offers. */
    private static List<String> slotsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_READ_SLOT.SLOT_NAME)
            .from(CODE_READ_SLOT)
            .where(CODE_READ_SLOT.CLASS_NAME.eq(className))
            .fetch(CODE_READ_SLOT.SLOT_NAME);
    }

    /** How a slot's type renders, which is its accessor's result type's property. */
    private static String displayTypeOf(DSLContext dsl, String className, String slotName) {
        var t = CODE_TYPE;
        return dsl.select(t.DISPLAY_NAME)
            .from(CODE_READ_SLOT)
            .join(t).on(t.SOURCE_NAME.eq(CODE_READ_SLOT.SOURCE_NAME),
                t.TYPE_NAME.eq(CODE_READ_SLOT.SLOT_TYPE))
            .where(CODE_READ_SLOT.CLASS_NAME.eq(className))
            .and(CODE_READ_SLOT.SLOT_NAME.eq(slotName))
            .fetchOne(t.DISPLAY_NAME);
    }

    /** The arms those slots came from, which is a fact about the class. */
    private static List<String> originsOn(DSLContext dsl, String className) {
        return dsl.select(CODE_READ_SLOT.ORIGIN)
            .from(CODE_READ_SLOT)
            .where(CODE_READ_SLOT.CLASS_NAME.eq(className))
            .fetch(CODE_READ_SLOT.ORIGIN);
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
