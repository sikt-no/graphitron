package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emits a service call's body from one row of {@link CallQuery} and the call's fills: the method the
 * frame calls, which fills the parameters, calls the method on its class or on an object made for
 * it, and hands the answer back as the frame takes it.
 *
 * <pre>{@code
 * static Map<Record1<Integer>, String> titleUppercase(Set<Record1<Integer>> frameKeys,
 *         DataFetchingEnvironment env, DSLContext dsl) throws Exception {
 *     List<Record1<Integer>> ordered = new ArrayList<>(frameKeys);
 *     List<Row1<Integer>> converted = ordered.stream().map(k -> k.valuesRow()).toList();
 *     Set<Row1<Integer>> keys = new LinkedHashSet<>(converted);
 *     Map<Row1<Integer>, String> answered = FilmService.titleLowercase(keys, dsl);
 *     Map<Record1<Integer>, String> out = new HashMap<>();
 *     for (int i = 0; i < ordered.size(); i++) {
 *         out.put(ordered.get(i), answered == null ? null : answered.get(converted.get(i)));
 *     }
 *     return out;
 * }
 * }</pre>
 *
 * <p>At a root the body answers what the method answers. Under an object the frame hands over the
 * batch's keys as records of the parent's key, and the method may take them so, as the key's rows, as
 * records of the parent's table, in a set or in a list in the batch's order; it answers a map from
 * the keys it was handed, or a list in their order, and the body reads each parent's value back off
 * it by its own key. The body throws what the method throws; the frame routes it.
 *
 * <p>Where the call hands back records of the table its target binds, the body lifts them: the
 * records go to the producer's lift, {@link LiftEmitter#lift}, which reads the target's rows for
 * them, and the body answers those rows. Under an object every parent's records are gathered in the
 * batch's order first, so one statement lifts the whole batch.
 */
public final class CallEmitter {

    private CallEmitter() {}

    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName MAP = ClassName.get("java.util", "Map");

    /**
     * The batch's keys as the frame hands them over: the record type of the parent's key, and the
     * parent's table as the generated code names it, which a method taking the table's records reads
     * the keys into.
     */
    public record FrameKey(TypeName keyType, ClassName tablesClass, String tableField) {}

    /**
     * The producer's lift: the class and name of the method {@link LiftEmitter#lift} rendered for
     * it, and whether it lifts a list of records, per parent under an object, or one.
     */
    public record Lift(ClassName host, String method, boolean list) {}

    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");

    /** What the emitter makes of a call: its body and the helpers its fills need, or why it cannot. */
    public sealed interface Result {
        record Emitted(MethodSpec body, List<MethodSpec> helpers) implements Result {
            public Emitted {
                helpers = List.copyOf(helpers);
            }
        }

        record NeedsBuilding(String reason) implements Result {}
    }

    /**
     * The body of {@code call}, named {@code name}, its parameters filled by {@code fills} as
     * {@code builds} says; {@code frameKey} is present under an object, {@code lift} where the
     * call's records are lifted onto its target's table.
     */
    public static Result body(CallQuery.Row call, FillQuery.Row fills, Map<Integer, BuildQuery.Result> builds,
                              FillEmitter.Runtime runtime, String name, Optional<FrameKey> frameKey,
                              Optional<Lift> lift) {
        var answer = SessionIdentityRows.typeOf(call.resultType());
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addException(Exception.class);
        Optional<TypeName> sourceType = call.source().map(s -> SessionIdentityRows.typeOf(s.parameterType()));
        Batch batch = null;
        if (frameKey.isPresent()) {
            if (sourceType.isEmpty()) {
                return new Result.NeedsBuilding(call.coordinate() + " runs under an object and takes no keys");
            }
            var planned = batch(frameKey.get(), sourceType.get(), answer, call.coordinate());
            if (planned instanceof String reason) {
                return new Result.NeedsBuilding(reason);
            }
            batch = (Batch) planned;
            method.addParameter(ParameterizedTypeName.get(SET, frameKey.get().keyType()), "frameKeys");
        }
        method.addParameter(ENV, "env").addParameter(DSL_CONTEXT, "dsl");
        var filled = FillEmitter.emit(fills, sourceType, runtime, builds);
        if (filled instanceof FillEmitter.Result.NeedsBuilding refused) {
            return new Result.NeedsBuilding("parameter " + refused.parameterName() + " of " + call.coordinate()
                + ": " + refused.reason());
        }
        var emitted = (FillEmitter.Result.Emitted) filled;
        if (batch != null) {
            batch.declareKeys(method, frameKey.get());
        }
        emitted.statements().forEach(method::addStatement);
        var target = target(call, method);
        var invocation = CodeBlock.of("$L.$L($L)", target, call.methodName(), CodeBlock.join(emitted.arguments(), ", "));
        if (batch == null && lift.isEmpty()) {
            method.returns(answer).addStatement("return $L", invocation);
        } else if (batch == null) {
            method.returns(lifted(lift.get()))
                .addStatement("$T answered = $L", answer, invocation)
                .addStatement("return $T.$L(env, dsl, answered)", lift.get().host(), lift.get().method());
        } else if (lift.isEmpty()) {
            batch.readBack(method, frameKey.get(), answer, invocation);
        } else {
            batch.liftBack(method, frameKey.get(), answer, invocation, lift.get());
        }
        return new Result.Emitted(method.build(), emitted.helpers());
    }

