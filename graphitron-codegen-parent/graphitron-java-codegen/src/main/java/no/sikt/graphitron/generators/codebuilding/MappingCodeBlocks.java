package no.sikt.graphitron.generators.codebuilding;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.context.MapperContext;
import no.sikt.graphitron.javapoet.CodeBlock;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.collectToList;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.getNodeQueryCallBlock;
import static no.sikt.graphitron.generators.codebuilding.NameFormat.asNodeQueryName;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.PATH_HERE_NAME;

public class MappingCodeBlocks {
    @Deprecated
    public static CodeBlock idFetchAllowingDuplicates(MapperContext context, GenerationField field, String varName, boolean atResolver) {
        var get = getNodeQueryCallBlock(field, varName, !atResolver ? CodeBlock.of("$N + $S", PATH_HERE_NAME, context.getPath()) : CodeBlock.of("$S", context.getPath()), atResolver);
        if (!context.isIterable()) {
            return context.getSetMappingBlock(get);
        }

        var tempName = asNodeQueryName(field.getTypeName());
        return CodeBlock
                .builder()
                .declare(tempName, get)
                .add(context.getSetMappingBlock(CodeBlock.of("$N.stream().map(it -> $N.get(it.getId()))$L", varName, tempName, collectToList())))
                .build();
    }
}
