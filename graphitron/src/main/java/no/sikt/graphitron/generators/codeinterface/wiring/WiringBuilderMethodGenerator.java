package no.sikt.graphitron.generators.codeinterface.wiring;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import graphql.scalars.ExtendedScalars;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.abstractions.DataFetcherClassGenerator;
import no.sikt.graphitron.generators.abstractions.SimpleMethodGenerator;
import no.sikt.graphitron.generators.resolvers.datafetchers.typeresolvers.TypeResolverClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.empty;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.indentIfMultiline;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.join;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.NODE_ID_HANDLER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING;
import static no.sikt.graphitron.mappings.JavaPoetClassName.RUNTIME_WIRING_BUILDER;
import static no.sikt.graphitron.mappings.JavaPoetClassName.TYPE_RUNTIME_WIRING;

/**
 * This class generates code for the RuntimeWiring Builder fetching method.
 */
public class WiringBuilderMethodGenerator extends SimpleMethodGenerator {
    static final Logger LOGGER = LoggerFactory.getLogger(WiringBuilderMethodGenerator.class);

    public static final String METHOD_NAME = "getRuntimeWiringBuilder";
    private final static String VAR_WIRING = "wiring";
    private final List<ClassGenerator> generators;
    protected final boolean includeNode;
    private final ProcessedSchema processedSchema;

    public WiringBuilderMethodGenerator(List<ClassGenerator> generators, ProcessedSchema processedSchema) {
        this.generators = generators;
        this.includeNode = processedSchema.nodeExists();
        this.processedSchema = processedSchema;
    }

    private List<ClassWiringContainer> extractDataFetchers() {
        return generators
                .stream()
                .filter(it -> it instanceof DataFetcherClassGenerator)
                .flatMap(it -> ((DataFetcherClassGenerator<?>) it).getGeneratedDataFetchers().stream())
                .toList();
    }

    private List<ClassWiringContainer> extractTypeResolvers() {
        return generators
                .stream()
                .filter(it -> it instanceof TypeResolverClassGenerator)
                .flatMap(it -> ((TypeResolverClassGenerator) it).getGeneratedTypeResolvers().stream())
                .toList();
    }

    private Map<String, List<ClassWiringContainer>> getWiringByType() {
        return Stream
                .concat(extractDataFetchers().stream(), extractTypeResolvers().stream())
                .collect(Collectors.groupingBy(it -> it.wiring().schemaType(), LinkedHashMap::new, Collectors.toList()));
    }

    public MethodSpec.Builder getSpec(String methodName, TypeName returnType) {
        var spec = MethodSpec
                .methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addModifiers(Modifier.STATIC)
                .returns(returnType);
        if (includeNode) {
            spec.addParameter(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME);
        }
        return spec;
    }

    @Override
    public MethodSpec generate() {
        var wiringByType = getWiringByType();

        var code = CodeBlock
                .builder()
                .add(declare(VAR_WIRING, asMethodCall(RUNTIME_WIRING.className, "newRuntimeWiring")));
        wiringByType.forEach((k, v) ->
                code
                        .add("$N.type(", VAR_WIRING)
                        .add(
                                indentIfMultiline(
                                        join(
                                                newTypeWiring(k),
                                                indentIfMultiline(v.stream().map(ClassWiringContainer::toCode).collect(CodeBlock.joining("\n")))
                                        )
                                )
                        )
                        .addStatement(")")  // This is separated because it affects indent.
        );

        code.add(createAddScalarsCodeBlocks(processedSchema.getScalarTypes()));

        code.add(returnWrap(CodeBlock.of("$N", VAR_WIRING)));
        return getSpec(METHOD_NAME, RUNTIME_WIRING_BUILDER.className)
                .addCode(code.build())
                .build();
    }

    /**
     * Automatically adds scalar types to the wiring for all scalar types that are
     * not built-in, if they are recognized as extended scalars.
     *
     * @param scalarsInSchema the set of scalar type names to be added to the wiring
     * @return a CodeBlock representing the scalar type additions
     */
    private CodeBlock createAddScalarsCodeBlocks(Set<String> scalarsInSchema) {
        var extendedScalars = ScalarUtils.getAllExtendedScalarsNotOverriddenByUserProvidedScalars();

        return scalarsInSchema.stream()
                .filter(it -> !ScalarUtils.getBuiltInScalarNames().contains(it))
                .map(scalarName -> {
                    if (extendedScalars.containsKey(scalarName)) {
                        return CodeBlock.of("$N.scalar($T.$N);", VAR_WIRING, ExtendedScalars.class, extendedScalars.get(scalarName));
                    } else {
                        LOGGER.info("Scalar type '{}' is not recognized and must be added manually to the wiring.", scalarName);
                        return empty();
                    }
                }).collect(CodeBlock.joining("\n"));
    }

    private CodeBlock newTypeWiring(String type) {
        return CodeBlock.of("$T.newTypeWiring($S)", TYPE_RUNTIME_WIRING.className, type);
    }
}
