package no.fellesstudentsystem.graphitron.reducedgenerators.dummygenerators;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;

public class DummyTransformerMethodGenerator extends TransformerMethodGenerator {
    public DummyTransformerMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    protected CodeBlock getMethodContent(GenerationField target) {
        return returnWrap(target.isInput() ? CodeBlock.of("new $T()", processedSchema.getRecordType(target).getRecordClassName()) : CodeBlock.of("null"));
    }
}
