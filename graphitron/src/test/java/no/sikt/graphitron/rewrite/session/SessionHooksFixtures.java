package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hand-built {@link SessionHooks} carriers for tiers that do not run the builder's reflection:
 * the same shapes {@code ServiceCatalog.resolveSessionHooks} mints, assembled directly so unit
 * and pipeline fixtures need no consumer classes on the classpath.
 */
public final class SessionHooksFixtures {

    private SessionHooksFixtures() {}

    /** The jOOQ-{@code Configuration} seam parameter. */
    public static MethodRef.Param.Typed configurationSeam() {
        return new MethodRef.Param.Typed("cfg", "org.jooq.Configuration",
            ClassName.get("org.jooq", "Configuration"),
            new ParamSource.SessionSeam(ParamSource.SessionSeam.Kind.CONFIGURATION));
    }

    /** The raw-JDBC {@code Connection} seam parameter. */
    public static MethodRef.Param.Typed connectionSeam() {
        return new MethodRef.Param.Typed("connection", "java.sql.Connection",
            ClassName.get("java.sql", "Connection"),
            new ParamSource.SessionSeam(ParamSource.SessionSeam.Kind.CONNECTION));
    }

    /** A payload parameter ({@link ParamSource.Context}-sourced), the factory-slot shape. */
    public static MethodRef.Param.Typed payload(String name, TypeName javaType) {
        return new MethodRef.Param.Typed(name, javaType.toString(), javaType, new ParamSource.Context());
    }

    /** A {@code String} payload parameter, the dev-tool-constructible shape. */
    public static MethodRef.Param.Typed stringPayload(String name) {
        return payload(name, ClassName.get(String.class));
    }

    /** The unmount's handle parameter ({@link ParamSource.SessionHandle}-sourced). */
    public static MethodRef.Param.Typed handleParam(TypeName javaType) {
        return new MethodRef.Param.Typed("handle", javaType.toString(), javaType,
            new ParamSource.SessionHandle());
    }

    /** A resolved static hook method reference. */
    public static MethodRef.StaticOnly hookRef(String className, String methodName,
            TypeName returnType, MethodRef.Param... params) {
        return new MethodRef.StaticOnly(className, methodName, returnType, List.of(params), List.of());
    }

    /** A handle-less mount (returns void) with the given payload parameters, no unmount. */
    public static SessionHooks handleLess(MethodRef.Param.Typed... payload) {
        var params = new ArrayList<MethodRef.Param>();
        params.add(configurationSeam());
        params.addAll(List.of(payload));
        return new SessionHooks.HandleLess(
            new MethodRef.StaticOnly("com.example.Hooks", "mount", TypeName.VOID,
                List.copyOf(params), List.of()),
            Optional.empty());
    }

    /** A handled mount returning {@code handleType} with the given payload, plus a paired unmount. */
    public static SessionHooks handled(TypeName handleType, MethodRef.Param.Typed... payload) {
        var mountParams = new ArrayList<MethodRef.Param>();
        mountParams.add(configurationSeam());
        mountParams.addAll(List.of(payload));
        var mount = new MethodRef.StaticOnly("com.example.Hooks", "mount", handleType,
            List.copyOf(mountParams), List.of());
        var unmount = new MethodRef.StaticOnly("com.example.Hooks", "unmount", TypeName.VOID,
            List.of(configurationSeam(), handleParam(handleType)), List.of());
        return new SessionHooks.Handled(mount, handleType, Optional.of(unmount));
    }

    /**
     * The {@code RecordingHookFixture} pair for the emitted-code harness tests: a raw-JDBC
     * ({@code Connection}-seam) mount with one {@code String} payload parameter named
     * {@code claims}, returning a {@code String} handle, plus the paired unmount.
     */
    public static SessionHooks recordingConnectionHooks() {
        String fixture = "no.sikt.graphitron.rewrite.generators.util.RecordingHookFixture";
        var mount = new MethodRef.StaticOnly(fixture, "mount", ClassName.get(String.class),
            List.of(connectionSeam(), stringPayload("claims")), List.of());
        var unmount = new MethodRef.StaticOnly(fixture, "unmount", TypeName.VOID,
            List.of(connectionSeam(), handleParam(ClassName.get(String.class))), List.of());
        return new SessionHooks.Handled(mount, ClassName.get(String.class), Optional.of(unmount));
    }

    /** A copy of {@code model} carrying {@code hooks}, with the classifier population recomputed. */
    public static GraphitronSchema withHooks(GraphitronSchema model, SessionHooks hooks) {
        return new GraphitronSchema(model.types(), model.fields(), model.entitiesByType(),
            model.warnings(), model.diagnostics(), model.arrivals(), model.reachableSourceShapes(),
            model.tenantScopes(), model.tenantBindings(), model.argumentReachableInputs(),
            model.connectionSynthesis(), model.operationMembers(), model.deliveryFacts(), hooks);
    }
}
