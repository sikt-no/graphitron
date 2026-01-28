package no.sikt.graphitron.definitions.mapping;

import no.sikt.graphitron.definitions.interfaces.JoinElement;
import no.sikt.graphitron.mappings.TableReflection;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

import static no.sikt.graphitron.generators.codebuilding.NameFormat.toCamelCase;
import static no.sikt.graphitron.mappings.TableReflection.*;

/**
 * Stores operations related to rendering jOOQ key and table mappings.
 */
public class JOOQMapping extends MethodMapping implements JoinElement {
    private final String name, codeName;
    private final JOOQMapping underlyingTable;

    private JOOQMapping(String name, String codeName) {
        super(name);
        this.name = name;
        this.codeName = codeName;
        this.underlyingTable = this;
    }

    private JOOQMapping(String name, String codeName, JOOQMapping underlyingTable) {
        super(name);
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
        var codeNameUpper = toCamelCase(tableName);
        return new JOOQMapping(tableName.toUpperCase(), StringUtils.uncapitalize(codeNameUpper));
    }

    public static JOOQMapping fromKey(String keyName) {
        var upperName = keyName.toUpperCase();
        var sourceTable = getFkSourceTableForFkJavaFieldName(upperName);
        var targetTable = getFkTargetTableForFkJavaFieldName(upperName);

        return new JOOQMapping(
                upperName,
                sourceTable.map(it -> searchTableFieldNameForPathMethodNameGivenFkJavaFieldName(it, upperName).orElse("")).orElse(""),
                targetTable.map(JOOQMapping::fromTable).orElse(null)
        );
    }

    public JOOQMapping getInverseKey() {
        var sourceTable = getFkSourceTableForFkJavaFieldName(name).map(JOOQMapping::fromTable).orElse(null);
        var targetTable = getFkTargetTableForFkJavaFieldName(name).map(JOOQMapping::fromTable).orElse(null);

        if (sourceTable == null || targetTable == null) {
            return null;
        }

        var isReverse = sourceTable.equals(underlyingTable);

        return new JOOQMapping(
                name,
                searchTableFieldNameForPathMethodNameGivenFkJavaFieldName(isReverse ? sourceTable.getName() : targetTable.getName(), name).orElse(""),
                (isReverse ? targetTable : sourceTable)
        );
    }

    @Override
    public JOOQMapping getTable() {
        return underlyingTable;
    }

    @Override
    public boolean clearsPreviousSequence() {
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JOOQMapping that)) return false;
        return Objects.equals(name, that.name) && underlyingTable.getName().equalsIgnoreCase(that.underlyingTable.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    public Class<?> getTableClass() {
        // FIXME: Dersom skjema refererer til en tabell som ikke finnes burde vi stoppe genereringen (FSP-538)
        return TableReflection.getTableClassGivenTableJavaFieldName(name)
                .orElse(Object.class);
    }

    public Class<?> getRecordClass() {
        // FIXME: Dersom skjema refererer til en tabell som ikke finnes burde vi stoppe genereringen (FSP-538)
        return TableReflection.getRecordClassGivenTableJavaFieldName(name)
                .orElse(Object.class);
    }
}
