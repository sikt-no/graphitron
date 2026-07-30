package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.Objects;

/**
 * Sealed permit hierarchy describing the per-shape body of a DataLoader rows-method. Consumed
 * by {@link no.sikt.graphitron.rewrite.generators.RowsMethodSkeleton}'s exhaustive switch:
 * the skeleton owns the declaration scaffolding (modifiers, parameters, return type,
 * {@code DSLContext dsl} resolution); each permit carries the body content that follows.
 *
 * <p>Only the {@link Service} permit remains: the SQL-shaped bodies (the batched table, lookup
 * and pivot children) render through the launcher-command path now. The sealed structure stays
 * so the skeleton's switch is exhaustive over whatever the service arms' own migration leaves.
 *
 * <p>The body content is an opaque {@link CodeBlock} so the skeleton is decoupled from the
 * service-call construction logic.
 */
public sealed interface RowsMethodBody {

    /**
     * The body content emitted into the rows method, excluding the {@code DSLContext dsl = ...}
     * line (the skeleton owns that framing). Ends in a
     * {@code return ServiceClass.method(...);} statement.
     */
    CodeBlock content();

    /**
     * Service-delegating body for {@link ChildField.ServiceTableField} /
     * {@link ChildField.ServiceRecordField}. The
     * {@code needsDsl} flag mirrors {@link MethodRef.CallShape.Static#needsDslLocal()} (with
     * {@link MethodRef.CallShape.InstanceWithDslHolder} folding to {@code true}); the skeleton
     * emits a {@code DSLContext dsl = ...} line when {@code true} and skips otherwise.
     */
    record Service(CodeBlock content, boolean needsDsl) implements RowsMethodBody {
        public Service {
            Objects.requireNonNull(content, "content");
        }
    }
}
