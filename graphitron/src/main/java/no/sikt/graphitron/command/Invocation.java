package no.sikt.graphitron.command;

/**
 * The launcher's delivery axis: how the entry point hands work to the composition, as data on
 * the command rather than emitter control flow. Tenancy is deliberately not here: it is its own
 * axis ({@link TenantStrategy}), independent by measurement (the fanned batched child is a
 * populated cell in the pipeline fixtures: {@code TenantFanOutFetcherPipelineTest}'s
 * batched-form fanned child, with {@code LauncherCommandsPipelineTest} pinning the fanned
 * root's row; the corpus's canonical run configures no tenants, so the cell does not live
 * there), so a delivery arm never encodes an acquisition decision.
 *
 * <p>The delivery and tenancy pair decide the launcher unit's <em>interface</em>, not only its
 * body: the parameter list is a derived view over the two (a direct single-tenant launcher
 * takes the one resolved {@code DSLContext}; a fanned launcher takes none, its acquisition
 * being plural and internal to the scatter carrier; the batched child arm, when it folds in,
 * takes its keys). The composition fragment itself is shared: one select chain, with the axes
 * deciding whether {@code dsl} is bound by a parameter, the strategy's per-tenant lambda, or
 * the batch method's own declaration.
 */
public sealed interface Invocation {

    /** The entry point calls the launcher once; the composition runs against one delivery. */
    record Direct() implements Invocation {}

    /**
     * The DataLoader-batched delivery: the entry point registers a loader keyed by the
     * coordinate's {@link no.sikt.graphitron.rewrite.model.SourceKey} and the launcher takes
     * the batch's keys, joining them in as the parent-input VALUES derived table and scattering
     * results back per key by the {@code __idx__} column (arm-entailed, constant for this
     * delivery, so no extras slot carries it). {@code sourceKey} decides the keys parameter's
     * element type and the VALUES cell extraction; {@code loader} decides the keys container
     * ({@code List} positional vs {@code Set} mapped) and the entry point's dispatch, borrowed
     * whole so the row and the loader wiring read one fact.
     */
    record Batched(no.sikt.graphitron.rewrite.model.SourceKey sourceKey,
                   no.sikt.graphitron.rewrite.model.LoaderRegistration loader) implements Invocation {
        public Batched {
            java.util.Objects.requireNonNull(sourceKey, "sourceKey");
            java.util.Objects.requireNonNull(loader, "loader");
        }
    }

    /**
     * The DML reentry delivery: the mutation entry point runs the write itself (the transaction,
     * the dialect guard, the {@code RETURNING} key capture), then calls the launcher once with
     * the captured keys to re-select the payload. Payload-free by design: the keys parameter's
     * element type derives from the {@link LaunchSource.Reentry} correlation, the list lift from
     * the result shape, and the {@code dsl} binding from the shell's dsl-declaration fragment,
     * so every fact this arm would carry already rides another axis.
     */
    record ReturningKeyed() implements Invocation {}
}
