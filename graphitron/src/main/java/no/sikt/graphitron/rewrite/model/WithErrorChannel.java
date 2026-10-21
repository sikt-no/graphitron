package no.sikt.graphitron.rewrite.model;

import java.util.Optional;

/**
 * A fetcher-emitting field variant that may carry a typed-error channel: when
 * {@link #errorChannel()} returns a value, the emitter wraps the fetcher body in a
 * try/catch that routes thrown exceptions into the payload's typed {@code errors} field.
 * When empty, the emitter wraps the body in
 * a redacting catch arm instead (the no-channel privacy disposition).
 *
 * <p>Capability rather than a slot on every {@link GraphitronField} root: only fetcher-emitting
 * variants (root mutations, root + child services)
 * carry the slot, since only those have a fetcher body whose catch arm can dispatch through the
 * channel. Each such variant implements this interface; the rest stay free of the slot.
 * Generators consume the field via {@code instanceof WithErrorChannel} when they need to know
 * whether to dispatch via the generated {@code ErrorRouter}.
 *
 * <p>Every channel is an {@link ErrorChannel.Mapped}, riding the {@code Outcome} wrapper;
 * channel-agnostic readers (dedup, mappings emission) consume this accessor, and emit seams read
 * the variant's own component.
 */
public interface WithErrorChannel {
    /** The typed-error channel resolved for this fetcher, or empty when the payload has no {@code errors} field. */
    Optional<? extends ErrorChannel> errorChannel();
}
