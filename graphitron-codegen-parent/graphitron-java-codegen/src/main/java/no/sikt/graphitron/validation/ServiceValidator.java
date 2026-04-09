package no.sikt.graphitron.validation;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.GenerationSourceField;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.CodeReferenceWrapper;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.definitions.sql.SQLCondition;
import no.sikt.graphitron.generators.codebuilding.VariablePrefix;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static no.sikt.graphitron.validation.ServiceMethodFormatter.formatExpectedListableType;
import static no.sikt.graphitron.validation.ServiceMethodFormatter.formatOverloads;
import static no.sikt.graphitron.validation.ValidationHandler.addErrorMessage;
import static no.sikt.graphql.directives.GenerationDirective.*;

/**
 * Validates service references, service method existence, input types, and parameter type compatibility.
 */
class ServiceValidator extends AbstractSchemaValidator {

    ServiceValidator(ProcessedSchema schema, List<ObjectField> allFields) {
        super(schema, allFields);
    }

    @Override
    void validate() {
        validateExternalMappingReferences();
        validateServiceMethods();
        validateServiceInputTypes();
        validateServiceMethodParameterTypes();
    }

    private void validateExternalMappingReferences() {
        var referenceSet = GeneratorConfig.getExternalReferences();
        schema
                .getEnums()
                .values()
                .stream()
                .filter(EnumDefinition::hasJavaEnumMapping)
                .map(EnumDefinition::getEnumReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No enum with name '%s' found.", e.getSchemaClassReference()));

        allFields.stream()
                .filter(ObjectField::hasServiceReference)
                .map(ObjectField::getExternalMethod)
                .map(CodeReferenceWrapper::getReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No service with name '%s' found.", e.getSchemaClassReference()));

        allFields
                .stream()
                .filter(ObjectField::isGenerated)
                .filter(ObjectField::hasCondition)
                .map(ObjectField::getCondition)
                .map(SQLCondition::getReference)
                .filter(e -> !referenceSet.contains(e))
                .forEach(e -> addErrorMessage("No condition with name '%s' found.", e.getSchemaClassReference()));
    }

    private void validateServiceMethods() {
        allFields.stream()
                .filter(ObjectField::hasServiceReference)
                .map(GenerationSourceField::getExternalMethod)
                .filter(it -> GeneratorConfig.getExternalReferences().contains(it.getReference()))
                .filter(it -> getAllOverloads(it).isEmpty())
                .forEach(it -> addErrorMessage("Service reference with name '%s' does not contain a method named '%s'.", GeneratorConfig.getExternalReferences().getClassFrom(it.getReference()), it.getReference().getMethodName()));
    }

    private void validateServiceInputTypes() {
        allFields.stream()
                .filter(ObjectField::hasServiceReference)
                .forEach(field -> field.getNonReservedArguments()
                        .stream()
                        .filter(schema::isInputType)
                        .filter(arg -> !schema.hasRecord(arg))
                        .forEach(arg -> addErrorMessage(
                                "Input type '%s' is used as an argument on service field '%s.%s', but has neither the @%s nor the @%s directive. "
                                        + "Input types on @%s operations must have one of these directives.",
                                arg.getTypeName(),
                                field.getContainerTypeName(),
                                field.getName(),
                                TABLE.getName(),
                                RECORD.getName(),
                                SERVICE.getName()
                        ))
                );
    }

    private void validateServiceMethodParameterTypes() {
        allFields.stream()
                .filter(ObjectField::hasServiceReference)
                .filter(field -> field.getNonReservedArguments()
                        .stream()
                        .filter(schema::isInputType)
                        .allMatch(schema::hasRecord))
                .forEach(this::checkServiceParameterTypes);
    }

    private void checkServiceParameterTypes(ObjectField field) {
        var serviceWrapper = field.getExternalMethod();
        var overloads = getAllOverloads(serviceWrapper);
        if (overloads.isEmpty()) {
            return;
        }

        var parser = new InputParser(field, schema);
        var resolverKeyOffset = field.isRootField() ? 0 : 1;
        var expectedParamCount = resolverKeyOffset + parser.getMethodInputNames(true, true, true).size();

        var candidates = overloads.stream()
                .filter(m -> m.getParameterTypes().length == expectedParamCount)
                .toList();

        if (candidates.isEmpty()) {
            addErrorMessage(
                    "Service field '%s.%s' maps to %d method parameter(s) but there is no overload of '%s' with that parameter count. Available overloads:\n%s",
                    field.getContainerTypeName(),
                    field.getName(),
                    expectedParamCount,
                    serviceWrapper.getMethodName(),
                    formatOverloads(overloads, serviceWrapper.getMethodName())
            );
            return;
        }

        var allMethodInputs = parser.getMethodInputNames(false, false, false);

        for (var entry : parser.getRecords().entrySet()) {
            var varName = entry.getKey();
            var inputField = entry.getValue();

            var posInInputs = allMethodInputs.indexOf(VariablePrefix.inputPrefix(varName));
            if (posInInputs < 0) {
                continue;
            }
            var methodParamIndex = resolverKeyOffset + posInInputs;

            var inputDef = schema.getInputType(inputField);
            if (inputDef == null || !inputDef.hasRecordReference()) {
                continue;
            }
            var inputClass = inputDef.getRecordReference();
            if (inputClass == null) {
                continue;
            }

            var inputIsListed = inputField.isIterableWrapped();
            if (candidates.stream().noneMatch(o -> isParameterCompatible(o, methodParamIndex, inputClass, inputIsListed))) {
                addErrorMessage(
                        "Argument '%s' on service field '%s.%s' has input type '%s' which maps to '%s', "
                                + "but there is no overload of '%s' that accepts this. Available overloads:\n%s",
                        inputField.getName(),
                        field.getContainerTypeName(),
                        field.getName(),
                        inputField.getTypeName(),
                        formatExpectedListableType(inputClass, inputIsListed),
                        serviceWrapper.getMethodName(),
                        formatOverloads(candidates, serviceWrapper.getMethodName())
                );
            }
        }
    }

    private boolean isParameterCompatible(Method method, int paramIndex, Class<?> inputClass, boolean inputIsListed) {
        var paramTypes = method.getParameterTypes();
        if (paramIndex >= paramTypes.length) {
            return false;
        }
        if (inputIsListed != List.class.isAssignableFrom(paramTypes[paramIndex])) {
            return false;
        }
        var effectiveType = getEffectiveParameterType(method, paramIndex, inputIsListed);
        return effectiveType != null && effectiveType.isAssignableFrom(inputClass);
    }

    private List<Method> getAllOverloads(CodeReferenceWrapper serviceWrapper) {
        return GeneratorConfig.getExternalReferences().getMethodsFrom(serviceWrapper.getReference());
    }

    private Class<?> getEffectiveParameterType(Method method, int paramIndex, boolean isListed) {
        var paramTypes = method.getParameterTypes();
        if (paramIndex >= paramTypes.length) {
            return null;
        }
        if (!isListed) {
            return paramTypes[paramIndex];
        }
        var genericTypes = method.getGenericParameterTypes();
        if (paramIndex < genericTypes.length
                && genericTypes[paramIndex] instanceof ParameterizedType pt
                && pt.getActualTypeArguments().length > 0
                && pt.getActualTypeArguments()[0] instanceof Class<?> elementType) {
            return elementType;
        }
        return null;
    }
}
