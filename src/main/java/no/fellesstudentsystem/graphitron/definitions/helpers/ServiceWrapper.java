package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.RECORD_NAME_SUFFIX;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class that contains extended information about a mutation service.
 */
public class ServiceWrapper {
    private final Class<?> service, returnType;
    private final Method method;
    private final String methodName;
    private final ClassName serviceClassName;

    public ServiceWrapper(GenerationField field, ObjectDefinition response) {
        var reference = field.getServiceReference();
        var references = GeneratorConfig.getExternalReferences();
        service = references.getClassFrom(reference);
        method = references.getNullableMethodFrom(reference);
        serviceClassName = ClassName.get(service);

        if (response != null && response.hasJavaRecordReference()) {
            returnType = response.getRecordReference();
        } else {
            returnType = method != null ? extractType(method.getGenericReturnType()) : null;
        }
        methodName = uncapitalize(method != null ? method.getName() : field.getName());
    }

    /**
     * @return If the type is a parameterized type such as a List or Set, return the class of the parameter, otherwise return the class of the argument.
     */
    public static Class<?> extractType(Type type) {
        if (type == null) return null;
        return (Class<?>) (type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0] : type);
    }

    /**
     * @return The class of the return type for this service method unpacked.
     */
    public boolean inferIsReturnTypeRecord() {
        return returnType.getName().endsWith(RECORD_NAME_SUFFIX);
    }

    /**
     * @return The class of the return type for this service method.
     */
    public TypeName getGenericReturnType() {
        return ClassName.get(method.getGenericReturnType());
    }

    /**
     * @return The reflected method name for this service method.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * @return Reference of the class that the service method is located in.
     */
    public Class<?> getService() {
        return service;
    }

    /**
     * @return Javapoet classname of the service.
     */
    public ClassName getServiceClassName() {
        return serviceClassName;
    }

}
