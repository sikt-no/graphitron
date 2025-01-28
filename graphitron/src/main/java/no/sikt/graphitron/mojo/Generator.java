package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.Extension;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;

import java.util.List;
import java.util.Set;

public interface Generator {
    String getOutputPath();

    String getOutputPackage();
    Set<String> getSchemaFiles();
    String getGeneratedSchemaCodePackage();
    String getJooqGeneratedPackage();
    String getResolverAnnotation();

    RecordValidation getRecordValidation();

    int getMaxAllowedPageSize();

    List<? extends ExternalReference> getExternalReferences();

    Set<String> getExternalReferenceImports();

    List<GlobalTransform> getGlobalTransforms();

    List<Extension> getExtensions();
}
