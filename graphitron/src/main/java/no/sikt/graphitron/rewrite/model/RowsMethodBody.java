package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.CodeBlock;

import java.util.Objects;

/**
 * Sealed permit hierarchy describing the per-shape body of a DataLoader rows-method. Consumed
 * by {@link no.sikt.graphitron.rewrite.generators.RowsMethodSkeleton}'s exhaustive switch:
 * the skeleton owns the declaration scaffolding (modifiers, parameters, return type, empty-input
 * gate, {@code DSLContext dsl} resolution); each permit carries the body content that follows.
 *
 * <p>One permit per body shape, mirroring the classifier's {@code (Reader, Container)}
 * projection:
 *
 * <ul>
 *   <li>{@link SqlBatchedTable}, {@link SqlBatchedLookupTable} — SQL-side bodies. The skeleton emits the empty-input
 *       short-circuit and the {@code DSLContext dsl = ...} line, then pastes the permit's
 *       {@code content()}; the SELECT / scatter logic that lives in {@code content} consumes
 *       both {@code keys} and {@code dsl}.</li>
 *   <li>{@link Service} — service-delegating body. The skeleton emits a {@code DSLContext dsl}
 *       line iff {@code needsDsl()} (driven by the {@code @service} method's
 *       {@link MethodRef.CallShape}); the empty-input gate is omitted (today's service-path
 *       behaviour preserved per the spec's "Out of scope" carve-out).</li>
 * </ul>
 *
 * <p>Permits are intentionally distinct types even though the four SQL framings are identical
 * today: distinct permits make the dispatch axis first-class so the construction site's
 * projection from the field's {@code (variant, LoaderRegistration.container())}
 * pair lands in a single typed slot rather than a runtime branch.
 *
 * <p>The body content is an opaque {@link CodeBlock} so the skeleton is decoupled from the
 * SELECT / scatter / service-call construction logic. R38 Phase 2 wires the existing
 * body builders ({@code SplitRowsMethodEmitter}, {@code TypeFetcherGenerator}'s
 * {@code buildServiceRowsMethod}) into the construction sites that produce these permits.
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
     * SQL body for {@code BatchedTableField} — flat correlated-batch SELECT plus scatter, keyed
     * off the parent-lifted key tuple (both source shapes; the SQL framing was identical for the
     * pre-merge {@code SqlSplitTable} / {@code SqlRecordTable} permits, R432).
     */
    record SqlBatchedTable(CodeBlock content) implements RowsMethodBody {
        public SqlBatchedTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * SQL body for {@code BatchedLookupTableField} — {@link SqlBatchedTable} plus a
     * {@code @lookupKey} VALUES join (both source shapes, R432).
     */
    record SqlBatchedLookupTable(CodeBlock content) implements RowsMethodBody {
        public SqlBatchedLookupTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * SQL body for {@code RecordTableMethodField} — class-backed parent + child
     * {@code @tableMethod}. Identical SQL framing to {@link SqlBatchedTable} (parent VALUES
     * join, scatter by idx) with the developer's static {@code @tableMethod} call substituted
     * for the {@code Tables.X.as("alias")} terminal table declaration.
     */
    record SqlRecordTableMethod(CodeBlock content) implements RowsMethodBody {
        public SqlRecordTableMethod {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * Service-delegating body for {@code ServiceTableField} / {@code ServiceRecordField}. The
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
