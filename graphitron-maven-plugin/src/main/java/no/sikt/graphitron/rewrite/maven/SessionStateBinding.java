package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for the {@code <sessionState>} block. Collapses into a
 * {@link no.sikt.graphitron.model.config.SessionStateConfig} on
 * {@link no.sikt.graphitron.model.config.RunContext}.
 *
 * <p>The block only switches session identity on for the owned-connection engine; the consumer
 * supplies the identity per request as code, a {@code SessionState} built from a mount callback
 * and an optional unmount callback and passed to the generated
 * {@code Graphitron.newOwnedExecutionInput}. {@code <handle>} optionally names the class the mount
 * callback returns, which {@code $session} bindings in {@code argMapping} read members of:
 * <pre>{@code
 * <sessionState>
 *   <handle>com.example.Identity</handle>
 * </sessionState>
 * }</pre>
 * Omit {@code <handle>} for a handle-less mount. The retired {@code <mount>} and {@code <unmount>}
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
