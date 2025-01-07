package no.sikt.graphitron.generators.datafetchers.wiring;

public class WiringContainer {
    private final String methodName, schemaType, schemaField;

    public WiringContainer(String methodName, String schemaType, String schemaField) {
        this.methodName = methodName;
        this.schemaType = schemaType;
        this.schemaField = schemaField;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getSchemaType() {
        return schemaType;
    }

    public String getSchemaField() {
        return schemaField;
    }
}
