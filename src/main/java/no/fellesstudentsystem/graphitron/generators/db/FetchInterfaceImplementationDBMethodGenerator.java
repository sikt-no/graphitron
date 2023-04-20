package no.fellesstudentsystem.graphitron.generators.db;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.abstractions.GeneratorContext;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;

/**
 * Generator that creates the data fetching methods for interface implementations, e.g. queries used by the node resolver.
 */
public class FetchInterfaceImplementationDBMethodGenerator extends DBMethodGenerator<ObjectField> {

    private final Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField;

    public FetchInterfaceImplementationDBMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema, Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField) {
        super(localObject, processedSchema);
        this.interfacesReturnedByObjectField = interfacesReturnedByObjectField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var implementation = getLocalObject();
        var implementationTableObject = implementation.getTable();
        var tableName = implementationTableObject.getName();

        ObjectField implementationReference = new ObjectField(new FieldDefinition(getLocalObject().getName(),
                new TypeName(getLocalObject().getName())));

        var context = new GeneratorContext(processedSchema, implementationReference, implementation);
        var selectCode = generateSelectRow(context);
        var returnType = implementation.getGraphClassName();
        var localName = implementation.getName();

        String argumentName = target.getInputFields().get(0).getName() + "s";
        String idName = "id";

        var code = CodeBlock.builder()
                .add(createSelectAliases(context.getJoinList(), context.getAliasList()))
                .add("return ctx\n")
                .indent()
                .indent()
                .add(".select(\n")
                .indent()
                .indent()
                .add(tableName + ".getId(),\n")
                .add(selectCode)
                .unindent()
                .add(".as($S)", idName)
                .add("\n")
                .unindent()
                .add(")\n")
                .add(".from(" + tableName + ")\n")
                .add(createSelectJoins(context.getJoinList()))
                .add(".where($N.has$N($N))\n",
                        tableName,
                        StringUtils.capitalize(argumentName),
                        argumentName
                )
                .add(createSelectConditions(context.getConditionList()))
                .addStatement("." + (!target.getFieldType().isIterableWrapped() ? "fetchMap" : "fetchGroups")
                                + "($T::value1, $T::value2)",
                        RECORD2.className,
                        RECORD2.className
                )
                .unindent()
                .unindent();

        return getDefaultSpecBuilder(
                "load" + localName + "By" + StringUtils.capitalize(argumentName) + "As" + StringUtils.capitalize(target.getName()),
                ParameterizedTypeName.get(MAP.className, STRING.className, returnType)
        )
                .addParameter(ParameterizedTypeName.get(SET.className, STRING.className), argumentName)
                .addParameter(SELECTION_SETS.className, SELECTION_NAME)
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return interfacesReturnedByObjectField
                .entrySet()
                .stream()
                .filter(entry -> getLocalObject().implementsInterface(entry.getValue().getName()))
                .sorted(Comparator.comparing(it -> it.getKey().getName()))
                .map(entry -> generate(entry.getKey()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(it -> processedSchema.isInterface(it.getTypeName()))
                .allMatch(ObjectField::isGenerated);
    }
}
