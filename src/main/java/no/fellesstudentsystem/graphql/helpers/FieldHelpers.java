package no.fellesstudentsystem.graphql.helpers;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class FieldHelpers {
    /**
     * @param pakke Kodet ID-pakke
     * @return tabelldelen av en ID-pakke
     * @throws IllegalArgumentException dersom kodet ID-pakke ikke representerer en gyldig ID i FS-plattformen
     */
    public static String getTablePartOf(String pakke) {
        return getTablePartOf(pakke, dec(pakke));
    }

    private static String getTablePartOf(String pakke, String id) {
        if (id.indexOf(':') < 0) {
            throw new IllegalArgumentException(String.format("%s (%s) er ikke en gyldig ID i FS-plattformen.", pakke, id));
        }
        return id.substring(0, id.indexOf(':'));
    }

    /**
     * Dekoder en ID-pakke.
     *
     * @param s Kodet ID-pakke
     * @return Dekodet ID-pakke
     */
    private static String dec(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(s + " er ikke en gyldig ID i FS-plattformen.");
        }
    }
}
