package no.sikt.graphitron.model.config;

import java.util.Optional;

/**
 * The validated authored {@code <sessionState>} configuration built once from the Maven block
 * and threaded through {@link no.sikt.graphitron.model.config.RunContext} into the schema build.
 *
 * <p>This carrier is <em>strings only</em>, deliberately: fact capture transcribes the config
 * object verbatim into the authored-facts provenance family ({@code StoreEntries}), so nothing
 * resolved or reflected may live here. What the configured handle class offers is derived from
 * the classpath corpus in the store, not read off this value. The authored config has exactly
 * two readers, {@code SessionStateWarnings#forConfig} and fact capture's transcription.
 *
 * <p>Config-shape defects (a blank {@code <handle>}, a leftover {@code <mount>} or
 * {@code <unmount>}) are validated in {@link #from}, which throws {@link IllegalArgumentException}
 * naming the offending element; the Maven seam ({@code AbstractRewriteMojo.buildSessionStateConfig})
 * turns that into a build failure, mirroring the {@code LintConfig.validated} precedent. A
 * {@code pom.xml} defect has no SDL coordinate, so it is validated here rather than routed
 * through the schema validator.
 */
public sealed interface SessionStateConfig permits SessionStateConfig.None, SessionStateConfig.Callbacks {

    /** No {@code <sessionState>} configured: the generated runtime mounts no identity. */
    record None() implements SessionStateConfig {
        public static final None INSTANCE = new None();
    }

    /**
     * Session identity is supplied per request: the consumer passes a {@code SessionState} to the
     * generated factory and graphitron calls its callbacks on each connection it acquires.
     * {@code handleType} is the authored fully qualified name of the class the mount callback
     * returns, when one is named; absent means the mount is handle-less.
     */
    record Callbacks(Optional<String> handleType) implements SessionStateConfig {
        public Callbacks {
            if (handleType == null) {
                throw new IllegalArgumentException("Callbacks requires a non-null handleType Optional");
            }
        }
    }

    /** The no-configuration form. */
    static SessionStateConfig none() {
        return None.INSTANCE;
    }

    /**
     * Reconciles the raw {@code <sessionState>} strings of a present block into a validated
     * config, or throws {@link IllegalArgumentException} naming the offending element. A
     * {@code null} string means the element was absent; a present-but-blank {@code <handle>} is a
     * defect, never silently absent. {@code <mount>} and {@code <unmount>} are retired: naming
     * either fails the build with the migration to the callback form.
     *
     * @param handle  the {@code <handle>} element's text, or {@code null} if absent
     * @param mount   the retired {@code <mount>} element's text, or {@code null} if absent
     * @param unmount the retired {@code <unmount>} element's text, or {@code null} if absent
     */
    static SessionStateConfig from(String handle, String mount, String unmount) {
        if (mount != null || unmount != null) {
            throw new IllegalArgumentException(
                "<" + (mount != null ? "mount" : "unmount") + "> is no longer a <sessionState> element: "
                    + "session identity is supplied per request. Leave <sessionState/> in the POM "
                    + "(optionally naming the handle class in <handle>) and pass a SessionState built "
                    + "from your mount and unmount callbacks to Graphitron.newOwnedExecutionInput; see "
                    + "the Session identity section of the plugin configuration reference");
        }
        if (handle != null && handle.isBlank()) {
            throw new IllegalArgumentException(
                "<handle> must name a class by its fully qualified name (e.g. com.example.Identity); "
                    + "remove the element if the mount returns no handle");
        }
        return new Callbacks(Optional.ofNullable(handle).map(String::trim));
    }
}
