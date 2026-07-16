package no.sikt.graphitron.rewrite.session;

import java.util.List;

/**
 * The resolved, validated session-identity configuration built once from the Maven
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
     * {@link Unmount} says how (and whether) identity is unmounted, and carries the declared survival
     * opt-in where a balanced pair exists (see {@link Unmount.PairedDisconnect#survivesTransactions}).
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
     * carries none. The survival opt-in lives here too, for the same reason: "this mount/unmount pair
     * can be re-fired around a transaction settle" is a fact about a balanced pair, so only
     * {@link PairedDisconnect} can assert or decline it; an {@link UnmountFree} hook has no pair to
     * re-fire and never does.
     */
    sealed interface Unmount permits Unmount.PairedDisconnect, Unmount.UnmountFree {
        /**
         * A disconnect callable unmounts identity; {@code handle} is true iff connect produces an OUT
         * handle it binds.
         *
         * <p>{@code survivesTransactions} is the declared survival opt-in
         * ({@code <stateSurvivesTransactions>true</stateSurvivesTransactions>}): the consumer confirms
         * the connect callable's mounted state survives transaction commit and rollback, so
         * acquisition-scoped mounting suffices. Unconfirmed (the default), graphitron cannot assume
         * survival and re-fires the pair (disconnect with the old handle, connect capturing a new one)
         * after each top-level transaction settle, so a settle can never leave stale or reverted
         * identity. Queries run in autocommit and never settle, so the re-fire never taxes the read
         * path. The {@code <variables>} sugar needs no flag: its {@code set_config} mounts run in
         * autocommit (enforced at acquisition) and session-scoped GUCs survive settles, so it opts in
         * structurally.
         */
        record PairedDisconnect(String call, boolean handle, boolean survivesTransactions) implements Unmount {
            public PairedDisconnect {
                if (call == null || call.isBlank()) {
                    throw new IllegalArgumentException("<disconnect> requires a non-blank <call>");
                }
            }

            /** Convenience constructor for the unconfirmed default (re-fire after each settle). */
            public PairedDisconnect(String call, boolean handle) {
                this(call, handle, false);
            }
        }

        /**
         * The explicit unmount-free opt-out ({@code <disconnect/>} with no call): connect mounts state
         * that provably never unmounts. The generation-time warning in {@link SessionStateWarnings}
         * names this exposure.
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
     * Convenience overload of {@link #from(RawHook, RawHook, List, Boolean)} with no declared
     * survival opt-in (the safe default: unconfirmed function hooks re-fire after each settle).
     */
    static SessionStateConfig from(RawHook connect, RawHook disconnect, List<Variable> variables) {
        return from(connect, disconnect, variables, null);
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
     * @param stateSurvivesTransactions the {@code <stateSurvivesTransactions>} element, or {@code null}
     *                                  if absent; only meaningful on the function-hook form
     */
    static SessionStateConfig from(RawHook connect, RawHook disconnect, List<Variable> variables,
                                   Boolean stateSurvivesTransactions) {
        boolean hasFunction = connect != null || disconnect != null;
        boolean hasVariables = variables != null && !variables.isEmpty();

        if (hasVariables && hasFunction) {
            throw new IllegalArgumentException(
                "<sessionState> configures both <variables> and <connect>/<disconnect>; choose one form "
                    + "(the <variables> sugar or the function-hook callables), not both");
        }
        if (stateSurvivesTransactions != null && !hasFunction) {
            // The flag answers a question only consumer-authored hooks raise: the <variables> sugar's
            // survival is structural (autocommit mounts of session-scoped GUCs), and with no hooks there
            // is no state to survive. A declaration that can mean nothing fails loud, like the pairing rules.
            throw new IllegalArgumentException(
                "<stateSurvivesTransactions> applies only to the function-hook form (<connect>/<disconnect>); "
                    + (hasVariables
                        ? "the <variables> sugar survives transaction settles structurally and needs no declaration"
                        : "there are no hooks whose state it could describe"));
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
            if (stateSurvivesTransactions != null) {
                // Survival is a fact about a balanced mount/unmount pair; the unmount-free opt-out has no
                // pair to re-fire, so there is no fallback the declaration could opt out of.
                throw new IllegalArgumentException(
                    "<stateSurvivesTransactions> requires a paired <disconnect call=\"...\">; the empty "
                        + "<disconnect/> opt-out has no mount/unmount pair to re-fire around a transaction "
                        + "settle, so the declaration describes nothing");
            }
            return new FunctionHooks(connect.call(), Unmount.UnmountFree.INSTANCE);
        }
        if (connect.handle() != disconnect.handle()) {
            throw new IllegalArgumentException(
                "handle must be declared on both <connect> and <disconnect> or neither; a handle produced "
                    + "by connect and not bound by disconnect (or bound but never produced) is a mismatch");
        }
        return new FunctionHooks(connect.call(), new Unmount.PairedDisconnect(
            disconnect.call(), disconnect.handle(), Boolean.TRUE.equals(stateSurvivesTransactions)));
    }

    /**
     * The raw shape of one {@code <connect>} / {@code <disconnect>} element as read from the POM, before
     * reconciliation. A {@code null} {@code call} with the element present is the empty-element marker.
     */
    record RawHook(String call, boolean handle) {}
}
