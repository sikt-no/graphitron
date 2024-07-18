package no.fellesstudentsystem.graphitron_newtestorder.dummygenerators;

import com.squareup.javapoet.CodeBlock;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationField;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerListMethodGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;

import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.listOf;
import static no.fellesstudentsystem.graphitron.generators.codebuilding.FormatCodeBlocks.returnWrap;

public class DummyTransformerListMethodGenerator extends TransformerListMethodGenerator {
    public DummyTransformerListMethodGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    @Override
    protected CodeBlock getMethodContent(GenerationField target) {
        return returnWrap(listOf());
    }
}
