package no.sikt.graphitron.rewrite.test.nameless;

import java.util.List;

/**
 * A service compiled without {@code -parameters}, so its classfile carries no parameter names: what
 * a library built that way looks like to a reading. A reference naming one of these methods has no
 * name to bind an argument to, which is what the editor warns about and an argMapping cannot target.
 */
public final class NamelessService {

    private NamelessService() {}

    public static Object compute(Object input) {
        return input;
    }

    public static List<String> list(int limit) {
        return List.of();
    }
}
