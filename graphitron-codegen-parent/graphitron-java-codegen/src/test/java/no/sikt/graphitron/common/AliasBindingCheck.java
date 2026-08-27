package no.sikt.graphitron.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.fail;

/**
 * Checks that every table alias a generated query declares is bound to that query's FROM tree.
 * <p>
 * An alias that is declared and referenced, but never reachable from a FROM or JOIN, compiles cleanly and passes any
 * check that only looks at whether a column belongs to its table. The database is the first thing to notice, and it
 * rejects the statement at parse time. This makes the invariant a build failure instead.
 * <p>
 * The unit checked is one statement rather than one method, because a query is spread across a method and the fragment
 * helpers it calls, all of which redeclare the aliases they use and any of which may carry the FROM that binds them.
 * Methods are therefore pooled with everything they call and everything that calls them before aliases are resolved.
 * <p>
 * Within a pool, an alias is bound when it appears in a {@code from} or {@code join} argument, when it arrives as a
 * parameter, or when its initializer derives it from another bound alias. That last case is how jOOQ implicit path
 * joins work: selecting a column off {@code customer.address()} makes jOOQ join {@code ADDRESS} on its own, so such an
 * alias needs no join of its own, but does need the alias it hangs off to be bound. An alias built straight from a
 * table constant has no such anchor, so only an explicit join or FROM can bind it.
 */
public final class AliasBindingCheck {
    private static final Pattern DECLARATION = Pattern.compile("^\\s*var (_a_\\w+) = (.*);\\s*$");
    private static final Pattern SIGNATURE = Pattern.compile("^ {4}(public|private|protected) .*");
    private static final Pattern BINDING = Pattern.compile("\\.\\w*[Jj]oin\\(|\\.from\\(");
    private static final Pattern METHOD_END = Pattern.compile("^ {4}}\\s*$");
    private static final Pattern ALIAS = Pattern.compile("(?<![A-Za-z0-9_])(_a_\\w+)");
    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(");
    private static final Pattern FETCH = Pattern.compile("\\.fetch\\w*\\(");

    private AliasBindingCheck() {
    }

    public static Map<String, List<String>> assertAliasesAreBound(Map<String, List<String>> generatedFiles) {
        var violations = new ArrayList<String>();
        generatedFiles.forEach((fileName, content) -> checkFile(fileName, content, violations));
        if (!violations.isEmpty()) {
            fail("Generated queries reference table aliases that nothing binds in their FROM tree:\n%s",
                    String.join("\n", violations));
        }
        return generatedFiles;
    }

    static void checkFile(String fileName, List<String> content, List<String> violations) {
        var methods = parseMethods(content);
        for (var pool : poolByCallGraph(methods)) {
            checkPool(fileName, pool.stream().flatMap(it -> methods.get(it).stream()).toList(), violations);
        }
    }

    private static Map<String, List<String>> parseMethods(List<String> content) {
        var methods = new LinkedHashMap<String, List<String>>();
        List<String> current = null;
        var name = "";
        for (var line : String.join("\n", content).lines().toList()) {
            if (current == null && SIGNATURE.matcher(line).matches()) {
                var matcher = METHOD_NAME.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                name = matcher.group(1);
                current = new ArrayList<>();
            }
            if (current == null) {
                continue;
            }
            current.add(line);
            if (METHOD_END.matcher(line).matches()) {
                methods.put(name, current);
                current = null;
            }
        }
        return methods;
    }

    /**
     * Groups methods into connected components of the in-file call graph, treated as undirected: a fragment helper is
     * bound by its caller's FROM, and a caller's aliases may be bound by a FROM inside the fragment it embeds.
     */
    private static List<Set<String>> poolByCallGraph(Map<String, List<String>> methods) {
        var pools = new ArrayList<Set<String>>();
        var assigned = new LinkedHashSet<String>();
        for (var method : methods.keySet()) {
            if (assigned.contains(method)) {
                continue;
            }
            var pool = new LinkedHashSet<String>();
            var frontier = new ArrayList<>(List.of(method));
            while (!frontier.isEmpty()) {
                var next = frontier.remove(frontier.size() - 1);
                if (!pool.add(next)) {
                    continue;
                }
                methods.forEach((other, lines) -> {
                    if (pool.contains(other)) {
                        return;
                    }
                    if (calls(methods.get(next), other) || calls(lines, next)) {
                        frontier.add(other);
                    }
                });
            }
            assigned.addAll(pool);
            pools.add(pool);
        }
        return pools;
    }

    private static boolean calls(List<String> caller, String callee) {
        var body = String.join("\n", caller.subList(Math.min(1, caller.size()), caller.size()));
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(callee) + "\\s*\\(").matcher(body).find();
    }

    private static void checkPool(String fileName, List<String> pool, List<String> violations) {
        if (pool.stream().noneMatch(it -> FETCH.matcher(it).find())) {
            return; // No terminal operation, so this is a fragment and the query that embeds it owns the FROM tree.
        }

        var declarations = new LinkedHashMap<String, String>();
        var boundText = new StringBuilder();
        for (var line : pool) {
            var declaration = DECLARATION.matcher(line);
            if (declaration.matches()) {
                declarations.put(declaration.group(1), declaration.group(2));
            } else if (BINDING.matcher(line).find()) {
                boundText.append(line).append('\n');
            }
        }

        var bound = new LinkedHashSet<String>();
        declarations.keySet().stream().filter(it -> references(boundText.toString(), it)).forEach(bound::add);
        // Anything never declared in the pool arrived as a parameter, so the caller bound it.
        pool.stream().flatMap(AliasBindingCheck::aliasesIn).filter(it -> !declarations.containsKey(it)).forEach(bound::add);
        spreadThroughImplicitPaths(declarations, bound);

        declarations.entrySet().stream()
                .filter(it -> !bound.contains(it.getKey()))
                .forEach(it -> violations.add("  %s: alias %s = %s is never joined, selected from, or derived from a bound alias"
                        .formatted(fileName, it.getKey(), it.getValue())));
    }

    /**
     * An alias derived from a bound alias is itself bound, and can in turn bind the aliases derived from it, so this
     * repeats until the set stops growing.
     */
    private static void spreadThroughImplicitPaths(Map<String, String> declarations, Set<String> bound) {
        boolean grew;
        do {
            grew = false;
            for (var declaration : declarations.entrySet()) {
                if (!bound.contains(declaration.getKey()) && aliasesIn(declaration.getValue()).anyMatch(bound::contains)) {
                    bound.add(declaration.getKey());
                    grew = true;
                }
            }
        } while (grew);
    }

    private static Stream<String> aliasesIn(String text) {
        return ALIAS.matcher(text).results().map(it -> it.group(1));
    }

    private static boolean references(String text, String alias) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(alias) + "(?![A-Za-z0-9_])").matcher(text).find();
    }
}
