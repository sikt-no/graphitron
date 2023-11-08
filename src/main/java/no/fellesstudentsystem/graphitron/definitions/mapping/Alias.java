package no.fellesstudentsystem.graphitron.definitions.mapping;

import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Holds an alias.
 */
public class Alias implements JoinElement {
    private final String name, shortName;
    private final JOOQMapping type;

    public Alias(String prefix, JOOQMapping type, boolean isLeft) {
        var name = prefix + "_" + type.getMappingName() + (isLeft ? "_left" : "");
        this.name = name.toLowerCase();
        this.shortName = createShortAliasName(Arrays.stream(prefix.split("_")).findFirst().orElse(prefix), name);
        this.type = type;
    }

    /**
     * @return Alias name.
     */
    @Override
    public String getMappingName() {
        return name;
    }

    /**
     * @return Shortened version of the alias name.
     */
    public String getShortName() {
        return shortName;
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
        crc32.update(to.getBytes());
        return from + "_" + crc32.getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Alias)) return false;
        Alias that = (Alias) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
