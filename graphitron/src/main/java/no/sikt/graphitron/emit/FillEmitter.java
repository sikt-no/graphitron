package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Emits a service call's fills from one row of {@link FillQuery}: the value each parameter of the
 * method is called with, in position order. It writes inside the body the frame calls, so it reads
 * the body's own names: {@code env} for the fetch, {@code dsl} for the jOOQ context, and under an
 * object {@code keys} for the batch.
 *
 * <pre>{@code
 * Integer limit = env.getArgument("limit");
 * @SuppressWarnings("unchecked") List<Integer> ids = env.getArgument("input") instanceof Map<?, ?> map1
 *     ? (List<Integer>) map1.get("ids") : null;
 * String userId = (String) graphitronContext(env).getContextArgument(env, "userId");
 * FilmKeySummary summary = TenantConnections.sessionHandle(dsl, "Query.callPrincipal");
 * ...
 * Service.method(dsl, limit, ids, userId, summary)
 * }</pre>
 *
 * <p>A value the wire hands over as the parameter takes it is passed as it is: the jOOQ context, a
 * context value, the session handle or one of its members, the batch's keys where the parameter
 * takes them in the frame's shape, and an argument or input field of a scalar type, read null-safe
 * through the input objects above it. Anything else has to be built into the parameter's type
 * first, an input object into a class, an enum into its Java type, a node id into its key, keys
 * into another shape, and this emitter does not build: it says which parameter needs building and
 * why, and emits nothing.
 */
public final class FillEmitter {

    private FillEmitter() {}

    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final String CONFIGURATION = "org.jooq.Configuration";
    /** The body's own names, which a local named after a parameter must not shadow. */
    private static final Set<String> TAKEN = Set.of("env", "dsl", "keys", "batch", "first");

    /**
     * The generated runtime the fills read through: the request context's value under a key, the
     * mounted session handle, and one member of it, the last two guarded with the field's coordinate.
     */
    public record Runtime(Function<String, CodeBlock> contextArgument,
                          Function<String, CodeBlock> sessionHandle,
                          BiFunction<String, String, CodeBlock> sessionMember) {
        public Runtime {
            Objects.requireNonNull(contextArgument, "contextArgument");
            Objects.requireNonNull(sessionHandle, "sessionHandle");
            Objects.requireNonNull(sessionMember, "sessionMember");
        }
    }

    /** What the emitter makes of a call: its fills, or the first parameter it cannot fill. */
    public sealed interface Result {
        /** The locals to declare in the body, then the arguments to call the method with. */
        record Emitted(List<CodeBlock> statements, List<CodeBlock> arguments) implements Result {
            public Emitted {
                statements = List.copyOf(statements);
                arguments = List.copyOf(arguments);
            }
        }

        /** A parameter whose value has to be built first, and why. */
        record NeedsBuilding(int position, String parameterName, String reason) implements Result {}
    }

