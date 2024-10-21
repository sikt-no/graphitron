package no.fellesstudentsystem.graphitron.configuration;

public class Extension {
    @SuppressWarnings("unused")
    private String extendedClass;
    @SuppressWarnings("unused")
    private String extensionClass;

    @SuppressWarnings("unused")
    public Extension() {}

    public Extension(String extendedClass, String extensionClass) {
        this.extendedClass = extendedClass;
        this.extensionClass = extensionClass;
    }

    public String getExtendedClass() {
        return extendedClass;
    }

    public String getExtensionClass() {
        return extensionClass;
    }
}
