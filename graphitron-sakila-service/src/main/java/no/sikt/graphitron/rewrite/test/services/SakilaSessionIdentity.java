package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.Routines;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord;
import org.jooq.Configuration;

/**
 * A hand-written facade the example's session callbacks call: a mount taking the jOOQ
 * {@code Configuration} a {@code MountedSession} carries and a payload the facade reshapes before
 * binding (one raw claims JSON string, split into the composite the database routine takes), and
 * returning the routine's composite handle, which the unmount takes back. The sibling shape, calling
 * the jOOQ-generated executing method ({@code Routines#sessionConnect}) straight from the
 * callback, is configured on another execution of the same reactor, so both are exercised.
 *
 * <p>Contract notes this class exists to model: no checked exceptions (the JDBC residue inside
 * the generated routine call already surfaces unchecked through jOOQ), and session-scoped state
 * only ({@code session_connect} issues {@code set_config(..., false)}).
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
    static String subOf(String claims) {
        if (claims == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"").matcher(claims);
        return matcher.find() ? matcher.group(1) : null;
    }
}
