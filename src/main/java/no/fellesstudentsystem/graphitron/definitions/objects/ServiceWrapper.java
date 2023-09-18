package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;

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

    public ServiceWrapper(String fieldName, int paramCount, Class<?> service) {
        this.paramCount = paramCount;
        var methodOptional = getServiceReturnMethod(fieldName, service);
        var wrapperName = methodOptional.map(Method::getReturnType).map(Class::getName).orElse("");
        returnIsIterable = wrapperName.equals("java.util.List");
        serviceName = service.getSimpleName();
        packageName = service.getPackageName();

        method = methodOptional.orElse(null);
        returnType = method != null ? extractType(method.getGenericReturnType()) : null;
        if (returnType != null) {
            returnTypeInService = returnType.getEnclosingClass() == service;
            returnTypeName = getServiceReturnClassName(returnType.getName(), returnIsIterable);
        } else {
            returnTypeInService = false;
            returnTypeName = null;
        }
        internalClasses = Arrays.stream(service.getClasses()).collect(Collectors.toSet());
    }

    /**
     * @return The method object of the type returned by the service.
     */
    @NotNull
    private Optional<Method> getServiceReturnMethod(String targetName, Class<?> service) {
        return Stream
                .of(service.getMethods())
                .filter(m -> m.getName().equals(targetName) && m.getParameterTypes().length == paramCount)
                .findFirst();
    }

    /**
     * @return The name for the result type for a query method call.
     */
    @NotNull
    public static TypeName getServiceReturnClassName(String serviceReturnTypeName, boolean isIterable) {
        var serviceReturnClassName = ClassName.get("", serviceReturnTypeName.replace("$", "."));
        return wrapListIf(serviceReturnClassName, isIterable);
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
