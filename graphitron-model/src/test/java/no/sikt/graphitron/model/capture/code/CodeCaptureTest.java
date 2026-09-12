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

import static no.sikt.graphitron.model.Tables.CODE_SCALAR_CONSTANT;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE;
import static no.sikt.graphitron.model.Tables.CODE_THROWABLE_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two arms of the code family a classpath answers: what an author may name in
 * {@code @scalarType(scalar:)}, and the throwables an {@code @error} handler may name.
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
            CodeCapture.capture(dsl, CLASSPATH, null, null, FIRST);
            int afterFirst = dsl.fetchCount(CODE_SCALAR_CONSTANT);
            int throwablesAfterFirst = dsl.fetchCount(CODE_THROWABLE);
            int ancestorsAfterFirst = dsl.fetchCount(CODE_THROWABLE_SUPERTYPE);
            CodeCapture.capture(dsl, CLASSPATH, null, null, SECOND);

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

    private static void withCapture(LocalDateTime at, Consumer<DSLContext> body) {
        try (var store = GraphitronStore.inMemory()) {
            CodeCapture.capture(store.dsl(), CLASSPATH, null, null, at);
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
}
