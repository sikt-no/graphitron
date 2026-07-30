package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.Objects;

/**
 * Sealed permit hierarchy describing the per-shape body of a DataLoader rows-method. Consumed
 * by {@link no.sikt.graphitron.rewrite.generators.RowsMethodSkeleton}'s exhaustive switch:
 * the skeleton owns the declaration scaffolding (modifiers, parameters, return type, empty-input
 * gate, {@code DSLContext dsl} resolution); each permit carries the body content that follows.
 *
 * <p>The SQL permits are intentionally distinct types even though their framings are identical:
 * distinct permits make the dispatch axis first-class so the construction site's projection
 * from the field's variant and {@link LoaderRegistration#container()} lands in a single typed
 * slot rather than a runtime branch.
 *
 * <p>The body content is an opaque {@link CodeBlock} so the skeleton is decoupled from the
 * SELECT / scatter / service-call construction logic.
 */
public sealed interface RowsMethodBody {

    /**
     * The body content emitted into the rows method, excluding the empty-input gate and the
     * {@code DSLContext dsl = ...} line (the skeleton owns those framings). For SQL permits
     * the content references the {@code keys} parameter and the {@code dsl} local; for the
     * {@link Service} permit it ends in a {@code return ServiceClass.method(...);} statement.
     */
    CodeBlock content();

    /**
     * SQL body for {@link ChildField.BatchedPivotField}: the key-preserving left join from the parent-input
     * {@code VALUES} table to the attribute table, the selection-gated filtered aggregates, and
     * {@code GROUP BY __idx__}, scattered single-per-key.
     */
    record SqlBatchedPivot(CodeBlock content) implements RowsMethodBody {
        public SqlBatchedPivot {
            Objects.requireNonNull(content, "content");
        }
    }


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
