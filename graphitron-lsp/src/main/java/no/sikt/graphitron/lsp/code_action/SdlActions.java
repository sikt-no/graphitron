package no.sikt.graphitron.lsp.code_action;

import java.util.List;

/**
 * Registry of every {@link SdlAction} the LSP knows how to apply. A quick-fix
 * action is registered here explicitly; it is never divined from a deprecation's
 * prose reason. Deprecation comments and quick-fix actions are independent:
 * a deprecation may carry no action, and an action need not correspond to a
 * deprecation. {@code SdlActionDriftTest} keeps the one remaining coupling honest,
 * an action that <em>does</em> target a deprecation must target a real one, so a
 * renamed or removed marker cannot leave a stale action behind.
 *
 * <p>The list is fetched fresh per request. An action needing build-derived facts
 * reads them where every other language-server surface does, from the fact store the
 * request already holds open, rather than from a projection handed in here.
 */
public final class SdlActions {

    private SdlActions() {}

    /**
     * Returns every {@link SdlAction} the LSP knows. Empty today: the
     * {@code ExternalCodeReference.name} migration retired with the argument
     * itself, and no other deprecation currently carries a registered fix. The
     * next directive rename or deprecation migration extends the list, and
     * {@link CodeActions} needs no change to pick it up.
     */
    public static List<SdlAction> all() {
        return List.of();
    }
}
