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
 */
public sealed interface ServiceMethodCall permits ServiceMethodCall.Static, ServiceMethodCall.Instance {

    String fqClassName();
    String methodName();
    List<MappingEntry> methodArgs();
    TypeName javaReturnType();
    ReturnContainer returnContainer();

    record Static(
        String fqClassName,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer
    ) implements ServiceMethodCall {
        public Static {
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
        }
    }

    record Instance(
        String fqClassName,
        List<MappingEntry> ctorArgs,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer
    ) implements ServiceMethodCall {
        public Instance {
            ctorArgs = List.copyOf(ctorArgs);
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
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
