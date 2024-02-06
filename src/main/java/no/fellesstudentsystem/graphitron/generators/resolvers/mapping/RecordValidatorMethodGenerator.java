package no.fellesstudentsystem.graphitron.generators.resolvers.mapping;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import no.fellesstudentsystem.graphitron.definitions.fields.InputField;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.getRecordValidation;
import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.recordValidationEnabled;
import static no.fellesstudentsystem.graphitron.configuration.Recursion.recursionCheck;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.ClassNameFormat.wrapSet;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.argumentsLookup;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.NameFormat.*;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.*;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public class RecordValidatorMethodGenerator extends AbstractMappingMethodGenerator {
    private static final String
            VARIABLE_VALIDATION_ERRORS = "validationErrors",
            VARIABLE_PATHS_FOR_PROPERTIES = "pathsForProperties";

    public RecordValidatorMethodGenerator(InputField localField, ProcessedSchema processedSchema) {
        super(localField, processedSchema);
    }

    @Override
    public MethodSpec generate(InputField target) {
        var methodName = recordValidateMethod();
        if (!processedSchema.isTableInputType(target) || !getRecordValidation().isEnabled()) {
            return MethodSpec.methodBuilder(methodName).build();
        }

        return getDefaultSpecBuilder(methodName, asListedRecordName(target.getTypeName()), processedSchema.getInputType(target).getRecordClassName(), wrapSet(GRAPHQL_ERROR.className))
                .addParameter(DATA_FETCHING_ENVIRONMENT.className, ENV_NAME)
                .addStatement("var $L = new $T<$T>()", VARIABLE_VALIDATION_ERRORS, HASH_SET.className, GRAPHQL_ERROR.className)
                .addCode("\n")
                .addCode("$L\n", fillValidation(target, "", "", 0))
                .addStatement("return $N", VARIABLE_VALIDATION_ERRORS)
                .build();
    }

    /**
     * @return Code for filling setting the validation paths.
     */
    @NotNull
    protected CodeBlock fillValidation(InputField target, String previousName, String path, int recursion) {
        recursionCheck(recursion);

        var input = processedSchema.getInputType(target);
        var hasTable = input.hasTable();

        var code = CodeBlock.builder();
        var propertiesToValidateDeclaration = recursion == 0
                ? CodeBlock.builder().addStatement("var $L = new $T<$T, $T>()", VARIABLE_PATHS_FOR_PROPERTIES, HASH_MAP.className, STRING.className, STRING.className).build()
                : empty();
        var isIterable = target.isIterableWrapped() || recursion == 0;
        var recordNameListed = asListedRecordNameIf(hasTable ? input.getName() : previousName, isIterable);
        var iterableRecordName = asIterableIf(asRecordName(hasTable ? input.getName() : previousName), isIterable);
        var iterableIndexName = iterableRecordName + "Index";
        if (isIterable) {
            if (!hasTable) {
                return empty();
            }

            code
                    .beginControlFlow("for (int $L = 0; $N < $N.size(); $N++)", iterableIndexName, iterableIndexName, recordNameListed, iterableIndexName)
                    .addStatement("var $L = $N.get($N)", iterableRecordName, recordNameListed, iterableIndexName)
                    .add(propertiesToValidateDeclaration);
        }

        var containedInputs = input.getInputsSortedByNullability().stream().filter(it -> !processedSchema.isTableInputType(it)).collect(Collectors.toList());
        for (var in : containedInputs) {
            var nextPath = path.isEmpty() && !isIterable ? in.getName() : path + (isIterable ? (path.isEmpty() ? "" : "/\" + ") + iterableIndexName + " + \"/" : "/") + in.getName();
            if (processedSchema.isInputType(in)) {
                code.add(fillValidation(in, recordNameListed, nextPath, recursion + 1));
            } else {
                code
                        .beginControlFlow("if ($L)", argumentsLookup(nextPath.replaceAll("(.*?)\"/", "")))
                        .addStatement("$N.put($S, $N + $L\")", VARIABLE_PATHS_FOR_PROPERTIES, uncapitalize(in.getRecordMappingName()), PATH_HERE_NAME, nextPath)
                        .endControlFlow();
            }
        }

        if (recursion == 0) {
            code.addStatement("$N.addAll($T.validatePropertiesAndGenerateGraphQLErrors($N, $N, $N))", VARIABLE_VALIDATION_ERRORS, RECORD_VALIDATOR.className, iterableRecordName, VARIABLE_PATHS_FOR_PROPERTIES, ENV_NAME);
        }

        if (isIterable) {
            code.endControlFlow();
        }

        return code.build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        if (getLocalObject() == null || !getLocalObject().isGenerated() || !recordValidationEnabled()) {
            return List.of();
        }

        var input = processedSchema.getInputType(getLocalField());
        if (input == null || !input.hasTable() || input.hasJavaRecordReference()) {
            return List.of();
        }

        return List.of(generate(getLocalField()));
    }

    @Override
    public boolean generatesAll() {
        return getLocalObject() == null || !recordValidationEnabled() || getLocalObject().isGenerated();
    }
}
