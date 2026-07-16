package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

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
 */
public sealed interface ServiceMethodCall permits ServiceMethodCall.Static, ServiceMethodCall.Instance {

    String fqClassName();
    String methodName();
    List<MappingEntry> methodArgs();
    TypeName javaReturnType();

    record Static(
        String fqClassName,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType
    ) implements ServiceMethodCall {
        public Static { methodArgs = List.copyOf(methodArgs); }
    }

    record Instance(
        String fqClassName,
        List<MappingEntry> ctorArgs,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType
    ) implements ServiceMethodCall {
        public Instance {
            ctorArgs = List.copyOf(ctorArgs);
            methodArgs = List.copyOf(methodArgs);
        }
    }
}
