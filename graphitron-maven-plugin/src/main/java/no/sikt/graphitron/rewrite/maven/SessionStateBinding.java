package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for the {@code <sessionState>} block. Collapses into a
 * {@link no.sikt.graphitron.rewrite.session.SessionStateConfig} on
 * {@link no.sikt.graphitron.rewrite.RewriteContext}; the schema build reflects the named
 * methods into the resolved carrier the connection-runtime emitters read.
 *
 * <p>{@code <mount>} names a public static method as {@code fqcn#method}; graphitron calls it
 * on each connection it acquires, before any SQL on it, and the optional {@code <unmount>}
 * runs when the connection goes back to the pool. jOOQ's generated {@code Routines} executing
 * methods satisfy the signature contract as-is, so the common case names them directly:
 * <pre>{@code
 * <sessionState>
 *   <mount>com.example.db.Routines#connect</mount>
 *   <unmount>com.example.db.Routines#disconnect</unmount>
 * </sessionState>
 * }</pre>
 * The method's signature is the contract, read at build time: exactly one seam parameter
 * ({@code org.jooq.Configuration} or {@code java.sql.Connection}) anywhere in the list, the
 * remaining mount parameters are the payload (each becomes a contextArgument on the generated
 * factory), and the mount's return type is the handle later passed to {@code unmount} (or
 * bound in a service's {@code argMapping} via the {@code $session} sigil). Omitting
 * {@code <unmount>} is a supported mount-only configuration: the next request's mount
 * overwrites wholesale.
 *
 * <p>The shape rejections ({@code <unmount>} without {@code <mount>}, a malformed
 * {@code fqcn#method}) are enforced by {@code SessionStateConfig.from(...)} at config build; a
 * defective block fails the build. Failures of the referenced methods themselves (class not
 * loadable, no seam parameter, handle-type mismatch) are typed rejections raised by the schema
 * build, where the reflection runs.
 */
public class SessionStateBinding {
    /** The {@code <mount>} method reference ({@code fqcn#method}). */
    String mount;
    /** The optional {@code <unmount>} method reference ({@code fqcn#method}). */
    String unmount;
}
