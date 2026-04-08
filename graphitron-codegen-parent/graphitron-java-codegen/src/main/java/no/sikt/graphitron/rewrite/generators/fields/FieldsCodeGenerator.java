package no.sikt.graphitron.rewrite.generators.fields;

import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;

/**
 * Generates a {@link TypeSpec} for one {@code <TypeName>Fields} class.
 *
 * <p>At this stage the class is empty. Field methods and wiring are added by subsequent
 * deliverables as the generator matures.
 */
public class FieldsCodeGenerator {

    public TypeSpec generate(String typeName) {
        return TypeSpec.classBuilder(typeName + "Fields")
            .addModifiers(Modifier.PUBLIC)
            .build();
    }
}
