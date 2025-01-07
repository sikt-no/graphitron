package no.sikt.graphitron.generators.wiring;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.generators.abstractions.AbstractMethodGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.*;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class generates code for the RuntimeWiring fetching method.
 */
public class WiringMethodGenerator extends AbstractMethodGenerator<ObjectField> {
    public static final String METHOD_NAME = "getRuntimeWiring";
    private final static String VAR_WIRING = "wiring";
    private final List<ClassGenerator<? extends GenerationTarget>> generators;

    public WiringMethodGenerator(List<ClassGenerator<? extends GenerationTarget>> generators, ProcessedSchema schema) {
        super(null, schema);
        this.generators = generators;
    }

    private List<ClassWiringContainer> extractDataFetchers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedDataFetchers().stream())
                .collect(Collectors.toList());
    }

    private List<ClassWiringContainer> extractTypeResolvers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedTypeResolvers().stream())
                .collect(Collectors.toList());
    }

    @Override
    public MethodSpec generate(ObjectField dummy) {
        var fetchersByType = extractDataFetchers().stream().collect(Collectors.groupingBy(it -> it.getWiring().getSchemaType()));

        var code = CodeBlock
                .builder()
                .add(declare(VAR_WIRING, asMethodCall(RUNTIME_WIRING.className, "newRuntimeWiring")));
        for (var df : fetchersByType.entrySet()) {
            var fetchers = new ArrayList<CodeBlock>();
            for (var wiringContainer : df.getValue()) {
                var wiring = wiringContainer.getWiring();
                var methodCall = wiring.getSchemaField().equals(uncapitalize(NODE_TYPE.getName()))
                        ? CodeBlock.of("$T.$L($N)", wiringContainer.getContainerClass(), wiring.getMethodName(), NODE_ID_HANDLER_NAME)
                        : asMethodCall(wiringContainer.getContainerClass(), wiring.getMethodName());
                fetchers.add(CodeBlock.of(".dataFetcher($S, $L)", wiring.getSchemaField(), methodCall));
            }
            code.add(builderBlock(df.getKey(), indentIfMultiline(fetchers.stream().collect(CodeBlock.joining("\n")))));
        }

        var containers = extractTypeResolvers();
        for (var container : containers) {
            var wiring = container.getWiring();
            code.add(builderBlock(wiring.getSchemaType(), CodeBlock.of(".typeResolver($L)", asMethodCall(container.getContainerClass(), wiring.getMethodName()))));
        }
        code.add(returnWrap(CodeBlock.of("$N.build()", VAR_WIRING)));
        var spec = getDefaultSpecBuilder(METHOD_NAME, RUNTIME_WIRING.className).addModifiers(Modifier.STATIC);
        if (processedSchema.nodeExists()) {
            spec.addParameter(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME);
        }
        return spec
                .addCode(code.build())
                .build();
    }

    private CodeBlock newTypeWiring(String type) {
        return CodeBlock.of("$T.newTypeWiring($S)", TYPE_RUNTIME_WIRING.className, type);
    }

    private CodeBlock builderBlock(String schemaType, CodeBlock content) {
        return CodeBlock
                .builder()
                .add("$N.type($L", VAR_WIRING, indentIfMultiline(CodeBlock.of("$L$L", newTypeWiring(schemaType), content)))
                .addStatement(")")
                .build();
    }

    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generate(null));
    }

    @Override
    public boolean generatesAll() {
        return true;
    }
}
