package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import no.fellesstudentsystem.kjerneapi.enums.GeneratorEnum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static no.fellesstudentsystem.graphitron.generators.context.ClassNameFormat.wrapListIf;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.MAP;

abstract public class AbstractMethodGenerator<T extends ObjectField> implements MethodGenerator<T> {
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

    @Override
    public Set<Dependency> getDependencySet() {
        return dependencySet;
    }

    protected TypeName inputIterableWrap(InputField field) {
        var typeName = field.getTypeName();
        var typeClass = processedSchema.isInputType(typeName) ? processedSchema.getInputType(typeName).getGraphClassName() : field.getFieldType().getTypeClass();
        if (typeClass == null && processedSchema.isEnum(typeName)) {
            typeClass = processedSchema.getEnum(typeName).getGraphClassName();
        }
        return wrapListIf(typeClass, field.isIterableWrapped());
    }

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
    protected CodeBlock toJOOQEnumConverter(String enumType, Map<String, Class<?>> enumOverrides) {
        if (!processedSchema.isEnum(enumType)) {
            return CodeBlock.of("");
        }

        var enumEntry = processedSchema.getEnum(enumType);
        return CodeBlock
                .builder()
                .add(".convert($T.class, s -> s == null ? null : $T.of(", enumEntry.getGraphClassName(), MAP.className)
                .add(renderMapElements(enumEntry, true, enumOverrides))
                .add(").getOrDefault(s, null), s -> s == null ? null : $T.of(", MAP.className)
                .add(renderMapElements(enumEntry, false, enumOverrides))
                .add(").getOrDefault(s, null))")
                .build();
    }

    /**
     * @return Code block containing the enum conversion method call.
     */
    protected CodeBlock toGraphEnumConverter(String enumType, CodeBlock field, Map<String, Class<?>> enumOverrides) {
        if (!processedSchema.isEnum(enumType)) {
            return CodeBlock.of("");
        }

        var enumEntry = processedSchema.getEnum(enumType);
        return CodeBlock
                .builder()
                .add("$L == null ? null : $T.of(", field, MAP.className)
                .add(renderMapElements(enumEntry, false, enumOverrides))
                .add(").getOrDefault($L, null)", field)
                .build();
    }

    private CodeBlock renderMapElements(EnumDefinition enumEntry, boolean flipDirection, Map<String, Class<?>> enumOverrides) {
        var code = CodeBlock.builder();
        var hasEnumReference = enumEntry.hasDbEnumMapping();
        var dbName = enumEntry.getDbName();
        var entryClassName = enumEntry.getGraphClassName();
        var entrySet = new ArrayList<>(enumEntry.getValuesMap().entrySet());
        var entrySetSize = entrySet.size();
        for (int i = 0; i < entrySetSize; i++) {
            var enumValue = entrySet.get(i);
            if (flipDirection) {
                code
                        .add(renderValueSide(hasEnumReference, dbName, enumValue.getValue().getUpperCaseName(), enumOverrides))
                        .add(", $T.$L", entryClassName, enumValue.getKey());
            } else {
                code
                        .add("$T.$L, ", entryClassName, enumValue.getKey())
                        .add(renderValueSide(hasEnumReference, dbName, enumValue.getValue().getUpperCaseName(), enumOverrides));
            }
            if (i < entrySetSize - 1) {
                code.add(", ");
            }
        }
        return code.build();
    }

    private CodeBlock renderValueSide(boolean hasEnumReference, String dbName, String valueName, Map<String, Class<?>> enumOverrides) {
        var code = CodeBlock.builder();
        if (hasEnumReference) {
            var enumName = dbName.toUpperCase();
            var apiEnumType = enumOverrides.containsKey(enumName) ? enumOverrides.get(enumName) : GeneratorEnum.valueOf(enumName).getEnumType();
            code.add("$T.$L", ClassName.get(apiEnumType.getPackageName(), apiEnumType.getSimpleName()), valueName);
        } else {
            code.add("$S", valueName);
        }
        return code.build();
    }

}
