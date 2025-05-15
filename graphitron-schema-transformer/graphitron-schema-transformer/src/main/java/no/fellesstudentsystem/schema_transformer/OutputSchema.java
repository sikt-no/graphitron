package no.fellesstudentsystem.schema_transformer;

import java.util.Set;

public class OutputSchema {
    private String fileName;
    private Set<String> flags;

    public OutputSchema() {}

    public OutputSchema(String fileName) {
        this(fileName, Set.of());
    }

    public OutputSchema(String fileName, Set<String> flags) {
        this.fileName = fileName;
        this.flags = flags;
    }

    public String fileName() {
        return fileName;
    }

    public Set<String> flags() {
        return flags != null ? flags : Set.of();
    }
}