    /** What a lift answers for one record, or for one list of them: a row, or a list of rows. */
    private static TypeName lifted(Lift lift) {
        return lift.list() ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
    }

    /** The class a static method is called on, or the object an instance method runs on, made here. */
    private static CodeBlock target(CallQuery.Row call, MethodSpec.Builder method) {
        var holder = SessionIdentityRows.classOf(call.className());
        if (call.isStatic()) {
            return CodeBlock.of("$T", holder);
        }
        method.addStatement(call.holderTakesDsl() ? "$T holder = new $T(dsl)" : "$T holder = new $T()", holder, holder);
        return CodeBlock.of("holder");
    }

    /**
     * How a batch goes in and comes back: the collection the method takes the keys in, the element
     * each frame key is converted to, and whether the answer is a map by key or a list by position.
     */
    private record Batch(ClassName collection, TypeName element, CodeBlock conversion, boolean byKey,
                         TypeName value) {

        void declareKeys(MethodSpec.Builder method, FrameKey frame) {
            method.addStatement("$T ordered = new $T<>(frameKeys)",
                ParameterizedTypeName.get(LIST, frame.keyType()), ClassName.get("java.util", "ArrayList"));
            method.addStatement("$T converted = ordered.stream().map(k -> $L).toList()",
                ParameterizedTypeName.get(LIST, element), conversion);
            var keysType = ParameterizedTypeName.get(collection, element);
            if (SET.equals(collection)) {
                method.addStatement("$T keys = new $T<>(converted)", keysType, ClassName.get("java.util", "LinkedHashSet"));
            } else {
                method.addStatement("$T keys = converted", keysType);
            }
        }

        void readBack(MethodSpec.Builder method, FrameKey frame, TypeName answer, CodeBlock invocation) {
            var out = ParameterizedTypeName.get(MAP, frame.keyType(), value);
            method.returns(out)
                .addStatement("$T answered = $L", answer, invocation)
                .addStatement("$T out = new $T<>()", out, ClassName.get("java.util", "HashMap"))
                .beginControlFlow("for (int i = 0; i < ordered.size(); i++)");
            if (byKey) {
                method.addStatement("out.put(ordered.get(i), answered == null ? null : answered.get(converted.get(i)))");
            } else {
                method.addStatement("out.put(ordered.get(i), answered == null || i >= answered.size() ? null : answered.get(i))");
            }
            method.endControlFlow().addStatement("return out");
        }

        /**
         * As {@link #readBack}, but every parent's records are gathered in the batch's order and
         * lifted in one call before each parent's rows are handed back under its key.
         */
        void liftBack(MethodSpec.Builder method, FrameKey frame, TypeName answer, CodeBlock invocation, Lift lift) {
            var row = lifted(lift);
            var out = ParameterizedTypeName.get(MAP, frame.keyType(), row);
            method.returns(out)
                .addStatement("$T answered = $L", answer, invocation)
                .addStatement("$T inOrder = new $T<>(ordered.size())", ParameterizedTypeName.get(LIST, value),
                    ClassName.get("java.util", "ArrayList"))
                .beginControlFlow("for (int i = 0; i < ordered.size(); i++)");
            if (byKey) {
                method.addStatement("inOrder.add(answered == null ? null : answered.get(converted.get(i)))");
            } else {
                method.addStatement("inOrder.add(answered == null || i >= answered.size() ? null : answered.get(i))");
            }
            method.endControlFlow()
                .addStatement("$T rows = $T.$L(env, dsl, inOrder)", ParameterizedTypeName.get(LIST, row), lift.host(),
                    lift.method())
                .addStatement("$T out = new $T<>()", out, ClassName.get("java.util", "HashMap"))
                .beginControlFlow("for (int i = 0; i < ordered.size(); i++)")
                .addStatement("out.put(ordered.get(i), rows.get(i))")
                .endControlFlow()
                .addStatement("return out");
        }
    }

    /** The batch for a method taking {@code source} and answering {@code answer}, or why there is none. */
    private static Object batch(FrameKey frame, TypeName source, TypeName answer, String coordinate) {
        if (!(source instanceof ParameterizedTypeName taken)
            || !(SET.equals(taken.rawType()) || LIST.equals(taken.rawType()))) {
            return coordinate + " takes its keys as " + source + ", neither a Set nor a List of them";
        }
        var element = taken.typeArguments().getFirst();
        CodeBlock conversion;
        if (element.equals(frame.keyType())) {
            conversion = CodeBlock.of("k");
        } else if (element instanceof ParameterizedTypeName row && row.rawType().packageName().equals("org.jooq")
            && row.rawType().simpleName().startsWith("Row")) {
            conversion = CodeBlock.of("k.valuesRow()");
        } else {
            conversion = CodeBlock.of("k.into($T.$L)", frame.tablesClass(), frame.tableField());
        }
        if (!(answer instanceof ParameterizedTypeName answered)) {
            return coordinate + " answers " + answer + ", neither a Map by key nor a List in the keys' order";
        }
        if (MAP.equals(answered.rawType())) {
            return new Batch(taken.rawType(), element, conversion, true, answered.typeArguments().get(1));
        }
        if (LIST.equals(answered.rawType())) {
            return new Batch(taken.rawType(), element, conversion, false, answered.typeArguments().getFirst());
        }
        return coordinate + " answers " + answer + ", neither a Map by key nor a List in the keys' order";
    }
}
