package no.sikt.graphitron.rewrite.catalog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * R160 — marks a pipeline-tier test method that asserts the projection's payload for one or
 * more generator-side sealed leaves of
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField} or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType}. Discovered by
 * {@link ProjectionCoverageTest} via reflection over {@code GraphitronSchemaBuilderTest}'s
 * declared methods.
 *
 * <p>The annotation is the drift-prevention contract that pairs with the projector's
 * compile-time exhaustive switch: the switch ensures every leaf has a projection arm,
 * the meta-test ensures every leaf has a payload-asserting test (or a documented
 * exception in the test's allowlist). A future contributor adding a permit without an
 * accompanying projection test fails the meta-test.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectionFor {
    /** The sealed leaves whose projection payload this test asserts. */
    Class<?>[] value();
}
