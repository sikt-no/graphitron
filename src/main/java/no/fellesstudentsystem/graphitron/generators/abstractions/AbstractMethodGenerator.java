package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;

/**
 * An abstract generator that contains methods that are common between both DB-method generators and resolver generators.
 */
abstract public class AbstractMethodGenerator<T extends ObjectField> implements MethodGenerator<T> {
    public static final String ENV_NAME = "env";
    protected final ObjectDefinition localObject;
    protected final ProcessedSchema processedSchema;
    protected Set<Dependency> dependencySet = new HashSet<>();

    public AbstractMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        this.localObject = localObject;
        this.processedSchema = processedSchema;
    }

    /**
     * @return The object that this generator is attempting to build methods for.
     */
    public ObjectDefinition getLocalObject() {
        return localObject;
    }

    /**
     * @return Set of dependencies found during generation up to this point.
     */
    @Override
    public Set<Dependency> getDependencySet() {
        return dependencySet;
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    protected TypeName inputIterableWrap(InputField field) {
        var typeName = field.getTypeName();
        var typeClass = processedSchema.isInputType(typeName) ? processedSchema.getInputType(typeName).getGraphClassName() : field.getFieldType().getTypeClass();
        if (typeClass == null && processedSchema.isEnum(typeName)) {
            typeClass = processedSchema.getEnum(typeName).getGraphClassName();
        }
        return wrapListIf(typeClass, field.isIterableWrapped());
    }

    /**
     * @return Get the javapoet TypeName for this field's type, and wrap it in a list ParameterizedTypeName if it is iterable.
     */
    protected TypeName objectIterableWrap(ObjectField field) {
        var typeName = field.getTypeName();
        var typeClass = processedSchema.isObject(typeName) ? processedSchema.getObject(typeName).getGraphClassName() : field.getFieldType().getTypeClass();
        if (typeClass == null && processedSchema.isEnum(typeName)) {
            typeClass = processedSchema.getEnum(typeName).getGraphClassName();
        }
        return wrapListIf(typeClass, field.isIterableWrapped());
    }

    /**
     * @return Code block containing the enum conversion method call with an anonymous function declaration.
     */
    protected CodeBlock toJOOQEnumConverter(String enumType) {
        if (!processedSchema.isEnum(enumType)) {
            return empty();
        }

        var enumEntry = processedSchema.getEnum(enumType);
        var tempVariableName = "s";
        return CodeBlock
                .builder()
                .add(".convert($T.class, $L -> $L, $L -> $L)",
                        enumEntry.getGraphClassName(),
                        tempVariableName,
                        toNullSafeMapCall(CodeBlock.of(tempVariableName), enumEntry, true),
                        tempVariableName,
                        toNullSafeMapCall(CodeBlock.of(tempVariableName), enumEntry, false)
                )
                .build();
    }

    /**
     * @return Code block containing the enum conversion method call.
     */
    protected CodeBlock toGraphEnumConverter(String enumType, CodeBlock field) {
        if (!processedSchema.isEnum(enumType)) {
            return empty();
        }

        var enumEntry = processedSchema.getEnum(enumType);
        return toNullSafeMapCall(field, enumEntry, false);
    }

    private CodeBlock toNullSafeMapCall(CodeBlock variable, EnumDefinition enumEntry, boolean flipDirection) {
        return CodeBlock.of(
                "$L$L.getOrDefault($L, null)",
                nullIfNullElse(variable),
                mapOf(renderEnumMapElements(enumEntry, flipDirection)),
                variable
        );
    }

    private CodeBlock renderEnumMapElements(EnumDefinition enumEntry, boolean flipDirection) {
        var code = CodeBlock.builder();
        var hasEnumReference = enumEntry.hasJavaEnumMapping();
        var enumReference = enumEntry.getEnumReference();
        var entryClassName = enumEntry.getGraphClassName();
        var entrySet = new ArrayList<>(enumEntry.getFields());
        var entrySetSize = entrySet.size();
        for (int i = 0; i < entrySetSize; i++) {
            var enumValue = entrySet.get(i);
            if (flipDirection) {
                code
                        .add(renderEnumValueSide(hasEnumReference, enumReference, enumValue.getUpperCaseName()))
                        .add(", $L", renderEnumKeySide(entryClassName, enumValue.getName()));
            } else {
                code
                        .add("$L, ", renderEnumKeySide(entryClassName, enumValue.getName()))
                        .add(renderEnumValueSide(hasEnumReference, enumReference, enumValue.getUpperCaseName()));
            }
            if (i < entrySetSize - 1) {
                code.add(", ");
            }
        }
        return code.build();
    }

    private CodeBlock renderEnumKeySide(TypeName entryClassName, String keyName) {
        return CodeBlock.of("$T.$L", entryClassName, keyName);
    }

    private CodeBlock renderEnumValueSide(boolean hasEnumReference, CodeReference reference, String valueName) {
        if (hasEnumReference) {
            return CodeBlock.of("$T.$L", ClassName.get(GeneratorConfig.getExternalReferences().getClassFrom(reference)), valueName);
        } else {
            return CodeBlock.of("$S", valueName);
        }
    }
}
