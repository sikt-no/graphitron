package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.schema.ScalarMapping;

/**
 * POM XML binding for a single {@code <scalar>} entry.
 * Converts to a {@link ScalarMapping} carried on {@link no.sikt.graphitron.rewrite.RewriteContext}.
 */
public class ScalarBinding {
    /** The GraphQL scalar name (e.g. {@code DateTime}). */
    String scalarName;
    /** The fully qualified Java class that implements this scalar. */
    String className;

    ScalarMapping toScalarMapping() {
        return new ScalarMapping(scalarName, className);
    }
}
