package no.sikt.graphitron.generators.codeinterface.wiring;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.configuration.GeneratorConfig;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_STRATEGY_NAME;
import static no.sikt.graphql.naming.GraphQLReservedName.NODE_TYPE;
import static org.apache.commons.lang3.StringUtils.uncapitalize;

public record ClassWiringContainer(WiringContainer wiring, ClassName containerClass) {
    public CodeBlock toCode() {
        if (wiring.isFetcher()) {
            CodeBlock methodCall;

            if (GeneratorConfig.shouldMakeNodeStrategy()) {
                methodCall = CodeBlock.of("$T.$L($N)", containerClass, wiring.methodName(), NODE_ID_STRATEGY_NAME);
            } else {
                methodCall = wiring.schemaField().equals(uncapitalize(NODE_TYPE.getName()))
                             ? CodeBlock.of("$T.$L($N)", containerClass, wiring.methodName(), NODE_ID_HANDLER_NAME)
                             : asMethodCall(containerClass, wiring.methodName());
            }
            return CodeBlock.of(".dataFetcher($S, $L)", wiring.schemaField(), methodCall);
        }
        return CodeBlock.of(".typeResolver($L)", asMethodCall(containerClass, wiring.methodName()));
    }
}
