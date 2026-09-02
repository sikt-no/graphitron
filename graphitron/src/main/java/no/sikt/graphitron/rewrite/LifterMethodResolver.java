package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.Rejection;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The reflection preamble every {@code @sourceRow} site shares: load the declared class, find the
 * one static method the directive names, and check that it takes the parent. What the method
 * <em>returns</em> is site-derived and stays with each caller, because the two sites want different
 * shapes ({@link SourceRowDirectiveResolver} a {@code RowN} tuple matching the derived column
 * tuple, {@link FieldBuilder}'s batched child {@code @service} route the {@code Sources} element
 * record).
 *
 * <p>Single-sourcing the preamble is what keeps the rejection vocabulary identical across the two
 * sites: the same authored mistake (a misspelled method name, a lifter that takes the wrong type)
 * produces the same message and the same did-you-mean candidates wherever the directive sits, so
 * the LSP renders one answer rather than two.
 *
 * <p>Raw {@link Method} handles do not leave the builder boundary: the {@link Resolution.Ok} arm
 * carries one so the callers can read the return shape, and each caller resolves it into a model
 * reference ({@code LifterRef}, {@code StaticProducerRef}) before anything downstream sees it.
 */
final class LifterMethodResolver {

    private LifterMethodResolver() {}

    /**
     * Outcome of {@link #resolve}. {@link Ok} carries both the loaded classes and the resolved
     * method, since the callers need the lifter class to mint a {@code ClassName} and the parent
     * class for their own checks.
     */
    sealed interface Resolution {
        record Ok(Class<?> lifterClass, Class<?> parentClass, Method method) implements Resolution {}
        record Rejected(Rejection rejection) implements Resolution {}
    }

    /**
     * Resolves the static method {@code methodName} on {@code lifterClassName} as a lifter over
     * {@code parentFqClassName}.
     *
     * @param site the message prefix naming the coordinate, e.g.
     *             {@code "@sourceRow on 'Parent.field'"}; every rejection below appends to it.
     */
    static Resolution resolve(ClassLoader loader, String site, String lifterClassName,
            String methodName, String parentFqClassName) {
        Class<?> lifterClass;
        try {
            // nameability: exempt (author-written @sourceRow className is gated at SourceRowDirectiveResolver before this resolve)
            lifterClass = Class.forName(lifterClassName, false, loader);
        } catch (ClassNotFoundException e) {
            return rejected(site + ": lifter class '" + lifterClassName + "' could not be loaded");
        }

        Class<?> parentClass;
        try {
            // nameability: exempt (parent backing class derived from the model, not author text)
            parentClass = Class.forName(parentFqClassName, false, loader);
        } catch (ClassNotFoundException e) {
            return rejected(site + ": parent backing class '" + parentFqClassName
                + "' could not be loaded");
        }

        List<Method> namedMethods = new ArrayList<>();
        for (Method m : lifterClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) {
                namedMethods.add(m);
            }
        }
        if (namedMethods.isEmpty()) {
            List<String> candidates = new ArrayList<>();
            for (Method m : lifterClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    candidates.add(m.getName());
                }
            }
            return new Resolution.Rejected(Rejection.unknownLifterMethod(
                site + ": no static method named '" + methodName + "' on class '"
                + lifterClassName + "'",
                methodName, candidates));
        }
        if (namedMethods.size() > 1) {
            return rejected(site + ": multiple static methods named '" + methodName
                + "' on class '" + lifterClassName
                + "'; the lifter must be uniquely identifiable by name");
        }
        Method method = namedMethods.getFirst();

        if (method.getParameterCount() != 1) {
            return rejected(site + ": lifter method '" + methodName
                + "' must take exactly one parameter; got " + method.getParameterCount());
        }
        Class<?> param = method.getParameterTypes()[0];
        if (!param.isAssignableFrom(parentClass)) {
            return rejected(site + ": lifter method '" + methodName + "' parameter type '"
                + param.getName() + "' is not assignable from the parent's backing class '"
                + parentFqClassName + "'");
        }
        return new Resolution.Ok(lifterClass, parentClass, method);
    }

    private static Resolution rejected(String message) {
        return new Resolution.Rejected(Rejection.structural(message));
    }
}
