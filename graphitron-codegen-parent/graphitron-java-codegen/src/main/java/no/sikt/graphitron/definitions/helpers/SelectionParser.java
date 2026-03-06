package no.sikt.graphitron.definitions.helpers;

import java.util.LinkedHashMap;

public class SelectionParser {
    public static ConstructSelection parseSelection(String selection) {
        var result = new LinkedHashMap<String, String>();

        // Parsing of selection will be here.

        return new ConstructSelection(result);
    }
}
