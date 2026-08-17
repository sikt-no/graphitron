package no.sikt.graphitron.lsp.state;

/**
 * Client-side toggles for the inlay-hint and classification-hover surfaces. Four
 * independent boolean axes, all defaulting to {@code false} so opt-in is explicit and
 * users who haven't asked for the surface see no behaviour change.
 *
 * <p>The keys live under the {@code graphitron.inlayHints} (and {@code graphitron.hover})
 * namespace on the client side; the document service pulls them via
 * {@code workspace/configuration} on initialisation and refreshes them on
 * {@code workspace/didChangeConfiguration}. A client that does not implement
 * {@code workspace/configuration} (common in early-stage LSP clients) leaves the defaults
 * in effect; no error is surfaced.
 *
 * @param inferredDirectives enables inlay hints at {@code @table} / {@code @field} /
 *     {@code @reference} directive sites where the author omitted the canonical argument.
 *     The hint shows the resolved value as a resolution overlay.
 * @param classification enables classification inlay hints on field declarations and
 *     object / interface / input / union type declarations. The label is the classifier
 *     claiming the declaration ({@code SERVICE}, {@code TABLE_COLUMN}, {@code TABLE}, ...)
 *     and nothing else.
 * @param separateFetch enables the round-trip marker on field declarations whose rows are
 *     fetched by a statement of their own rather than out of the enclosing SELECT. Its own
 *     axis rather than part of {@code classification} because it is a delivery fact rather
 *     than a classifier, and an author reading a schema for its query cost wants it without
 *     a label on every declaration beside it.
 * @param hoverClassification enables the rich classification hover content on field /
 *     type declaration coordinates. Where the inlay hints show one word each, the hover
 *     adds the facts behind them.
 */
public record InlayHintConfig(
    boolean inferredDirectives,
    boolean classification,
    boolean separateFetch,
    boolean hoverClassification
) {

    /** All toggles off. The default until the client opts in. */
    public static InlayHintConfig defaults() {
        return new InlayHintConfig(false, false, false, false);
    }

    /** True when any axis is enabled, i.e. the LSP has any reason to compute hints. */
    public boolean anyEnabled() {
        return inferredDirectives || classification || separateFetch || hoverClassification;
    }
}
