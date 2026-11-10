package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.ParameterizedTypeName;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Emits a validating call's validation pass from what its fills built: a value built into a class
 * from an input object is validated whole, and one built into a table's jOOQ record one property at
 * a time, for each column it wrote that its getter offers; each element of a list on its own, before
 * the call. A violation becomes a GraphQL error at the argument, the element's index for a list, and
 * the SDL input fields its Java members or columns were bound from; any violation answers the call's
 * outcome with the errors in place of calling it.
 *
 * <pre>{@code
 * Validator validator = graphitronContext(env).getValidator(env);
 * List<Object> violations = new ArrayList<>();
 * if (drafts != null) {
 *     for (int i = 0; i < drafts.size(); i++) {
 *         FilmDraft element = drafts.get(i);
 *         if (element != null) {
 *             for (ConstraintViolation<?> violation : validator.validate(element)) {
 *                 violations.add(ConstraintViolations.toGraphQLError(violation, env, List.of("in", i), PATHS));
 *             }
 *         }
 *     }
 * }
 * if (!violations.isEmpty()) {
 *     return new Outcome.ErrorList<>(violations);
 * }
 * }</pre>
 */
public final class ValidationEmitter {

    private ValidationEmitter() {}

    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName VALIDATOR = ClassName.get("jakarta.validation", "Validator");
    private static final ClassName VIOLATION = ClassName.get("jakarta.validation", "ConstraintViolation");

    /**
     * The generated runtime the pass reads through: the outcome its errors answer, the class turning
     * a violation into a GraphQL error with its member-to-input-field names, and the validator of
     * the request, spelled with the environment's variable.
     */
    public record Runtime(ClassName outcome, ClassName constraintViolations, Function<String, CodeBlock> validator) {
        public Runtime {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(constraintViolations, "constraintViolations");
            Objects.requireNonNull(validator, "validator");
        }

        /** The names a built class's members bind to, one constant per class validated. */
        ClassName paths() {
            return constraintViolations.nestedClass("Paths");
        }
    }

    /** The pass's statements, ending in the early answer, and the constants they name. */
    public record Pass(CodeBlock statements, List<FieldSpec> constants) {
        public Pass {
            constants = List.copyOf(constants);
        }
    }

    /**
     * The pass over {@code built} in a body named {@code body}, empty where nothing was built; the
     * constants are named after the body so two bodies in one class never share a name.
     */
    public static Pass pass(List<FillEmitter.Result.Built> values, String body, Runtime runtime) {
        var constants = new ArrayList<FieldSpec>();
        // A record is checked one property at a time, each column it writes that has a getter; a
        // record with none has nothing to check.
        var built = values.stream()
            .filter(v -> !(v.into() instanceof FillEmitter.Result.Into.ARecord record)
                || record.build().columns().stream().anyMatch(c -> c.property().isPresent()))
            .toList();
        if (built.isEmpty()) {
            // Nothing built, nothing to validate: not even the request's validator is asked for.
            return new Pass(CodeBlock.of(""), constants);
        }
        var b = CodeBlock.builder();
        b.addStatement("$T validator = $L", VALIDATOR, runtime.validator().apply("env"));
        b.addStatement("$T violations = new $T<>()", ParameterizedTypeName.get(LIST, ClassName.OBJECT), ARRAY_LIST);
        for (var value : built) {
            String constant = "VALIDATION_PATHS_" + body.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT)
                + "_" + constants.size();
            constants.add(FieldSpec.builder(runtime.paths(), constant, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(switch (value.into()) {
                    case FillEmitter.Result.Into.AClass c -> paths(c.build(), runtime);
                    case FillEmitter.Result.Into.ARecord r -> paths(r.build(), runtime);
                })
                .build());
            var argument = CodeBlock.builder().add("$T.of(", LIST);
            for (int i = 0; i < value.argumentPath().size(); i++) {
                argument.add(i == 0 ? "$S" : ", $S", value.argumentPath().get(i));
            }
            String elementClass = switch (value.into()) {
                case FillEmitter.Result.Into.AClass c -> c.build().className();
                case FillEmitter.Result.Into.ARecord r -> r.build().recordClass();
            };
            b.beginControlFlow("if ($L != null)", value.local());
            if (value.list()) {
                b.beginControlFlow("for (int i = 0; i < $L.size(); i++)", value.local())
                    .addStatement("$T element = $L.get(i)", ClassName.bestGuess(elementClass.replace('$', '.')),
                        value.local())
                    .beginControlFlow("if (element != null)")
                    .add(check(value.into(), "element", argument.add(", i)").build(), constant, runtime))
                    .endControlFlow()
                    .endControlFlow();
            } else {
                b.add(check(value.into(), value.local(), argument.add(")").build(), constant, runtime));
            }
            b.endControlFlow();
        }
        b.beginControlFlow("if (!violations.isEmpty())")
            .addStatement("return new $T.ErrorList<>(violations)", runtime.outcome())
            .endControlFlow();
        return new Pass(b.build(), constants);
    }

