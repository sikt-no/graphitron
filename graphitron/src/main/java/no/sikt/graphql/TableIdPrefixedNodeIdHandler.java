package no.sikt.graphql;

import org.jooq.Table;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;


abstract public class TableIdPrefixedNodeIdHandler implements NodeIdHandler {

    private final Map<String, String> idToName;
    private final Map<String, Table> nameToTable;

    @Inject
    public TableIdPrefixedNodeIdHandler(Map<String, String> tableIdToTableName, Map<String, Table> nameToTable) {
        this.idToName = tableIdToTableName;
        this.nameToTable = nameToTable;
    }

    @Override
    public Table<?> getTable(String id) {
        String tablePartOfId = getTablePartOf(id);

        Optional<String> tableName = Optional.ofNullable(idToName.get(tablePartOfId));
        if (tableName.isEmpty()) {
            throw new IllegalArgumentException(String.format("Finner ikke tabellnavn med id: %s", id));
        } 

        return nameToTable.get(tableName.get());
    }
    public static String getTablePartOf(String pakke) {
        return getTablePartOf(pakke, dec(pakke));
    }

    private static String getTablePartOf(String pakke, String id) {
        if (id.indexOf(':') < 0) {
            throw new IllegalArgumentException(String.format("%s (%s) er ikke en gyldig ID", pakke, id));
        }
        return id.substring(0, id.indexOf(':'));
    }

    private static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("%s inneholder ugyldige tegn", s), e);
        }
    }
}
