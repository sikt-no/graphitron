package no.sikt.graphitron.definitions.helpers;

import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.mappings.JavaPoetClassName.OBJECT;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentStringList;
import static no.sikt.graphql.directives.GenerationDirective.SERVICE;
import static no.sikt.graphql.directives.GenerationDirectiveParam.CONTEXT_ARGUMENTS;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * Class that contains extended information about a service.
 */
public class ServiceWrapper {
    private final Method method;
    private final String methodName;
    private final ClassName serviceClassName;
    private final CodeReference reference;
    private final Map<String, TypeName> contextFields;

    public <T extends NamedNode<T> & DirectivesContainer<T>> ServiceWrapper(T field) {
        if (!field.hasDirective(SERVICE.getName())) {
            method = null;
            methodName = null;
            serviceClassName = null;
            reference = null;
            contextFields = Map.of();
            return;
        }

        reference = new CodeReference(field, SERVICE, GenerationDirectiveParam.SERVICE, field.getName());
        var references = GeneratorConfig.getExternalReferences();
        var service = references.getClassFrom(reference);
        method = references.getMethodsFrom(reference).stream().findFirst().orElse(null);
        serviceClassName = service != null ? ClassName.get(service) : null;
        methodName = uncapitalize(method != null ? method.getName() : field.getName());

        // This matches parameter types to the names given in the schema. This is a best-guess effort which assumes these context parameters are at the end of the method call.
        var contextFields = getOptionalDirectiveArgumentStringList(field, SERVICE, CONTEXT_ARGUMENTS);
        var allParams = (method != null ? List.of(method.getParameters()) : List.<Parameter>of());
        var paramTypes = allParams
                .subList(Math.max(allParams.size() - contextFields.size(), 0), allParams.size())
                .stream()
                .map(Parameter::getType)
                .toList();
        var contextMap = new LinkedHashMap<String, TypeName>();
        for (int i = 0; i < contextFields.size(); i++) {
            contextMap.put(contextFields.get(i), i < paramTypes.size() ? TypeName.get(paramTypes.get(i)) : OBJECT.className);
        }
        this.contextFields = contextMap;
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
     * @return Javapoet classname of the service.
     */
    public ClassName getServiceClassName() {
        return serviceClassName;
    }

    /**
     * @return Map of context argument to be added to the service call mapped to their types.
     */
    public Map<String, TypeName> getContextFields() {
        return contextFields;
    }

    /**
     * @return The reference to the external service that this field is related to.
     */
    public CodeReference getReference() {
        return reference;
    }

    /**
     * @return The method that is referenced by this service.
     */
    public Method getMethod() {
        return method;
    }
}
