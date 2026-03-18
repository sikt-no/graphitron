package no.sikt.graphitron.definitions.helpers;

import java.util.LinkedHashMap;

/**
 * Wrapper for handling selection construction patterns. May be expanded to handle more complex cases later.
 * @param values Map of selection values.
 */
public record ConstructSelection(LinkedHashMap<String, String> values) {}
