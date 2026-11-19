package no.sikt.graphitron.store.functions;

/**
 * The functions the fact store's SQL calls, each bound once by {@code CREATE ALIAS} in the store's
 * DDL and called by the Java that renders the same thing, so a message reads the same whichever
 * reader assembles it.
 */
public final class StoreFunctions {

    private StoreFunctions() {}

    /**
     * The edit distance between two strings: the fewest single-character insertions, deletions and
     * substitutions turning one into the other. What a "did you mean" ranks candidates by, nearest
     * first; a caller wanting it case-blind lowers both sides.
     */
    public static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1))
                    curr[j] = prev[j - 1];
                else
                    curr[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }
}
