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
 *   <li>{@link SqlSplitTable}, {@link SqlSplitLookupTable}, {@link SqlRecordTable},
 *       {@link SqlRecordLookupTable} — SQL-side bodies. The skeleton emits the empty-input
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
 * projection from the field's {@code (SourceKey.reader(), LoaderRegistration.container())}
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

    /** SQL body for {@code SplitTableField} — flat correlated-batch SELECT plus scatter. */
    record SqlSplitTable(CodeBlock content) implements RowsMethodBody {
        public SqlSplitTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /** SQL body for {@code SplitLookupTableField} — adds a {@code @lookupKey} VALUES join. */
    record SqlSplitLookupTable(CodeBlock content) implements RowsMethodBody {
        public SqlSplitLookupTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /** SQL body for {@code RecordTableField} — flat join keyed off the parent {@code @record}. */
    record SqlRecordTable(CodeBlock content) implements RowsMethodBody {
        public SqlRecordTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /** SQL body for {@code RecordLookupTableField} — {@code @record} parent + lookupKey. */
    record SqlRecordLookupTable(CodeBlock content) implements RowsMethodBody {
        public SqlRecordLookupTable {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * SQL body for {@code RecordTableMethodField} — {@code @record} parent + child
     * {@code @tableMethod}. Identical SQL framing to {@link SqlRecordTable} (parent VALUES
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
