package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * through the input objects above it. An enum arrives as its runtime value's string, so it is
 * passed as it is into a {@code String} and through {@code valueOf} into a Java enum. Anything else
 * has to be built into the parameter's type first, an input object into a class, a node id into its
 * key, keys into another shape, and this emitter does not build: it says which parameter needs
 * building and why, and emits nothing.
 */
public final class FillEmitter {

    private FillEmitter() {}

    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName STRING = ClassName.get("java.lang", "String");
    /** The landing types a fill reads as they arrive: an enum arrives as its runtime value's string. */
    private static final Set<String> SCALAR_OR_ENUM = Set.of("SCALAR", "ENUM");
    private static final String CONFIGURATION = "org.jooq.Configuration";
    /** The body's own names, which a local named after a parameter must not shadow. */
    private static final Set<String> TAKEN = Set.of("env", "dsl", "keys", "batch", "first");

    /**
     * The generated runtime the fills read through: the request context's value under a key, the
     * mounted session handle, and one member of it, the last two guarded with the field's coordinate.
     */
    public record Runtime(Function<String, CodeBlock> contextArgument,
                          Function<String, CodeBlock> sessionHandle,
                          BiFunction<String, String, CodeBlock> sessionMember,
                          NodeIdDecodeEmitter.Runtime decode) {
        public Runtime {
            Objects.requireNonNull(contextArgument, "contextArgument");
            Objects.requireNonNull(sessionHandle, "sessionHandle");
            Objects.requireNonNull(sessionMember, "sessionMember");
        }

        /** A runtime for fills that build nothing through a node id decode. */
        public Runtime(Function<String, CodeBlock> contextArgument, Function<String, CodeBlock> sessionHandle,
                       BiFunction<String, String, CodeBlock> sessionMember) {
            this(contextArgument, sessionHandle, sessionMember, null);
        }
    }

