package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every static field of this module's test classes that can hold a {@link DSLContext} carries
 * {@link AutoClose}, so the class that opens a fixture connection closes it when the class
 * finishes. The one exception is a field that holds a context which is not {@link AutoCloseable}
 * as soon as its class is initialized: a {@code static final} field set to
 * {@code DSL.using(SQLDialect.POSTGRES)}, which renders SQL and opens no connection.
 * {@code docs/architecture/how-to/testing.adoc}, "Who closes a fixture connection", states the
 * rule and why it is enforced.
 *
 * <p>Reflective and keyed on the declared type, so a field is seen however its class assigns it.
 * A fixture field is {@code null} until {@code @BeforeAll} runs, so it has to carry the annotation;
 * a dialect-only field assigned in {@code @BeforeAll} is {@code null} at that point too and is
 * caught, which is what keeps such fields in the initializer shape. Not seen: a connection held in
 * a field of another type (a {@code Connection}, a {@code DataSource}, a pool), or one reached
 * through a helper class, which are closed by hand; and a context a test opens inline, which
 * try-with-resources closes. That inline half of the rule is checked by review only.
 */
@UnitTier
class FixtureCloseEnforcementTest {

    /** Well under the 93 annotated fields the module had when the rule arrived; fewer means the walk stopped reaching it. */
    private static final int MIN_ANNOTATED_FIELDS = 50;

    @Test
    void everyStaticContextFieldClosesWithItsClass() {
        var classes = TestClasses.loaded().stream()
            // Nested classes are kept, since one can declare a static field too; this class's own
            // planted shapes are not, the way SakilaDatabaseGuardTest leaves out its own file.
            .filter(clazz -> clazz.getNestHost() != FixtureCloseEnforcementTest.class)
            .toList();

        var check = check(classes);

        assertThat(check.annotated())
            .as("the walk found only %d static context fields carrying @AutoClose, so it no longer "
                + "reaches this module's test classes and the rule below would pass vacuously",
                check.annotated().size())
            .hasSizeGreaterThan(MIN_ANNOTATED_FIELDS);
        assertThat(check.violations())
            .as("static DSLContext fields without @AutoClose. A context built over a URL holds one "
                + "open connection until it is closed, and every class runs in one test JVM, so a "
                + "field nobody closes holds its connection until the JVM exits and the suite runs "
                + "the server out of connections. Put @AutoClose on the field; it closes the value "
                + "after the class's own @AfterAll methods. A context that opens no connection "
                + "(DSL.using(SQLDialect.POSTGRES)) belongs in a static final initializer instead. "
                + "docs/architecture/how-to/testing.adoc, \"Who closes a fixture connection\".\n"
                + String.join("\n", check.violations()))
            .isEmpty();
    }

    @Test
    void thePlantedShapesAreSortedAsTheRealOnesAre() {
        var check = check(List.of(
            UnannotatedFixture.class, AnnotatedFixture.class,
            DialectOnlyInitializer.class, DialectOnlyInBeforeAll.class));

        assertThat(check.violations()).containsExactlyInAnyOrder(
            UnannotatedFixture.class.getName() + "#dsl",
            DialectOnlyInBeforeAll.class.getName() + "#DSL_CTX");
        assertThat(check.annotated()).containsExactly(AnnotatedFixture.class.getName() + "#dsl");
    }

    private record Check(List<String> annotated, List<String> violations) {}

    private static Check check(List<Class<?>> classes) {
        var annotated = new ArrayList<String>();
        var violations = new ArrayList<String>();
        for (var clazz : classes) {
            for (var field : clazz.getDeclaredFields()) {
                if (field.isSynthetic()
                    || !Modifier.isStatic(field.getModifiers())
                    || !DSLContext.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                var name = clazz.getName() + "#" + field.getName();
                if (field.isAnnotationPresent(AutoClose.class)) {
                    annotated.add(name);
                } else if (!holdsAContextWithNoConnection(field)) {
                    violations.add(name);
                }
            }
        }
        return new Check(annotated, violations);
    }

    /** Reads the field, which initializes its class; only an unannotated field's class gets that far. */
    private static boolean holdsAContextWithNoConnection(Field field) {
        try {
            field.setAccessible(true);
            var value = field.get(null);
            return value != null && !(value instanceof AutoCloseable);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }

    // The planted shapes. None declares a test, so JUnit never runs their @BeforeAll methods.

    /** A fixture field nothing closes: caught. */
    private static final class UnannotatedFixture {
        static DSLContext dsl;

        @BeforeAll
        static void setUp() {
            dsl = SakilaDatabase.dsl();
        }
    }

    /** A fixture field closed with its class: passes. */
    private static final class AnnotatedFixture {
        @AutoClose
        static DSLContext dsl;

        @BeforeAll
        static void setUp() {
            dsl = SakilaDatabase.dsl();
        }
    }

    /** A dialect-only context in an initializer, holding no connection: passes. */
    private static final class DialectOnlyInitializer {
        static final DSLContext DSL_CTX = DSL.using(SQLDialect.POSTGRES);
    }

    /** A dialect-only context assigned in {@code @BeforeAll}: indistinguishable from a fixture field, so caught. */
    private static final class DialectOnlyInBeforeAll {
        static DSLContext DSL_CTX;

        @BeforeAll
        static void setUp() {
            DSL_CTX = DSL.using(SQLDialect.POSTGRES);
        }
    }
}
