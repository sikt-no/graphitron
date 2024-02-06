package no.fellesstudentsystem.graphitron.definitions.helpers;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.CodeReference;

public class JavaRecordReference {
    private final Class<?> classReference;
    private final TypeName typeName;

    public JavaRecordReference(CodeReference reference ) {
        if (reference != null) {
            var recordClass = GeneratorConfig.getExternalReferences().getClassFrom(reference);
            this.classReference = recordClass;
            this.typeName = ClassName.get(recordClass);
        } else {
            this.classReference = null;
            this.typeName = null;
        }
    }

    public Class<?> getRecordClass() {
        return classReference;
    }

    public String getClassName() {
        return classReference != null ? classReference.getSimpleName() : "";
    }

    public TypeName getTypeName() {
        return typeName;
    }
}