    /** What the emitter makes of a call: its fills, or the first parameter it cannot fill. */
    public sealed interface Result {
        /** The locals to declare in the body, then the arguments to call the method with. */
        record Emitted(List<CodeBlock> statements, List<CodeBlock> arguments, List<MethodSpec> helpers)
                implements Result {
            public Emitted {
                helpers = List.copyOf(helpers);
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
        return emit(row, keysType, runtime, Map.of());
    }

    /**
     * The fills of {@code row}'s call, an input object made into its parameter's class as
     * {@code builds} says, by position: the helpers that make it ride along with the fills.
     */
    public static Result emit(FillQuery.Row row, Optional<TypeName> keysType, Runtime runtime,
                              Map<Integer, BuildQuery.Result> builds) {
        if (!row.complete()) {
            throw new IllegalStateException("the call at " + row.coordinate()
                + " has a parameter nothing fills, which the store reports where it is written");
        }
        var statements = new ArrayList<CodeBlock>();
        var helpers = new ArrayList<MethodSpec>();
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
                    if (unreadThroughList(landing)) {
                        return new Result.NeedsBuilding(fill.position(), fill.parameterName(),
                            String.join(".", landing.path()) + " is read through a list, which only a scalar is here");
                    }
                    if (landing.nodeId() && builds.get(fill.position()) instanceof BuildQuery.Result.Decoded decoded) {
                        var helper = "decode" + row.coordinate().replaceAll("[^A-Za-z0-9]", "_") + "_" + fill.position();
                        helpers.add(NodeIdDecodeEmitter.parameterHelper(helper, type, decoded.decode(), decoded.shape(),
                            String.join(".", landing.path()), runtime.decode()));
                        statements.add(argument(ClassName.get(Object.class), local + "Wire", landing.path()));
                        statements.add(CodeBlock.of("$T $L = $L($LWire)", type, local, helper, local));
                        arguments.add(CodeBlock.of("$L", local));
                        continue;
                    }
                    if (landing.nodeId() && builds.get(fill.position()) instanceof BuildQuery.Result.Refused refused) {
                        return new Result.NeedsBuilding(fill.position(), fill.parameterName(), refused.reason());
                    }
                    if (!landing.nodeId() && "INPUT_OBJECT".equals(landing.typeKind())) {
                        var build = builds.get(fill.position());
                        var helper = "build" + row.coordinate().replaceAll("[^A-Za-z0-9]", "_") + "_" + fill.position();
                        if (build instanceof BuildQuery.Result.Built built) {
                            helpers.addAll(BuildEmitter.helpers(built.build(), helper, runtime.decode()));
                        } else if (build instanceof BuildQuery.Result.Columns columns) {
                            helpers.add(BuildEmitter.recordHelper(columns.build(), helper, runtime.decode()));
                        } else {
                            return new Result.NeedsBuilding(fill.position(), fill.parameterName(),
                                build instanceof BuildQuery.Result.Refused refused ? refused.reason()
                                    : "nothing says how input object " + String.join(".", landing.path())
                                        + " is made into " + type);
                        }
                        statements.add(argument(ClassName.get(Object.class), local + "Wire", landing.path()));
                        statements.add(made(type, local, helper));
                        arguments.add(CodeBlock.of("$L", local));
                        continue;
                    }
                    if (landing.nodeId() || !SCALAR_OR_ENUM.contains(landing.typeKind())) {
                        return new Result.NeedsBuilding(fill.position(), fill.parameterName(),
                            landing.nodeId() ? "it takes a node id, which is decoded into its key"
                                : "it takes " + landing.typeKind().toLowerCase().replace('_', ' ')
                                    + " " + String.join(".", landing.path())
                                    + ", which is built into " + type);
                    }
                    if ("ENUM".equals(landing.typeKind()) && !STRING.equals(elementOf(type))) {
                        statements.addAll(enumValue(type, local, landing.path()));
                    } else {
                        statements.add(liftsThroughList(landing)
                            ? declare(type, local, lifted(elementsOf(type, landing), landing, 0,
                                CodeBlock.of("env.getArgument($S)", landing.path().getFirst()), new int[]{0}))
                            : argument(type, local, landing.path()));
                    }
                    arguments.add(CodeBlock.of("$L", local));
                }
                default -> throw new IllegalStateException("no fill " + fill.fill() + " at "
                    + row.coordinate() + " position " + fill.position());
            }
        }
        return new Result.Emitted(statements, arguments, helpers);
    }

    /**
     * Whether {@code landing} is read through a list: a step above the last whose value is a list,
     * each element of which the rest of the path is read from.
     */
    private static boolean liftsThroughList(FillQuery.Landing landing) {
        return landing.lists().stream().anyMatch(i -> i < landing.path().size() - 1);
    }

    /**
     * Whether {@code landing} is read through a list to something other than a scalar, which is not
     * read here: a node id, an enum or an input object under a list above the last step.
     */
    public static boolean unreadThroughList(FillQuery.Landing landing) {
        return liftsThroughList(landing) && (landing.nodeId() || !"SCALAR".equals(landing.typeKind()));
    }

    /**
     * The value at step {@code at} of a path read through lists, given as {@code value}: each list
     * above the last step streams its elements into the rest of the path, one more list deep, and an
     * input object steps into its field; {@code null} wherever a level is absent, at that depth.
     *
     * <pre>{@code
     * env.getArgument("input") instanceof Map<?, ?> map1
     *     ? (map1.get("items") instanceof List<?> list2
     *         ? list2.stream().map(elem3 -> elem3 instanceof Map<?, ?> map4 ? (Integer) map4.get("id") : null).toList()
     *         : null)
     *     : null
     * }</pre>
     */
    private static CodeBlock lifted(TypeName leaf, FillQuery.Landing landing, int at, CodeBlock value, int[] names) {
        var path = landing.path();
        if (at == path.size() - 1) {
            return CodeBlock.of("($T) $L", leaf, value);
        }
        if (landing.lists().contains(at)) {
            String list = "list" + ++names[0];
            String element = "elem" + ++names[0];
            return CodeBlock.of("($L instanceof $T<?> $L ? $L.stream().map($L -> $L).toList() : null)",
                value, LIST, list, list, element, step(leaf, landing, at, CodeBlock.of("$L", element), names));
        }
        return step(leaf, landing, at, value, names);
    }

    /** The input object {@code value} at step {@code at} into its field on the next step. */
    private static CodeBlock step(TypeName leaf, FillQuery.Landing landing, int at, CodeBlock value, int[] names) {
        String map = "map" + ++names[0];
        return CodeBlock.of("($L instanceof $T<?, ?> $L ? $L : null)", value, MAP, map,
            lifted(leaf, landing, at + 1, CodeBlock.of("$L.get($S)", map, landing.path().get(at + 1)), names));
    }

    /**
     * What the last step of a path read through lists casts to: the parameter's type with one list
     * taken off for each list above the last step, boxed.
     */
    private static TypeName elementsOf(TypeName type, FillQuery.Landing landing) {
        var leaf = type;
        long lifts = landing.lists().stream().filter(i -> i < landing.path().size() - 1).count();
        for (long i = 0; i < lifts; i++) {
            leaf = elementOf(leaf);
        }
        return leaf.box();
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

    /**
     * An enum argument into a Java enum. graphql-java hands an enum value over as its runtime value,
     * the string the schema gives it, so the string is read off the fetch and turned into the
     * parameter's constant of the same name, or into a list of them.
     */
    private static List<CodeBlock> enumValue(TypeName type, String local, List<String> path) {
        var wire = local + "Wire";
        if (type instanceof ParameterizedTypeName list && LIST.equals(list.rawType())) {
            var element = list.typeArguments().getFirst();
            return List.of(argument(ParameterizedTypeName.get(LIST, STRING), wire, path),
                CodeBlock.of("$T $L = $L == null ? null : $L.stream().map($T::valueOf).toList()",
                    type, local, wire, wire, element));
        }
        return List.of(argument(STRING, wire, path),
            CodeBlock.of("$T $L = $L == null ? null : $T.valueOf($L)", type, local, wire, type, wire));
    }

    /** An input object, or a list of them, made into the parameter's class by {@code helper}. */
    private static CodeBlock made(TypeName type, String local, String helper) {
        var wire = local + "Wire";
        var anyMap = ParameterizedTypeName.get(MAP, com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class),
            com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class));
        if (type instanceof ParameterizedTypeName list && LIST.equals(list.rawType())) {
            return CodeBlock.of("$T $L = $L instanceof $T<?> elements ? elements.stream()"
                    + ".map(e -> e instanceof $T m ? $L(m) : null).toList() : null",
                type, local, wire, LIST, anyMap, helper);
        }
        return CodeBlock.of("$T $L = $L instanceof $T m ? $L(m) : null", type, local, wire, anyMap, helper);
    }

    /** A type's element: the type a {@code List} holds, or the type itself. */
    private static TypeName elementOf(TypeName type) {
        return type instanceof ParameterizedTypeName list && LIST.equals(list.rawType())
            ? list.typeArguments().getFirst() : type;
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
