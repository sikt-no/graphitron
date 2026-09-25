package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Routines;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionHandleRecord;
import org.jooq.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The multi-tenant sibling of {@link SakilaSessionIdentity}, configured on the
 * {@code <tenantColumn>} execution: a mount that declares the tenant slot, a parameter typed
 * {@code Optional} of the tenant column's Java type ({@code film_id}, so {@code Integer}), which
 * graphitron recognises by type and fills with the tenant each connection is mounted for
 * ({@code Optional.empty()} for the default source). The slot is not payload, so the factory
 * still asks only for {@code claims}.
 *
 * <p>The tenant is bound into the {@code tenant} field of the composite the database routine
 * takes, the way a per-tenant identity (a VPD context, a per-tenant role set) would be. Every
 * mount is also recorded with its claims' {@code sub}, so an execution test can assert which
 * tenant each of its own mounts received while other test classes mount concurrently.
 */
public final class SakilaTenantSessionIdentity {

    /** One mount as the facade saw it: the claims' {@code sub} and the tenant it was mounted for. */
    public record Mount(String sub, Optional<Integer> tenant) {}

    /** Every mount in call order, across all concurrently running test classes. */
    public static final List<Mount> MOUNTS = new CopyOnWriteArrayList<>();

    private SakilaTenantSessionIdentity() {}

    /** Mounts identity for {@code tenant}, recording the call and binding the tenant into the claims. */
    public static SessionHandleRecord mount(Configuration cfg, Optional<Integer> tenant, String claims) {
        String sub = SakilaSessionIdentity.subOf(claims);
        MOUNTS.add(new Mount(sub, tenant));
        return Routines.sessionConnect(cfg,
            new SessionClaimsRecord(sub, tenant.map(String::valueOf).orElse(null)));
    }

    /** Unmounts through the paired routine, bound to the handle mount returned. */
    public static void unmount(Configuration cfg, SessionHandleRecord handle) {
        Routines.sessionDisconnect(cfg, handle);
    }
}
