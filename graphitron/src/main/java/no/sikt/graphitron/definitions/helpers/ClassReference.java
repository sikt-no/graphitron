package no.sikt.graphitron.definitions.helpers;

import com.squareup.javapoet.ClassName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.CodeReference;

public class ClassReference {
    private final Class<?> classReference;
    private final ClassName className;

    public ClassReference(CodeReference reference) {
        if (reference != null) {
            var referencedClass = GeneratorConfig.getExternalReferences().getClassFrom(reference);
            if (referencedClass == null) {
                throw new IllegalArgumentException("Invalid class reference: " + reference.getSchemaClassReference());
            }
            classReference = referencedClass;
            className = ClassName.get(referencedClass);
        } else {
            classReference = null;
            className = null;
        }
    }

    public ClassReference(String typeName, String packageSource) {
        Class<?> reference;
        try {
            reference = Class.forName(packageSource + "." + typeName);
        } catch (ClassNotFoundException e) {
            reference = null;
        }
        classReference = reference;
        className = classReference != null
                ? ClassName.get(classReference)
                : ClassName.get(packageSource, typeName); // TODO: Remove special else case (should be just null). In our test the objects do not exist and this class lookup will fail. When the graphql-codegen is integrated in this module this will be redundant.
    }

    public ClassReference(Class<?> reference) {
        classReference = reference;
        className = ClassName.get(reference);
    }

    public Class<?> getReferenceClass() {
        return classReference;
    }

    public String getClassNameString() {
        return className != null ? className.simpleName() : "";
    }

    public ClassName getClassName() {
        return className;
    }
}
