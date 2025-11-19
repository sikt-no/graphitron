package no.sikt.graphitron.generators.codebuilding;

import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * This class handles the naming conventions for internal variables. When generating code, we need to name parameters and variables.
 * The different names may come from different namespaces. A namespace in this context is a set of strings, meaning a collection that has no duplicates.
 * A namespace can for example be the set of fields that a GraphQL type has, as there can not be two fields with the same name.
 * Therefore, using the names of the fields as variables is safe, as they will never collide with each other.
 * Another example is the set of internal helper variables in Graphitron-generated code, as these must also be unique and are predetermined by a single source, the developers.
 * When variables hail from different namespaces, however, they inevitably risk colliding in the generated code without proper countermeasures.
 * <p>
 * An example of a collision may be an input field (it will become a method parameter) which has the same name as a jOOQ table.
 * In the query Graphitron may create an alias for this table, while also having a method parameter that inherits the name of the input field.
 * Such code may result in uncompilable code at best or produce incorrect query results at worst.
 * <p>
 * The risk of collisions would be even higher for instance if the DB/GraphQL-domain was code-related.
 * To prevent such issues we use variable prefixes for all variables in the generated code.
 * These make it impossible to accidentally or intentionally produce naming collisions, given that the prefixes are not substrings of each other.
 * <p>
 * Here is a more rigorous explanation and proof for why this is needed:<br>
 * Assume two variable names N and M (which are based on user-defined configuration beyond our control or other factors such as DB-schema).
 * These two variables exist in their corresponding namespaces with their own prefixes P and Q where P ≠ Q and where there exists no string S such that P = S + Q or Q = S + P.
 * <p>
 * Let us take a look at the possibilities:<br>
 * 1. No prefixes are applied. Here, a conflict immediately emerges in the case N = M. As such, this solution is unacceptable.
 * <p>
 * 2. Prefixes are applied to only one namespace, P. This means that the resulting variable X in the code will be X = P + N.<br>
 *  One can easily engineer a problem case by assuming that M consists of two substrings concatenated such that M = P + N.<br>
 *  Since prefixes in the name space Q are not applied (are empty), we get that the generated variable Y will be Y = M = P + N = X, and so we have another collision.
 *  Thus, this solution is also unacceptable.
 * <p>
 * 3. Prefixes are applied for all namespaces. This time the generated variables will be X = P + N and Y = Q + M.<br>
 *  Let us disprove that there exist a case where X = Y. If X = Y, then P + N = Q + M. We have three possibilities:<br>
 *  * N = M results in P + N = Q + N and P = Q which is a contradiction with our premise P ≠ Q. So we have that N ≠ M.<br>
 *  * The next possibility is that the prefixes have the same length |P| = |Q|.<br>
 *    For this to be true and for the strings to still be equal, every character in P must match the one at the same index in Q, thus resulting in P = Q, contradicting P ≠ Q.<br>
 *  * Lastly, for |P| ≠ |Q| one of them must be shorter, so let us use |P| &lt; |Q|. This would be equivalent for |Q| &lt; |P|. Since P + N = Q + M, Q must use P as a prefix.<br>
 *    This means that Q = P + S where S is a non-empty string. This contradicts our second premise that there must not exist a string S such that P = S + Q or Q = S + P.<br>
 *  With this solution there can not exist a case where X = Y and variable naming collisions are impossible.
 */
public class VariablePrefix {
    // Note that all names are fine, so long as none of these prefixes are the same for multiple name spaces.
    // There should also not exist any pair of prefixes that are substrings of each other.
    public static final String
            INTERNAL_VARIABLE = "iv",
            METHOD_INPUT = "mi",
            METHOD_OUTPUT = "mo",
            METHOD_LIST_OUTPUT = "mlo",
            OPERATION_SOURCE = "os",
            DATA_FETCHER_KEYS = "rk",
            DATA_FETCHER_SERVICE = "rs",
            DATA_FETCHER_QUERY = "rq",
            SELECT_JOIN_STEP = "sjs",
            INSERT_HELPER = "ih",
            NAMED_ITERATOR = "nit",
            NAMED_ITERATOR_INDEX = "niit",
            ALIAS = "a",
            CONTEXT_FIELD = "cf",
            SEPARATOR = "_";

    /**
     * @return This name formatted as an internal variable to avoid namespace collisions.
     */
    public static String internalPrefix(String name) {
        return prefixName(INTERNAL_VARIABLE, name);
    }

    /**
     * @return This name formatted as an input variable to avoid namespace collisions.
     */
    public static String inputPrefix(String name) {
        return prefixName(METHOD_INPUT, name);
    }

    /**
     * @return This name formatted as an output variable to avoid namespace collisions.
     */
    public static String outputPrefix(String name) {
        return prefixName(METHOD_OUTPUT, name);
    }

    /**
     * @return This name formatted as a listed output variable to avoid namespace collisions.
     */
    public static String listedOutputPrefix(String name) {
        return prefixName(METHOD_LIST_OUTPUT, name);
    }

    /**
     * @return This name formatted as an operation source variable to avoid namespace collisions.
     */
    public static String sourcePrefix(String name) {
        return prefixName(OPERATION_SOURCE, name);
    }

    /**
     * @return This name formatted as a resolver key variable to avoid namespace collisions.
     */
    public static String resolverKeyPrefix(String name) {
        return prefixName(DATA_FETCHER_KEYS, name);
    }

    /**
     * @return This name formatted as an alias variable to avoid namespace collisions.
     */
    public static String aliasPrefix(String name) {
        return prefixName(ALIAS, name);
    }

    /**
     * @return This name formatted as a context variable to avoid namespace collisions.
     */
    public static String contextFieldPrefix(String name) {
        return prefixName(CONTEXT_FIELD, name);
    }

    /**
     * @return This name formatted as an iterator variable to avoid namespace collisions.
     */
    public static String namedIteratorPrefix(String name) {
        return prefixName(NAMED_ITERATOR, name);
    }

    /**
     * @return This name formatted as an index iterator variable to avoid namespace collisions.
     */
    public static String namedIndexIteratorPrefix(String name) {
        return prefixName(NAMED_ITERATOR_INDEX, name);
    }

    /**
     * @return This name formatted as a service class object reference to avoid namespace collisions.
     */
    public static String servicePrefix(String name) {
        return prefixName(DATA_FETCHER_SERVICE, name);
    }

    /**
     * @return This name formatted as a query class object reference to avoid namespace collisions.
     */
    public static String queryPrefix(String name) {
        return prefixName(DATA_FETCHER_QUERY, name);
    }

    /**
     * @return This name formatted as an operation query select join step variable to avoid namespace collisions.
     */
    public static String joinStepPrefix(String name) {
        return prefixName(SELECT_JOIN_STEP, name);
    }

    /**
     * @return This name formatted as an insert mutation helpervariable to avoid namespace collisions.
     */
    public static String insertHelperPrefix(String name) {
        return prefixName(INSERT_HELPER, name);
    }

    /**
     * @return This name prefixed with the provided prefix.
     */
    public static String prefixName(String prefix, String name) {
        var actualPrefix = prefixFormat(prefix);
        return name.startsWith(actualPrefix) ? name : actualPrefix + uncapitalize(name);
    }

    /**
     * This adds the separator symbol before and after the prefix.
     * @return The correct format for prefixes.
     */
    private static String prefixFormat(String prefix) {
        return SEPARATOR + prefix + SEPARATOR;
    }
}
