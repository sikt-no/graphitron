package no.sikt.graphitron.rewrite.test;

import no.sikt.graphitron.rewrite.ClassificationTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.Annotation;

/**
 * Auto-registered JUnit 5 extension that tags every classifier-trace record produced inside
 * a test class's lifecycle with the running test class name and its tier. The extension
 * inspects the test class's annotations, finds the meta-{@code @Tag} (one of {@code unit},
 * {@code pipeline}, {@code compilation}, {@code execution}, or the {@code cross-cutting}
 * exemption), and writes the pair into {@link ClassificationTrace#setContext}.
 *
 * <p>Auto-registration is wired via
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} plus
 * {@code junit.jupiter.extensions.autodetection.enabled=true} in
 * {@code junit-platform.properties}, so test classes do not need {@code @ExtendWith}.
 *
 * <p>{@code @BeforeAll} schema-building (e.g. {@code GraphQLQueryTest} spinning up
 * PostgreSQL and loading sakila) runs inside the active context, so its classification
 * records inherit the tag. Per-{@code @Test} granularity is unnecessary; class-level is
 * enough — tier is a class-level property.
 */
public final class ClassificationTraceContextExtension implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        String tier = resolveTier(testClass);
        ClassificationTrace.setContext(new ClassificationTrace.Context(testClass.getName(), tier));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ClassificationTrace.clearContext();
    }

    private static String resolveTier(Class<?> testClass) {
        for (Annotation a : testClass.getAnnotations()) {
            // Direct @Tag (e.g. @Tag("cross-cutting") on a test class without a tier annotation)
            if (a.annotationType() == Tag.class) {
                return ((Tag) a).value();
            }
            // Meta-@Tag: the four tier annotations (@UnitTier / @PipelineTier / @CompilationTier
            // / @ExecutionTier) each declare @Tag("<tier>") at the meta level, so a single pass
            // over the meta-annotations finds the tier without hardcoding the four annotation
            // class names here.
            for (Annotation meta : a.annotationType().getAnnotations()) {
                if (meta.annotationType() == Tag.class) {
                    return ((Tag) meta).value();
                }
            }
        }
        return "";
    }
}
