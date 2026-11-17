package no.sikt.graphitron.rewrite.test.services;

import java.util.List;
import java.util.Map;

/**
 * Fixture for the refusal of a map at the service boundary: services taking an input object as the
 * map graphql-java delivers, as {@code Object}, or as a list of maps, each of which graphitron refuses
 * in favour of a typed bean. Read by fact documents only; no schema serves them.
 */
public final class ServiceInputAsMapFixtures {

    private ServiceInputAsMapFixtures() {}

    public static String byMap(Map<String, Object> in) {
        return String.valueOf(in);
    }

    public static String byObject(Object in) {
        return String.valueOf(in);
    }

    public static String byMaps(List<Map<String, Object>> in) {
        return String.valueOf(in);
    }
}