    /**
     * {@code target} checked as what it was built into says: a class whole, a record one property at
     * a time, each guarded on the record writing that column, so a field the client did not send is
     * not checked.
     */
    private static CodeBlock check(FillEmitter.Result.Into into, String target, CodeBlock argument,
                                   String constant, Runtime runtime) {
        return switch (into) {
            case FillEmitter.Result.Into.AClass ignored ->
                validate(CodeBlock.of("validator.validate($L)", target), argument, constant, runtime);
            case FillEmitter.Result.Into.ARecord record -> {
                var table = SessionIdentityRows.classOf(record.build().tablesClass());
                var b = CodeBlock.builder();
                for (var column : record.build().columns()) {
                    if (column.property().isEmpty()) {
                        continue;
                    }
                    b.beginControlFlow("if ($L.touched($T.$L.$L))", target, table, record.build().tableField(),
                            column.columnJavaName())
                        .add(validate(CodeBlock.of("validator.validateProperty($L, $S)", target,
                            column.property().get()), argument, constant, runtime))
                        .endControlFlow();
                }
                yield b.build();
            }
        };
    }

    /** Each violation {@code violationsOf} yields into the errors, under {@code argument}, named by {@code constant}. */
    private static CodeBlock validate(CodeBlock violationsOf, CodeBlock argument, String constant, Runtime runtime) {
        return CodeBlock.builder()
            .beginControlFlow("for ($T<?> violation : $L)", VIOLATION, violationsOf)
            .addStatement("violations.add($T.toGraphQLError(violation, env, $L, $L))",
                runtime.constraintViolations(), argument, constant)
            .endControlFlow()
            .build();
    }

    /**
     * How a record's properties name the input fields that write them: each column with a getter by
     * the first field written for it, nothing below it.
     */
    static CodeBlock paths(BuildQuery.RecordBuild build, Runtime runtime) {
        var b = CodeBlock.builder().add("new $T()", runtime.paths());
        for (var column : build.columns()) {
            if (column.property().isEmpty()) {
                continue;
            }
            var sdl = CodeBlock.builder().add("$T.of(", LIST);
            var path = column.paths().getFirst();
            for (int i = 0; i < path.size(); i++) {
                sdl.add(i == 0 ? "$S" : ", $S", path.get(i));
            }
            b.add(".bind($S, $L, null)", column.property().get(), sdl.add(")").build());
        }
        return b.build();
    }

    /**
     * How {@code build}'s members name the input fields they were bound from: each member by the
     * whole path read from the input object, a grouping's names first, and a member built into a
     * class itself, or a list of them, by that class's names below it.
     */
    static CodeBlock paths(BuildQuery.Build build, Runtime runtime) {
        var b = CodeBlock.builder().add("new $T()", runtime.paths());
        for (var member : build.members()) {
            var sdl = CodeBlock.builder().add("$T.of(", LIST);
            for (int i = 0; i < member.path().size(); i++) {
                sdl.add(i == 0 ? "$S" : ", $S", member.path().get(i));
            }
            var child = member.nested().map(nested -> paths(nested, runtime)).orElse(CodeBlock.of("null"));
            b.add(".bind($S, $L, $L)", member.slot().slotName(), sdl.add(")").build(), child);
        }
        return b.build();
    }
}
