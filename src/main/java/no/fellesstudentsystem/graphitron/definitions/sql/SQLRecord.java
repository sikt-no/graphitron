package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.ClassName;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.RECORDS_PACKAGE_PATH;

/**
 * A record representation of table data.
 */
public class SQLRecord {
    public static final String RECORD_NAME_SUFFIX = "Record";
    private final String name, type;
    private final ClassName graphClassName;

    public SQLRecord(RecordMethodMapping recordMethodMapping) {
        this.name = recordMethodMapping.getCodeName() + RECORD_NAME_SUFFIX;
        this.type = recordMethodMapping.getName() + RECORD_NAME_SUFFIX;
        graphClassName = ClassName.get(RECORDS_PACKAGE_PATH, type);
    }

    /**
     * @return The name the record will be declared with.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The type of the record.
     */
    public String getType() {
        return type;
    }

    /**
     * @return The javapoet {@link ClassName} for the imported generated record.
     */
    public ClassName getGraphClassName() {
        return graphClassName;
    }

    /**
     * @return Format this string as a record naming pattern.
     */
    public static String asRecordName(String name) {
        return name + RECORD_NAME_SUFFIX;
    }
}
