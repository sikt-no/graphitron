package no.sikt.graphitron.rewrite;

import java.util.List;

/**
 * Fixture: minimal record-shaped consumer bean used by classifier and emitter tests for
 * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean}. Mirrors a small SDL
 * {@code input} type with a scalar, an enum (via {@link TestInputBeanEnum}), and a list of nested
 * beans.
 */
public record TestInputBean(
    String title,
    TestInputBeanEnum rating,
    List<TestInputNested> nested
) {
}
