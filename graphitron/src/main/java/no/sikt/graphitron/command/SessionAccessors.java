package no.sikt.graphitron.command;

/**
 * The spelling of the connection runtime's guarded session-member accessors, shared by the one
 * place that emits them ({@code ConnectionRuntimeClassGenerator}) and the call sites that name
 * them, so the two cannot drift.
 */
public final class SessionAccessors {

    private SessionAccessors() {}

    /** The method on the generated {@code TenantConnections} that reads one member of the session handle. */
    public static String memberMethod(String member) {
        return "sessionMember_" + member;
    }
}
