package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapListIf;

/**
 * Class that contains extended information about a mutation service.
 */
public class ServiceWrapper {
    private final boolean returnIsIterable, returnTypeInService;
    private final Class<?> returnType;
    private final Method method;
    private final int paramCount;
    private final String serviceName, packageName;
    private final TypeName returnTypeName;
    private final Set<Class<?>> internalClasses;

    public ServiceWrapper(CodeReference reference, int paramCount) {
        var references = GeneratorConfig.getExternalReferences();
        var service = references.getClassFrom(reference);
        method = references.getNullableMethodFrom(reference);

        this.paramCount = paramCount;
        returnIsIterable = method != null && method.getReturnType().getName().equals("java.util.List");
        serviceName = service.getSimpleName();
        packageName = service.getPackageName();

        returnType = method != null ? extractType(method.getGenericReturnType()) : null;
        if (returnType != null) {
            returnTypeInService = returnType.getEnclosingClass() == service;
            returnTypeName = getServiceReturnClassName(returnType, returnIsIterable);
        } else {
            returnTypeInService = false;
            returnTypeName = null;
        }
        internalClasses = Arrays.stream(service.getClasses()).collect(Collectors.toSet());
    }

    /**
     * @return The name for the result type for a query method call.
     */
    @NotNull
    public static TypeName getServiceReturnClassName(Class<?> serviceReturnType, boolean isIterable) {
        return wrapListIf(ClassName.get(serviceReturnType), isIterable);
    }

    /**
     * @return If the type is a parameterized type such as a List or Set, return the class of the parameter, otherwise return the class of the argument.
     */
    public static Class<?> extractType(Type type) {
        if (type == null) return null;
        return (Class<?>) (type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0] : type);
    }

    /**
     * @return Is the return type of this service method iterable?
     */
    public boolean returnIsIterable() {
        return returnIsIterable;
    }

    /**
     * @return Is the return type of this method located within this method's service class? Used for special return classes.
     */
    public boolean isReturnTypeInService() {
        return returnTypeInService;
    }

    /**
     * @return Number of parameters for this service method.
     */
    public int getParamCount() {
        return paramCount;
    }

    /**
     * @return The class of the return type for this service method.
     */
    public Class<?> getReturnType() {
        return returnType;
    }

    /**
     * @return The reflected method object for this service method.
     */
    public Method getMethod() {
        return method;
    }

    /**
     * @return The name of the class that the service method is located in.
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * @return The package of the class that the service method is located in.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * @return Javapoet classname of the return type.
     */
    public TypeName getReturnTypeName() {
        return returnTypeName;
    }

    /**
     * @return Set of classes that are contained within this service method's class.
     */
    public Set<Class<?>> getInternalClasses() {
        return internalClasses;
    }
}
