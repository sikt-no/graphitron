package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.ClassName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Class that contains extended information about a mutation service.
 */
public class ServiceWrapper {
    private final boolean returnsJavaRecord;
    private final Class<?> service, returnType;
    private final Method method;
    private final ClassName serviceClassName;

    public ServiceWrapper(GenerationField field, ProcessedSchema processedSchema) {
        var reference = field.getServiceReference();
        var references = GeneratorConfig.getExternalReferences();
        service = references.getClassFrom(reference);
        method = references.getNullableMethodFrom(reference);
        serviceClassName = ClassName.get(service);

        var response = processedSchema.getObject(field);
        returnsJavaRecord = response != null && response.hasJavaRecordReference();
        if (returnsJavaRecord) {
            returnType = response.getRecordReference();
        } else {
            returnType = method != null ? extractType(method.getGenericReturnType()) : null;
        }
    }

    /**
     * @return If the type is a parameterized type such as a List or Set, return the class of the parameter, otherwise return the class of the argument.
     */
    public static Class<?> extractType(Type type) {
        if (type == null) return null;
        return (Class<?>) (type instanceof ParameterizedType ? ((ParameterizedType) type).getActualTypeArguments()[0] : type);
    }

    /**
     * @return Is the return type of this method located within this method's service class? Used for special return classes.
     */
    public boolean returnsJavaRecord() {
        return returnsJavaRecord;
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
