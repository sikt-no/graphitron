package no.sikt.graphitron.rewrite;

import java.util.List;

/**
 * R150 fixture: a self-referential bean shape. Used by the classifier test that pins the
 * recursive-shape rejection — a record referencing itself would loop forever in the bean walker
 * without the cycle-detection guard, surfacing as a {@code StackOverflowError} at generation time.
 */
public record TestInputRecursive(String name, List<TestInputRecursive> children) {}
