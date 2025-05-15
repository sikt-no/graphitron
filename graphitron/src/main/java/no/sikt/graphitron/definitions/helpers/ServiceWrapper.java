package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;

import java.lang.reflect.Method;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.RECORD_NAME_SUFFIX;
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
        method = references.getMethodsFrom(reference).stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                "Service reference " +
                        GeneratorConfig.getExternalReferences().getClassFrom(reference).getName() +
                        " does not contain method named " + reference.getMethodName()
        ));
        serviceClassName = ClassName.get(service);

        if (response != null && response.hasJavaRecordReference()) {
            returnType = response.getRecordReference();
        } else {
            returnType = method != null ? TypeExtractor.extractType(method.getGenericReturnType()) : null;
        }
        methodName = uncapitalize(method != null ? method.getName() : field.getName());
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
