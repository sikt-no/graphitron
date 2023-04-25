package no.fellesstudentsystem.graphitron.definitions.sql;

import com.squareup.javapoet.ClassName;
import no.fellesstudentsystem.graphitron.definitions.mapping.RecordMethodMapping;

import static no.fellesstudentsystem.graphitron.configuration.GeneratorConfig.RECORDS_PACKAGE_PATH;
import static no.fellesstudentsystem.graphitron.generators.context.NameFormat.asRecordName;

/**
 * A record representation of table data.
 */
public class SQLRecord {
    private final String name, type;
    private final ClassName graphClassName;

    public SQLRecord(RecordMethodMapping recordMethodMapping) {
        this.name = asRecordName(recordMethodMapping.getCodeName());
        this.type = asRecordName(recordMethodMapping.getName());
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
}
