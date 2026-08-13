package no.sikt.graphitron.rewrite.session;

import java.util.Optional;

/**
 * The validated authored {@code <sessionState>} configuration built once from the Maven block
 * and threaded through {@link no.sikt.graphitron.rewrite.RewriteContext} into the schema build.
 *
 * <p>This carrier is <em>strings only</em>, deliberately: fact capture transcribes the config
 * object verbatim into the authored-facts provenance family
 * ({@code ConfigurationFactCapture}), so a resolved method reference on this arm would leak
 * reflected facts into relations that store only what the author wrote. The reflected
 * signatures live on the model's resolved carrier ({@link SessionHooks}), minted by
 * {@code GraphitronSchemaBuilder} from these strings. The authored config has exactly two
 * readers, {@link SessionStateWarnings#forConfig} and fact capture's transcription; every
 * emit-side decision reads the resolved carrier instead.
 *
 * <p>Config-shape defects (a malformed {@code fqcn#method}, {@code <unmount>} without
 * {@code <mount>}) are validated in {@link #from}, which throws
 * {@link IllegalArgumentException} naming the offending configuration; the Maven seam
 * ({@code AbstractRewriteMojo.buildSessionStateConfig}) turns that into a build failure,
 * mirroring the {@code LintConfig.validated} precedent. A {@code pom.xml} defect has no SDL
 * coordinate, so it is validated here rather than routed through
 * {@code GraphitronSchemaValidator}; failures of the <em>referenced methods</em> (unresolvable
 * class, no seam parameter, ...) are reflection facts and drain as typed rejections in the
 * builder instead.
 */
public sealed interface SessionStateConfig permits SessionStateConfig.None, SessionStateConfig.MethodHooks {

    /** No {@code <sessionState>} configured: the generated runtime mounts no identity. */
    record None() implements SessionStateConfig {
        public static final None INSTANCE = new None();
    }

    /**
     * The method-hook form: {@code <mount>} names a public static method graphitron calls on
     * each connection it acquires, before any SQL on it; the optional {@code <unmount>} runs
     * when the connection goes back to the pool. Both are authored {@code fqcn#method} strings;
     * omitting {@code <unmount>} means mount-only (the next request's mount overwrites
     * wholesale), with no opt-out ceremony.
     */
    record MethodHooks(HookRef mount, Optional<HookRef> unmount) implements SessionStateConfig {
        public MethodHooks {
            if (mount == null) {
                throw new IllegalArgumentException("<sessionState> requires a <mount>");
            }
            if (unmount == null) {
                throw new IllegalArgumentException("MethodHooks requires a non-null unmount Optional");
            }
        }
    }

    /**
     * One authored {@code fqcn#method} reference, split at the {@code #} but otherwise verbatim.
     * {@link #raw()} reproduces the authored string for fact capture and messages.
     */
    record HookRef(String className, String methodName) {
        public HookRef {
            if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
                throw new IllegalArgumentException("a hook reference requires both a class and a method name");
            }
        }

        /** The authored form: {@code com.example.KernelIdentity#mount}. */
        public String raw() {
            return className + "#" + methodName;
        }

        /**
         * Parses an authored {@code fqcn#method} string, throwing {@link IllegalArgumentException}
         * naming {@code element} (the POM element, e.g. {@code <mount>}) when the shape is wrong.
         */
        static HookRef parse(String raw, String element) {
            String trimmed = raw.trim();
            int hash = trimmed.indexOf('#');
            if (hash <= 0 || hash != trimmed.lastIndexOf('#') || hash == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                    element + " must name a method as fqcn#method (e.g. com.example.db.Routines#connect) — got '"
                        + trimmed + "'");
            }
            return new HookRef(trimmed.substring(0, hash), trimmed.substring(hash + 1));
        }
    }

    /** The no-configuration form. */
    static SessionStateConfig none() {
        return None.INSTANCE;
    }

    /**
     * Reconciles the raw {@code <sessionState>} strings into a validated config, or throws
     * {@link IllegalArgumentException} naming the offending combination. {@code null} means the
     * element was absent; a present-but-blank element is a defect, never silently absent.
     *
     * @param mount   the {@code <mount>} element's text, or {@code null} if absent
     * @param unmount the {@code <unmount>} element's text, or {@code null} if absent
     */
    static SessionStateConfig from(String mount, String unmount) {
        if (mount != null && mount.isBlank()) {
            throw new IllegalArgumentException(
                "<mount> must name a method as fqcn#method (e.g. com.example.db.Routines#connect); "
                    + "remove the element if no identity is mounted");
        }
        if (unmount != null && unmount.isBlank()) {
            throw new IllegalArgumentException(
                "<unmount> must name a method as fqcn#method; remove the element for a mount-only configuration");
        }
        if (mount == null && unmount != null) {
            // Unmounting what nothing mounted is a defect in either direction of reading it.
            throw new IllegalArgumentException(
                "<sessionState> has an <unmount> but no <mount>; identity cannot be unmounted without "
                    + "first being mounted");
        }
        if (mount == null) {
            return none();
        }
        return new MethodHooks(
            HookRef.parse(mount, "<mount>"),
            Optional.ofNullable(unmount).map(u -> HookRef.parse(u, "<unmount>")));
    }
}
