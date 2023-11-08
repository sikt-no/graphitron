package no.fellesstudentsystem.graphitron.definitions.mapping;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.fellesstudentsystem.graphitron.mappings.TableReflection.*;

/**
 * Stores operations related to rendering jOOQ key and table mappings.
 */
public class JOOQMapping implements JoinElement {
    private final String name, codeName;
    private JOOQMapping underlyingTable;

    private JOOQMapping(String name, String codeName) {
        this.name = name;
        this.codeName = codeName;
        underlyingTable = this;
    }

    private JOOQMapping(String name, String codeName, JOOQMapping underlyingTable) {
        this.name = name;
        this.codeName = codeName;
        this.underlyingTable = underlyingTable;
    }

    /**
     * @return The exact name of the data that this object corresponds to.
     */
    @Override
    public String getMappingName() {
        return name;
    }

    /**
     * @return The method call version of the name.
     */
    @Override
    public String getCodeName() {
        return codeName;
    }

    public static JOOQMapping fromTable(String tableName) {
        var codeNameUpper = Stream
            .of(tableName.toLowerCase().split("_"))
            .map(StringUtils::capitalize)
            .collect(Collectors.joining(""));
        return new JOOQMapping(tableName.toUpperCase(), StringUtils.uncapitalize(codeNameUpper));
    }

    public static JOOQMapping fromKey(String keyName) {
        var upperName = keyName.toUpperCase();
        var table = getKeySourceTable(upperName);
        return new JOOQMapping(
                upperName,
                table.map(it -> searchTableForKeyMethodName(it, upperName).orElse("")).orElse(""),
                table.map(JOOQMapping::fromTable).orElse(null)
        );
    }

    @Override
    public JOOQMapping getTable() {
        return underlyingTable;
    }

    /**
     * Extra method to fix key references that are reversed. TODO: Find better solution to know this for sure beforehand.
     */
    public void setTable(JOOQMapping table) {
        underlyingTable = table;
    }

    @Override
    public boolean clearsPreviousSequence() {
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JOOQMapping)) return false;
        JOOQMapping that = (JOOQMapping) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
