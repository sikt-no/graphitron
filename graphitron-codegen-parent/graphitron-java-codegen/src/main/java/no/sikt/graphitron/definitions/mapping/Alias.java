package no.sikt.graphitron.definitions.mapping;

import no.sikt.graphitron.definitions.interfaces.JoinElement;
import no.sikt.graphitron.generators.context.JoinListSequence;

import java.util.Objects;
import java.util.zip.CRC32;

import static no.sikt.graphitron.generators.codebuilding.VariablePrefix.aliasPrefix;

/**
 * Holds an alias.
 */
public class Alias implements JoinElement {
    private final String name, shortName, variableValue;
    private final JOOQMapping type;

    public Alias(String prefix, JOOQMapping table, boolean isLeft) {
        this(
                prefix + "_" + table.getMappingName(),
                table,
                table.getMappingName(),
                table.getMappingName(),
                isLeft
        );
    }

    public Alias(String prefix, JoinListSequence joinSequence, boolean isLeft) {
        this(
                prefix,
                joinSequence.getLast().getTable(),
                joinSequence.getLast().getTable().getMappingName(),
                joinSequence.render().toString(),
                isLeft
        );
    }

    private Alias(String name, JOOQMapping type, String aliasName, String variableValue, boolean isLeft) {
        this.name = aliasPrefix(name + (isLeft ? "_left" : "")).toLowerCase();
        var withoutNamespace = name.replaceFirst(aliasPrefix(""), "");
        this.type = type;
        this.shortName = createShortAliasName(withoutNamespace.replace("_", ""), aliasName.replace("_", "").toLowerCase());
        this.variableValue = variableValue;
    }

    /**
     * @return Alias name.
     */
    @Override
    public String getMappingName() {
        return name;
    }

    @Override
    public String getCodeName() {
        return shortName;
    }

    @Override
    public boolean clearsPreviousSequence() {
        return true;
    }

    @Override
    public JOOQMapping getTable() {
        return type;
    }

    /**
     * Short name for Alias due to character limit on Oracle Alias values (JIRA reference: <a href="https://unit.atlassian.net/browse/ROK-685">ROK-685</a>).
     */
    private String createShortAliasName(String from, String to) {
        var crc32 = new CRC32();
        crc32.reset();
        crc32.update(from.getBytes());
        return to + "_" + crc32.getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Alias that)) return false;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    /**
    * Content for alias variable declaration
    */
    public String getVariableValue() {
        return variableValue;
    }

    public AliasWrapper toAliasWrapper() {
        return new AliasWrapper(this);
    }
}