    /**
     * The fills of {@code row}'s call. {@code keysType} is the type of the frame's batch of keys
     * under an object, empty at a root.
     */
    public static Result emit(FillQuery.Row row, Optional<TypeName> keysType, Runtime runtime) {
        if (!row.complete()) {
            throw new IllegalStateException("the call at " + row.coordinate()
                + " has a parameter nothing fills, which the store reports where it is written");
        }
        var statements = new ArrayList<CodeBlock>();
        var arguments = new ArrayList<CodeBlock>();
        for (var fill : row.fills()) {
            var type = SessionIdentityRows.typeOf(fill.parameterType());
            var local = local(fill);
            switch (fill.fill()) {
                case "DSL_CONTEXT" -> arguments.add(CONFIGURATION.equals(fill.erasedClass())
                    ? CodeBlock.of("dsl.configuration()") : CodeBlock.of("dsl"));
                case "SOURCE" -> {
                    if (keysType.isEmpty() || !keysType.get().equals(type)) {
                        return new Result.NeedsBuilding(fill.position(), fill.parameterName(),
                            "the call takes its keys as " + type + ", and the frame hands them over as "
                            + keysType.map(TypeName::toString).orElse("nothing"));
                    }
                    arguments.add(CodeBlock.of("keys"));
                }
                case "CONTEXT_KEY" -> {
                    statements.add(cast(type, local, runtime.contextArgument().apply(fill.contextKey())));
                    arguments.add(CodeBlock.of("$L", local));
                }
                case "SESSION" -> {
                    statements.add(CodeBlock.of("$T $L = $L", type, local,
                        runtime.sessionHandle().apply(row.fieldCoordinate())));
                    arguments.add(CodeBlock.of("$L", local));
                }
                case "SESSION_MEMBER" -> {
                    statements.add(CodeBlock.of("$T $L = $L", type, local,
                        runtime.sessionMember().apply(fill.sessionMember(), row.fieldCoordinate())));
                    arguments.add(CodeBlock.of("$L", local));
                }
                case "ARGUMENT" -> {
                    var landing = fill.landing().orElseThrow();
                    if (landing.nodeId() || !"SCALAR".equals(landing.typeKind())) {
                        return new Result.NeedsBuilding(fill.position(), fill.parameterName(),
                            landing.nodeId() ? "it takes a node id, which is decoded into its key"
                                : "it takes " + landing.typeKind().toLowerCase().replace('_', ' ')
                                    + " " + String.join(".", landing.path())
                                    + ", which is built into " + type);
                    }
                    statements.add(argument(type, local, landing.path()));
                    arguments.add(CodeBlock.of("$L", local));
                }
                default -> throw new IllegalStateException("no fill " + fill.fill() + " at "
                    + row.coordinate() + " position " + fill.position());
            }
        }
        return new Result.Emitted(statements, arguments);
    }

    /**
     * An argument read off the fetch: the argument itself target-typed, an input field below it
     * through each input object on the way, {@code null} wherever one is absent.
     */
    private static CodeBlock argument(TypeName type, String local, List<String> path) {
        if (path.size() == 1) {
            return CodeBlock.of("$T $L = env.getArgument($S)", type, local, path.getFirst());
        }
        var read = CodeBlock.builder().add("env.getArgument($S) instanceof $T<?, ?> map1", path.getFirst(), MAP);
        for (int i = 1; i < path.size() - 1; i++) {
            read.add(" && map$L.get($S) instanceof $T<?, ?> map$L", i, path.get(i), MAP, i + 1);
        }
        int last = path.size() - 1;
        // Boxed, since an absent level reads null; a primitive parameter unboxes it at the call.
        var boxed = type.box();
        var value = CodeBlock.of("$L ? ($T) map$L.get($S) : null", read.build(), boxed, last, path.get(last));
        return declare(boxed, local, value);
    }

    /** A value read as {@code Object} and cast to the parameter's type. */
    private static CodeBlock cast(TypeName type, String local, CodeBlock read) {
        var target = type instanceof ParameterizedTypeName ? type : type.box();
        return declare(type, local, CodeBlock.of("($T) $L", target, read));
    }

    /**
     * A local of the parameter's type. A cast from {@code Object} to a generic type cannot be
     * checked, which the build treats as an error, so such a local suppresses that one warning at the
     * narrowest scope it can, the declaration itself.
     */
    private static CodeBlock declare(TypeName type, String local, CodeBlock value) {
        return type instanceof ParameterizedTypeName
            ? CodeBlock.of("@$T($S) $T $L = $L", SuppressWarnings.class, "unchecked", type, local, value)
            : CodeBlock.of("$T $L = $L", type, local, value);
    }

    /** The local a parameter's value is held in: its own name, unless the body already uses it. */
    private static String local(FillQuery.Fill fill) {
        var name = fill.parameterName() == null ? "parameter" + fill.position() : fill.parameterName();
        return TAKEN.contains(name) ? name + "Parameter" : name;
    }
}
