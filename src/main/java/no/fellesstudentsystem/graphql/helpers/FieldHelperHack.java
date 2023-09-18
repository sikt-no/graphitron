package no.fellesstudentsystem.graphql.helpers;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A helper class that handles some special ID-unpacking. This does not belong in Graphitron and should be removed at some point.
 */
@Deprecated
public class FieldHelperHack {
    /**
     * @param packageName Encoded ID package name
     * @return The table contained within the ID package name
     * @throws IllegalArgumentException If the encoded ID package is not a valid ID.
     */
    public static String getTablePartOf(String packageName) {
        return getTablePartOf(packageName, decodeIDPackage(packageName));
    }

    private static String getTablePartOf(String packageName, String id) {
        if (id.indexOf(':') < 0) {
            throw new IllegalArgumentException(String.format("%s (%s) is not a valid ID.", packageName, id));
        }
        return id.substring(0, id.indexOf(':'));
    }

    private static String decodeIDPackage(String s) {
        try {
            return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(s + " is not a valid ID.");
        }
    }
}
