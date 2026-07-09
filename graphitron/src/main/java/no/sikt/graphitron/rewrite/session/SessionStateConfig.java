package no.sikt.graphitron.rewrite.session;

import java.util.List;

/**
 * R429 slice 3 — the resolved, validated session-identity configuration built once from the Maven
 * {@code <sessionState>} block and threaded through {@link no.sikt.graphitron.rewrite.RewriteContext}
 * to {@code ConnectionRuntimeClassGenerator}, which emits a concrete {@code SessionHook} from it (or
 * keeps {@code SessionHook.NONE} for {@link None}).
 *
 * <p>The three forms are a sealed hierarchy so the emitter forks on an exhaustive {@code switch} (a
 * fourth form becomes a compile error at the emission seam) rather than on predicates over a wider
 * tuple. The shape is chosen so illegal states are unrepresentable: a handle lives inside
 * {@link Unmount.PairedDisconnect}, so "a handle produced by connect that no disconnect binds" cannot
 * be constructed, it collapses into {@link #from} rather than surviving as a field the emitter must
 * re-check.
 *
 * <p>All cross-form and pairing rejections live in {@link #from}, which throws
 * {@link IllegalArgumentException} naming the offending configuration; the Maven seam
 * ({@code AbstractRewriteMojo.buildSessionStateConfig}) turns that into a build failure, mirroring the
 * {@code LintConfig.validated} precedent. Config-shape defects are a {@code pom.xml} concern with no
 * SDL coordinate, so they are validated here rather than routed through {@code GraphitronSchemaValidator}.
 */
public sealed interface SessionStateConfig permits SessionStateConfig.None, SessionStateConfig.FunctionHooks, SessionStateConfig.Variables {

    /** No {@code <sessionState>} configured: the runtime keeps the no-op {@code SessionHook.NONE}. */
    record None() implements SessionStateConfig {
        public static final None INSTANCE = new None();
    }

    /**
     * The function-hook form: consumer-authored database callables named by {@code <connect call>} /
     * {@code <disconnect call>}. {@code connectCall} mounts identity from the claims payload; the
     * {@link Unmount} says how (and whether) identity is unmounted.
     */
    record FunctionHooks(String connectCall, Unmount unmount) implements SessionStateConfig {
        public FunctionHooks {
            if (connectCall == null || connectCall.isBlank()) {
                throw new IllegalArgumentException("<connect> requires a non-blank <call>");
            }
            if (unmount == null) {
                throw new IllegalArgumentException("FunctionHooks requires an Unmount");
            }
        }
    }

    /**
     * The Postgres {@code <variables>} sugar: graphitron generates both hook halves from this one
     * resolved variable set, so "disconnect clears exactly what connect set" is structural, not a
     * prose agreement between two emitters.
     */
    record Variables(List<Variable> variables) implements SessionStateConfig {
        public Variables {
            if (variables == null || variables.isEmpty()) {
                throw new IllegalArgumentException("<variables> requires at least one <variable>");
            }
            variables = List.copyOf(variables);
        }
    }

    /** One {@code <variable><name>app.user_id</name><claim>sub</claim></variable>}: a session GUC name and the claim it reads. */
    record Variable(String name, String claim) {
        public Variable {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("<variable> requires a non-blank <name>");
            }
            if (claim == null || claim.isBlank()) {
                throw new IllegalArgumentException("<variable> with <name>" + name + "</name> requires a non-blank <claim>");
            }
        }
    }

    /**
     * How the function-hook form unmounts identity. Sealed so "handle produced but not bound" is
     * unrepresentable: the handle boolean lives on {@link PairedDisconnect}, and {@link UnmountFree}
     * carries none.
     */
    sealed interface Unmount permits Unmount.PairedDisconnect, Unmount.UnmountFree {
        /** A disconnect callable unmounts identity; {@code handle} is true iff connect produces an OUT handle it binds. */
        record PairedDisconnect(String call, boolean handle) implements Unmount {
            public PairedDisconnect {
                if (call == null || call.isBlank()) {
                    throw new IllegalArgumentException("<disconnect> requires a non-blank <call>");
                }
            }
        }

        /**
         * The explicit unmount-free opt-out ({@code <disconnect/>} with no call): connect mounts state
         * that provably never unmounts. Slice 6's generation-time warning names this exposure.
         */
        record UnmountFree() implements Unmount {
            public static final UnmountFree INSTANCE = new UnmountFree();
        }
    }

    /** The no-configuration form. */
    static SessionStateConfig none() {
        return None.INSTANCE;
    }

    /**
     * Reconciles the raw {@code <sessionState>} shape into a validated config, or throws
     * {@link IllegalArgumentException} naming the offending combination. A {@code null} hook means the
     * element was absent; a {@link RawHook} with a {@code null} call means the element was present but
     * empty ({@code <disconnect/>}), the explicit unmount-free marker.
     *
     * @param connect   the {@code <connect>} element, or {@code null} if absent
     * @param disconnect the {@code <disconnect>} element, or {@code null} if absent
     * @param variables  the {@code <variables>} entries, empty if the block is absent
     */
    static SessionStateConfig from(RawHook connect, RawHook disconnect, List<Variable> variables) {
        boolean hasFunction = connect != null || disconnect != null;
        boolean hasVariables = variables != null && !variables.isEmpty();

        if (hasVariables && hasFunction) {
            throw new IllegalArgumentException(
                "<sessionState> configures both <variables> and <connect>/<disconnect>; choose one form "
                    + "(the <variables> sugar or the function-hook callables), not both");
        }
        if (hasVariables) {
            return new Variables(variables);
        }
        if (!hasFunction) {
            return none();
        }
        // Function-hook form: a connect and a disconnect must be paired; a connect that mounts identity
        // with no disconnect is a configuration whose identity provably never unmounts, a security hole,
        // so it is rejected unless the empty-<disconnect/> opt-out is present.
        if (connect == null) {
            throw new IllegalArgumentException(
                "<sessionState> has a <disconnect> but no <connect>; identity cannot be unmounted without "
                    + "first being mounted");
        }
        if (connect.call() == null || connect.call().isBlank()) {
            throw new IllegalArgumentException("<connect> requires a non-blank <call>");
        }
        if (disconnect == null) {
            throw new IllegalArgumentException(
                "<sessionState> has a <connect> but no <disconnect>; identity that mounts must unmount. "
                    + "Add <disconnect><call>...</call></disconnect>, or an explicit empty <disconnect/> to opt "
                    + "out of unmounting (a genuinely unmount-free design)");
        }
        boolean unmountFree = disconnect.call() == null || disconnect.call().isBlank();
        if (unmountFree) {
            if (connect.handle()) {
                throw new IllegalArgumentException(
                    "<connect handle=\"true\"> produces a handle, but the empty <disconnect/> opt-out binds "
                        + "none; a produced handle must be bound by a <disconnect call=\"...\">");
            }
            return new FunctionHooks(connect.call(), Unmount.UnmountFree.INSTANCE);
        }
        if (connect.handle() != disconnect.handle()) {
            throw new IllegalArgumentException(
                "handle must be declared on both <connect> and <disconnect> or neither; a handle produced "
                    + "by connect and not bound by disconnect (or bound but never produced) is a mismatch");
        }
        return new FunctionHooks(connect.call(), new Unmount.PairedDisconnect(disconnect.call(), disconnect.handle()));
    }

    /**
     * The raw shape of one {@code <connect>} / {@code <disconnect>} element as read from the POM, before
     * reconciliation. A {@code null} {@code call} with the element present is the empty-element marker.
     */
    record RawHook(String call, boolean handle) {}
}
