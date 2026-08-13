package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Routines;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionHandleRecord;
import org.jooq.Configuration;

/**
 * The hand-written facade shape of the {@code <sessionState>} method-hook contract: a public
 * static mount with exactly one seam parameter ({@code Configuration}), a payload the facade
 * reshapes before binding (one raw claims JSON string, split into the composite the database
 * routine takes), and the routine's composite handle as the return type. The sibling shape,
 * naming the jOOQ-generated executing method directly
 * ({@code Routines#sessionConnect}), is configured on another execution of the same reactor, so
 * "generated and hand-written are indistinguishable to the resolver" is exercised rather than
 * asserted.
 *
 * <p>Contract notes this class exists to model: no checked exceptions (the JDBC residue inside
 * the generated routine call already surfaces unchecked through jOOQ); session-scoped state only
 * ({@code session_connect} issues {@code set_config(..., false)}); and a payload parameter name
 * ({@code claims}) that is the public factory-slot identity on
 * {@code Graphitron.newOwnedExecutionInput(...)}.
 */
public final class SakilaSessionIdentity {

    private SakilaSessionIdentity() {}

    /**
     * Mounts identity from the raw claims JSON: the {@code sub} claim (absent means no
     * identity, matching the RLS policies' fail-closed treatment of the empty string) is bound
     * into the composite payload the database routine takes.
     */
    public static SessionHandleRecord mount(Configuration cfg, String claims) {
        return Routines.sessionConnect(cfg, new SessionClaimsRecord(subOf(claims), null));
    }

    /** Unmounts through the paired routine, bound to the handle mount returned. */
    public static void unmount(Configuration cfg, SessionHandleRecord handle) {
        Routines.sessionDisconnect(cfg, handle);
    }

    /**
     * The {@code sub} claim of a flat JSON claims document, or {@code null} when absent. A
     * deliberately minimal extraction: the fixture payloads are one-level JSON objects with
     * string values, and this facade's point is the reshaping seam, not JSON parsing.
     */
    private static String subOf(String claims) {
        if (claims == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"").matcher(claims);
        return matcher.find() ? matcher.group(1) : null;
    }
}
