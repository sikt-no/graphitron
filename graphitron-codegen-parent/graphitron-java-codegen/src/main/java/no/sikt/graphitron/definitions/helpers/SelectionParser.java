package no.sikt.graphitron.definitions.helpers;

import no.sikt.graphql.schema.SelectionSetParser;

import java.util.LinkedHashMap;

public class SelectionParser {
    public static ConstructSelection parseSelection(String selection) {
        var fields = SelectionSetParser.parseFields(selection);
        var result = new LinkedHashMap<String, String>();
        for (var field : fields) {
            var key = field.getAlias() != null ? field.getAlias() : field.getName();
            result.put(key, field.getName());
        }
        return new ConstructSelection(result);
    }
}
