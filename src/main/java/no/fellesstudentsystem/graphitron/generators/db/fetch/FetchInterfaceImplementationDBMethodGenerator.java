package no.fellesstudentsystem.graphitron.generators.db.fetch;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.definitions.objects.InterfaceDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.abstractions.DBMethodGenerator;
import no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames;
import no.fellesstudentsystem.graphitron.generators.context.FetchContext;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.getStringSetTypeName;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapStringMap;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.VARIABLE_SELECT;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.RECORD2;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.SELECTION_SET;
import static no.fellesstudentsystem.graphql.naming.GraphQLReservedName.NODE_ID;

/**
 * Generator that creates the data fetching methods for interface implementations, e.g. queries used by the node resolver.
 */
public class FetchInterfaceImplementationDBMethodGenerator extends DBMethodGenerator<ObjectField> {
    private final Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField;

    public FetchInterfaceImplementationDBMethodGenerator(
            ObjectDefinition localObject,
            ProcessedSchema processedSchema,
            Map<ObjectField, InterfaceDefinition> interfacesReturnedByObjectField
    ) {
        super(localObject, processedSchema);
        this.interfacesReturnedByObjectField = interfacesReturnedByObjectField;
    }

    @Override
    public MethodSpec generate(ObjectField target) {
        var implementation = getLocalObject();
        var implementationTableObject = implementation.getTable();
        if (implementationTableObject == null) {
            var interfaceName = interfacesReturnedByObjectField.containsKey(target) ? interfacesReturnedByObjectField.get(target).getName() : "";
            throw new IllegalArgumentException(String.format("Type %s needs to have the @%s directive set to be able to implement interface %s", implementation.getName(), GenerationDirective.TABLE.getName(), interfaceName));
        }

        var implementationReference = new ObjectField(
                new FieldDefinition(target.getName(), new TypeName(getLocalObject().getName())),
                target.getTypeName()
        );

        var context = new FetchContext(processedSchema, implementationReference, implementation);
        var selectCode = generateSelectRow(context);

        String argumentName = target.getArguments().get(0).getName() + "s";
        var querySource = context.renderQuerySource(implementationTableObject);

        var code = CodeBlock.builder()
                .add(createSelectAliases(context.getJoinSet()))
                .add("return $N\n", VariableNames.CONTEXT_NAME)
                .indent()
                .indent()
                .add(".select(\n")
                .indent()
                .indent()
                .add("$L.getId(),\n", querySource)
                .add(selectCode)
                .unindent()
                .unindent()
                .add(")\n")
                .add(".from($L)\n", querySource)
                .add(createSelectJoins(context))
                .add(".where($L.has$N($N))\n",
                        querySource,
                        StringUtils.capitalize(argumentName),
                        argumentName
                )
                .add(createSelectConditions(context))
                .addStatement(".$L($T::value1, $T::value2)",
                        (!target.isIterableWrapped() ? "fetchMap" : "fetchGroups"),
                        RECORD2.className,
                        RECORD2.className
                )
                .unindent()
                .unindent();

        return getDefaultSpecBuilder(
                "load" + implementation.getName() + "By" + StringUtils.capitalize(argumentName) + "As" + StringUtils.capitalize(target.getName()),
                wrapStringMap(implementation.getGraphClassName())
        )
                .addParameter(getStringSetTypeName(), argumentName)
                .addParameter(SELECTION_SET.className, VARIABLE_SELECT)
                .addCode(code.build())
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return interfacesReturnedByObjectField
                .entrySet()
                .stream()
                .filter(entry -> ((ObjectDefinition) getLocalObject()).implementsInterface(entry.getValue().getName()))
                .sorted(Comparator.comparing(it -> it.getKey().getName()))
                .map(entry -> generate(entry.getKey()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject()
                .getFields()
                .stream()
                .filter(processedSchema::isInterface)
                .allMatch(GenerationField::isGenerated);
    }
}
