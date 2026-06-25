package no.sikt.graphitron.mcp.rag;

/**
 * The warm lifecycle of a RAG handle (R372 D3): a value resolves the Backlog's prose
 * "loading / ready / failed" into a typed shape, mirroring R361's exhaustive {@code LspSchemaSnapshot}
 * switch posture. "Bind sync, warm async, never block the dev loop" is the R118 cross-cutting
 * principle this slice owns the state machine for; the consuming tools report through it.
 *
 * <p><strong>Generic over the warmed handle</strong> because one harness drives two distinct warms:
 * the shared embedder warm is a {@code WarmState<Embedder>}, each per-corpus index warm is a
 * {@code WarmState<EmbeddingStore>}. The type parameter is what lets one sealed shape cover both; a
 * non-generic {@code Ready(EmbeddingStore)} would have no slot for the warmed {@code Embedder} and
 * would force the embedder warm into a second, drifting type.
 *
 * <p><strong>Each state carries exactly its own data.</strong> {@link Ready} carries the warmed
 * handle, {@link Failed} carries the cause for diagnostics, {@link Warming} carries nothing. A bare
 * enum / {@code isReady()} would force every consumer to re-fetch the handle and re-derive the
 * degradation message, and they would drift.
 *
 * @param <T> the warmed handle a {@link Ready} carries
 */
public sealed interface WarmState<T> permits WarmState.Warming, WarmState.Ready, WarmState.Failed {

    /** Warm in progress: no handle yet. The dev loop keeps running; consumers degrade. */
    record Warming<T>() implements WarmState<T> {}

    /** Warm succeeded: {@code handle} is the warmed embedder or store, ready to serve. */
    record Ready<T>(T handle) implements WarmState<T> {}

    /** Warm failed: {@code cause} is kept for diagnostics. The dev loop keeps running, RAG degraded. */
    record Failed<T>(Throwable cause) implements WarmState<T> {}

    /**
     * The single shared degradation message (R372 D3): the standard "index warming, use the
     * structured tools meanwhile" wording, produced once here so the semantic tools of slices 9/10
     * do not each re-author it. Handle-agnostic: it reads any {@code WarmState<?>} and branches only
     * on the two non-{@code Ready} states. Exhaustive over the sealed permits with no {@code default},
     * so a new arm forces a compile-time choice here; {@link Ready} is rejected because a ready warm
     * has its handle and nothing to degrade to.
     */
    static String degradationMessage(WarmState<?> state) {
        return switch (state) {
            case Warming<?> ignored ->
                "The semantic index is still warming; the structured tools (catalog, schema, "
                    + "services, conditions, records, diagnostics) are available meanwhile.";
            case Failed<?> failed ->
                "The semantic index failed to load (" + describe(failed.cause()) + "); the structured "
                    + "tools (catalog, schema, services, conditions, records, diagnostics) remain available.";
            case Ready<?> ignored ->
                throw new IllegalArgumentException(
                    "degradationMessage called on a Ready state; the handle is available, no degradation applies");
        };
    }

    /** A compact, non-null description of a warm failure cause for the degradation message. */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "unknown cause";
        }
        String message = cause.getMessage();
        return (message != null && !message.isBlank())
            ? cause.getClass().getSimpleName() + ": " + message
            : cause.getClass().getSimpleName();
    }
}
