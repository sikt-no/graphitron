package no.sikt.graphitron.command;

/**
 * The launcher's delivery axis: how the entry point hands work to the composition, as data on
 * the command rather than emitter control flow. Tenancy is deliberately not here: it is its own
 * axis ({@link TenantStrategy}), independent by measurement (the fanned batched child is a
 * populated corpus cell), so a delivery arm never encodes an acquisition decision.
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
}
