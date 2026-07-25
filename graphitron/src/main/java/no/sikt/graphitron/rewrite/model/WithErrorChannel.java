package no.sikt.graphitron.rewrite.model;

import java.util.Optional;

/**
 * A fetcher-emitting field variant that may carry a typed-error channel: when
 * {@link #errorChannel()} returns a value, the emitter wraps the fetcher body in a
 * try/catch that routes thrown exceptions into the payload's typed {@code errors} field
 * (see {@code error-handling-parity.md}). When empty, the emitter wraps the body in
 * a redacting catch arm instead (the no-channel privacy disposition).
 *
 * <p>Capability rather than a slot on every {@link GraphitronField} root: only fetcher-emitting
 * variants (root mutations, root + child services, root + child {@code @tableMethod} fields)
 * carry the slot, since only those have a fetcher body whose catch arm can dispatch through the
 * channel. Each such variant implements this interface; the rest stay free of the slot.
 * Generators consume the field via {@code instanceof WithErrorChannel} when they need to know
 * whether to dispatch via the generated {@code ErrorRouter}.
 *
 * <p>The wildcard return lets each variant declare the narrowest channel partition its
 * construction path can mint ({@link ErrorChannel.Mapped} for the root {@code @service}
 * variants, {@link ErrorChannel.RouterDispatched} for everything else), so the emit seams take
 * the narrowed type and the compiler enforces the seam partition. Channel-agnostic readers
 * (dedup, mappings emission) consume this accessor; emit seams read the variant's own component.
 */
public interface WithErrorChannel {
    /** The typed-error channel resolved for this fetcher, or empty when the payload has no {@code errors} field. */
    Optional<? extends ErrorChannel> errorChannel();
}
