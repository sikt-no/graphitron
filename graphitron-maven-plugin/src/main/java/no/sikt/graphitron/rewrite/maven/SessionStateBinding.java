package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for the {@code <sessionState>} block. Collapses into a
 * {@link no.sikt.graphitron.model.config.SessionStateConfig} on
 * {@link no.sikt.graphitron.model.config.RunContext}.
 *
 * <p>The block declares that the build supplies session identity per request; the consumer sets it
 * on each connection its {@code ConnectionSource} leases (the generated
 * {@code ConnectionSources.mounting} does so from a mount and an unmount), passed to the generated
 * {@code Graphitron.newOwnedExecutionInput}. {@code <handle>} optionally names the class of the
 * lease's handle, which {@code $session} bindings in {@code argMapping} read members of:
 * <pre>{@code
 * <sessionState>
 *   <handle>com.example.Identity</handle>
 * </sessionState>
 * }</pre>
 * Omit {@code <handle>} for leases without a handle. The retired {@code <mount>} and {@code <unmount>}
 * elements stay bound only so that a POM still naming one fails with the migration message
 * ({@code SessionStateConfig.from}) instead of Maven's generic unknown-parameter error.
 */
public class SessionStateBinding {
    /** The optional {@code <handle>} class, fully qualified. */
    String handle;
    /** Retired; present only to reject a leftover {@code <mount>} with the migration message. */
    String mount;
    /** Retired; present only to reject a leftover {@code <unmount>} with the migration message. */
    String unmount;
}
