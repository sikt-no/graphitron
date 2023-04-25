package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
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

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.LIST;

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
        return isIterable
                ? ParameterizedTypeName.get(LIST.className, serviceReturnClassName)
                : serviceReturnClassName;
    }

    public static Class<?> extractType(Type type) {
        if (type == null) return null;
        return (Class<?>) (type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0] : type);
    }

    public boolean returnIsIterable() {
        return returnIsIterable;
    }

    public boolean isReturnTypeInService() {
        return returnTypeInService;
    }

    public int getParamCount() {
        return paramCount;
    }

    public Class<?> getReturnType() {
        return returnType;
    }

    public Method getMethod() {
        return method;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPackageName() {
        return packageName;
    }

    public TypeName getReturnTypeName() {
        return returnTypeName;
    }

    public Set<Class<?>> getInternalClasses() {
        return internalClasses;
    }
}
