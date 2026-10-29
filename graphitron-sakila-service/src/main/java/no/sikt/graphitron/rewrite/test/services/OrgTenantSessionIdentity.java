package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The mount of the converted-tenant fixture, configured on its {@code <tenantColumn>owner_org}
 * execution: a handle-less mount whose tenant is an {@code Optional<String>}, the Java type the
 * converter gives {@code owner_org}. It touches no database; it records which tenant each
 * connection was mounted for, so an execution test can assert that a key supplied as
 * {@code "0186"} reaches the mount in the column's form, {@code "186"}.
 */
public final class OrgTenantSessionIdentity {

    /** One mount as the facade saw it: the claims' {@code sub} and the tenant it was mounted for. */
    public record Mount(String sub, Optional<String> tenant) {}

    /** Every mount in call order, across all concurrently running test classes. */
    public static final List<Mount> MOUNTS = new CopyOnWriteArrayList<>();

    private OrgTenantSessionIdentity() {}

    /** Records the mount; no session state is set. */
    public static void mount(Configuration cfg, Optional<String> tenant, String claims) {
        MOUNTS.add(new Mount(SakilaSessionIdentity.subOf(claims), tenant));
    }
}
