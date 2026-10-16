package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A structured description of one {@code @service} method invocation.
 * Produced by {@code ServiceMethodCallWalker} from the SDL definition plus codegen-classloader
 * reflection; consumed by {@code ServiceMethodCallEmitter} to emit the fetcher lambda body's
 * statement list.
 *
 * <p>{@link Static} carries one round ({@code methodArgs}). {@link Instance} carries two
 * ({@code ctorArgs} then {@code methodArgs}); field order mirrors evaluation order. The
 * walker enforces two cross-round invariants: {@link MappingEntry.FromArg} is invalid in
 * {@code ctorArgs}, and two {@link MappingEntry.FromDsl} entries cannot coexist in the same
 * round.
 *
 * <p>{@code javaReturnType} is the structured Java return type used to type the emitter's
 * {@code result} local. The GraphQL-side classification of the field's return lives on the
 * permit's existing {@code ReturnTypeRef} component; the two classifications are orthogonal.
 * {@code returnContainer} is the container that return type is, read once where the return is
 * reflected so no consumer inspects a type name.
 *
 * <p>{@code validatedArguments} names each field argument the call reads, once, in the order the
 * method's parameters first reach it, classified by the shape the pre-execution validation pass
 * materialises it in. It is a fact about the arguments, not about the parameter bindings: two
 * parameters binding into one argument yield one target.
 */
public sealed interface ServiceMethodCall permits ServiceMethodCall.Static, ServiceMethodCall.Instance {

    String fqClassName();
    String methodName();
    List<MappingEntry> methodArgs();
    TypeName javaReturnType();
    ReturnContainer returnContainer();
    List<ValidatedArgument> validatedArguments();

    record Static(
        String fqClassName,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer,
        List<ValidatedArgument> validatedArguments
    ) implements ServiceMethodCall {
        public Static {
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
            validatedArguments = List.copyOf(validatedArguments);
        }
    }

    record Instance(
        String fqClassName,
        List<MappingEntry> ctorArgs,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer,
        List<ValidatedArgument> validatedArguments
    ) implements ServiceMethodCall {
        public Instance {
            ctorArgs = List.copyOf(ctorArgs);
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
            validatedArguments = List.copyOf(validatedArguments);
        }
    }

    /**
     * One field argument the call reads, as the pre-execution validation pass materialises it.
     * The arm is decided from the argument's declared SDL type: whether it unwraps to an input
     * object, and under how many list wrappers. An input-object arm names the SDL input type; the
     * generated class that type renders to is emit vocabulary.
     */
    sealed interface ValidatedArgument {

        /** The argument's name on the field. */
        String argName();

        /** A scalar or enum argument, at any list depth; validated as its raw value. */
        record Raw(String argName) implements ValidatedArgument {
            public Raw {
                Objects.requireNonNull(argName, "argName");
            }
        }

        /** An input-object argument under no list wrapper. */
        record InputObject(String argName, String inputTypeName) implements ValidatedArgument {
            public InputObject {
                Objects.requireNonNull(argName, "argName");
                Objects.requireNonNull(inputTypeName, "inputTypeName");
            }
        }

        /** An input-object argument under exactly one list wrapper; validated element by element. */
        record InputObjectList(String argName, String inputTypeName) implements ValidatedArgument {
            public InputObjectList {
                Objects.requireNonNull(argName, "argName");
                Objects.requireNonNull(inputTypeName, "inputTypeName");
            }
        }

        /**
         * An input-object argument under two or more list wrappers. The validation pass cannot
         * materialise it, so classification rejects it on a field whose error channel carries a
         * {@code VALIDATION} handler.
         */
        record InputObjectNestedList(String argName, String inputTypeName) implements ValidatedArgument {
            public InputObjectNestedList {
                Objects.requireNonNull(argName, "argName");
                Objects.requireNonNull(inputTypeName, "inputTypeName");
            }
        }
    }

    /**
     * The container the service method's Java return type is: what an empty answer to the call
     * would be, where graphitron can build one.
     */
    sealed interface ReturnContainer {

        /** A {@code java.util.List} or {@code java.util.Collection}. */
        record ListLike() implements ReturnContainer {
            public static final ListLike INSTANCE = new ListLike();
        }

        /** A {@code java.util.Set}. */
        record SetLike() implements ReturnContainer {
            public static final SetLike INSTANCE = new SetLike();
        }

        /** A jOOQ {@code Result} over {@code table}'s record class. */
        record JooqResult(TableRef table) implements ReturnContainer {
            public JooqResult {
                Objects.requireNonNull(table, "table");
            }
        }

        /**
         * Anything else: a payload class, a scalar, a single record, an {@code Optional}, a
         * {@code CompletableFuture}, or a {@code Result} over a record class the catalog does not
         * name.
         */
        record Other() implements ReturnContainer {
            public static final Other INSTANCE = new Other();
        }

        /**
         * Classifies a reflected return type. {@code recordTable} resolves a jOOQ record class to
         * its catalog table, for the {@link JooqResult} arm.
         */
        static ReturnContainer of(TypeName javaReturnType,
                                  Function<ClassName, Optional<TableRef>> recordTable) {
            if (!(javaReturnType instanceof ParameterizedTypeName ptn) || ptn.typeArguments().size() != 1) {
                return Other.INSTANCE;
            }
            return switch (ptn.rawType().canonicalName()) {
                case "java.util.List", "java.util.Collection" -> ListLike.INSTANCE;
                case "java.util.Set" -> SetLike.INSTANCE;
                case "org.jooq.Result" -> ptn.typeArguments().get(0) instanceof ClassName record
                    ? recordTable.apply(record).<ReturnContainer>map(JooqResult::new).orElse(Other.INSTANCE)
                    : Other.INSTANCE;
                default -> Other.INSTANCE;
            };
        }
    }
}
