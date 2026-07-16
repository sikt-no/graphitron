package no.sikt.graphitron.rewrite.walker.internal;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.rewrite.ClassAccessorResolver;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.walker.ReflectTypeResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-({@code @error} type, handler, SDL field) source-class accessor-coverage check.
 * Absorbed from {@code FieldBuilder.checkErrorTypeSourceAccessors}; produces typed
 * {@link ErrorChannelWalkerError.HandlerSourceAccessorMissing} arms instead of the legacy reject
 * string so the {@code ErrorChannelWalker} surfaces them through {@code WalkerResult.Err}.
 *
 * <p>For each mapped {@code @error} type, each declared SDL field (except {@code path} /
 * {@code message}, which the rewrite populates via synthesised per-type DataFetchers) must resolve
 * to a {@code PropertyDataFetcher}-visible accessor on every handler's source class. The source
 * class per handler kind: the named exception class for {@code GENERIC}, {@code SQLException} for
 * {@code DATABASE} (SQL-state / vendor-code), {@code GraphQLError} for {@code VALIDATION}.
 *
 * <p>Unlike the legacy check this collects every failure rather than returning the first, since
 * {@code WalkerResult.Err} carries a list; the accept/reject decision is unchanged.
 */
public final class HandlerAccessorCheck {

    private HandlerAccessorCheck() {}

    public static List<ErrorChannelWalkerError.HandlerSourceAccessorMissing> check(
            String outcomeTypeName,
            List<ErrorType> mappedErrorTypes,
            GraphQLSchema schema,
            ClassLoader codegenLoader,
            ReflectTypeResolver reflectTypeResolver) {
        var failures = new ArrayList<ErrorChannelWalkerError.HandlerSourceAccessorMissing>();
        for (ErrorType errorType : mappedErrorTypes) {
            var sdlType = schema.getType(errorType.name());
            if (!(sdlType instanceof GraphQLObjectType errorObj)) {
                continue;
            }
            var extraFields = errorObj.getFieldDefinitions().stream()
                .filter(f -> !"path".equals(f.getName()) && !"message".equals(f.getName()))
                .toList();
            if (extraFields.isEmpty()) {
                continue;
            }
            for (var handler : errorType.handlers()) {
                Class<?> sourceClass = resolveHandlerSourceClass(handler, codegenLoader);
                if (sourceClass == null) {
                    continue;
                }
                for (var sdlField : extraFields) {
                    var expectedReturn = reflectTypeResolver.resolve(sdlField.getType());
                    String accessorBase = errorType.accessorBaseFor(sdlField.getName());
                    var resolution = ClassAccessorResolver.resolve(
                        sourceClass,
                        accessorBase,
                        expectedReturn,
                        new ClassAccessorResolver.PerArgument(List.of()),
                        ClassAccessorResolver.CandidateOrder.POJO_FIRST);
                    if (resolution instanceof AccessorResolution.Rejected) {
                        failures.add(new ErrorChannelWalkerError.HandlerSourceAccessorMissing(
                            outcomeTypeName,
                            errorType.name(),
                            sourceClass.getName(),
                            sdlField.getName(),
                            accessorBase,
                            availableAccessors(sourceClass)));
                    }
                }
            }
        }
        return failures;
    }

    /**
     * The runtime source class for a handler variant, or {@code null} when it cannot be loaded on
     * the codegen classloader (a {@code GENERIC} className would already have been rejected at
     * parse time, so a miss here means a broken classifier classpath).
     */
    private static Class<?> resolveHandlerSourceClass(ErrorType.Handler handler, ClassLoader codegenLoader) {
        String fqn = switch (handler) {
            case ErrorType.ExceptionHandler eh -> eh.exceptionClassName();
            case ErrorType.SqlStateHandler ignored -> "java.sql.SQLException";
            case ErrorType.VendorCodeHandler ignored -> "java.sql.SQLException";
            case ErrorType.ValidationHandler ignored -> "graphql.GraphQLError";
        };
        try {
            return Class.forName(fqn, false, codegenLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Public zero-arg, non-{@code void} methods declared below {@code Object} on the source class,
     * in sorted order. Populates {@link ErrorChannelWalkerError.HandlerSourceAccessorMissing#available()}
     * so the diagnostic can suggest what the source class does expose.
     */
    private static List<String> availableAccessors(Class<?> sourceClass) {
        return java.util.Arrays.stream(sourceClass.getMethods())
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> m.getReturnType() != void.class)
            .filter(m -> m.getDeclaringClass() != Object.class)
            .map(Method::getName)
            .distinct()
            .sorted()
            .toList();
    }
}
