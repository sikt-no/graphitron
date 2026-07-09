package no.sikt.graphitron.rewrite.maven;

import java.util.List;

/**
 * POM XML binding for the {@code <sessionState>} block (R429 slice 3). Collapses into a
 * {@link no.sikt.graphitron.rewrite.session.SessionStateConfig} on
 * {@link no.sikt.graphitron.rewrite.RewriteContext}, from which
 * {@code ConnectionRuntimeClassGenerator} emits the concrete {@code SessionHook}.
 *
 * <p>Two mutually-exclusive forms; configure one, not both:
 *
 * <p><b>Function-hook form</b> names consumer-authored database callables:
 * <pre>{@code
 * <sessionState>
 *   <connect><call>Pk_Ras.Connect</call><handle>true</handle></connect>       <!-- (p_claims IN, p_handle OUT) -->
 *   <disconnect><call>Pk_Ras.Disconnect</call><handle>true</handle></disconnect> <!-- (p_handle IN) -->
 * </sessionState>
 * }</pre>
 * A {@code <handle>true</handle>} on both sides means connect produces an OUT handle that disconnect
 * binds; it must be declared on both or neither. An empty {@code <disconnect/>} (no {@code <call>}) is
 * the explicit unmount-free opt-out.
 *
 * <p><b>Postgres {@code <variables>} sugar</b> generates both hook halves from one variable set, so a
 * consumer writes no SQL:
 * <pre>{@code
 * <sessionState>
 *   <variables>
 *     <variable><name>app.user_id</name><claim>sub</claim></variable>
 *   </variables>
 * </sessionState>
 * }</pre>
 * The pairing, handle-consistency, and one-form-only rejections are enforced by
 * {@code SessionStateConfig.from(...)} at config build; a defective block fails the build.
 */
public class SessionStateBinding {
    /** The {@code <connect>} callable (function-hook form). */
    HookBinding connect;
    /** The {@code <disconnect>} callable (function-hook form); an empty element is the unmount-free opt-out. */
    HookBinding disconnect;
    /** The {@code <variables>} sugar entries (Postgres form). */
    List<VariableBinding> variables;

    /** One {@code <connect>} / {@code <disconnect>} callable: a database call name and optional handle flag. */
    public static class HookBinding {
        /** The database callable name, e.g. {@code Pk_Ras.Connect}. Absent on the empty-{@code <disconnect/>} opt-out. */
        String call;
        /** True when this callable produces (connect) or binds (disconnect) the opaque OUT handle. */
        boolean handle;
    }

    /** One {@code <variable><name>app.user_id</name><claim>sub</claim></variable>}: a session GUC name and the claim it reads. */
    public static class VariableBinding {
        /** The session variable (Postgres GUC) name to set, e.g. {@code app.user_id}. */
        String name;
        /** The claim key read from the payload JSON, e.g. {@code sub}. */
        String claim;
    }
}
