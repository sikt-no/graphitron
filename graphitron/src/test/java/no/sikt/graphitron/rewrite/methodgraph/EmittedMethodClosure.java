package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.javapoet.JavaFile;
import no.sikt.graphitron.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R333 thread I, level 1 — the method-name closure walk over one generation run's emitted
 * {@link TypeSpec}s. The emit target is a graph of Java methods calling each other by name
 * (R333 "The unit is the emitted method"); this walk extracts that graph's two relations from
 * the current emit artifact and exposes the referential-integrity violations:
 *
 * <ul>
 *   <li><b>Node relation</b>: every method the emit declares, keyed by
 *       {@code (unit FQCN, nested-type path, method name)} ({@link #declaredMethods()}).</li>
 *   <li><b>Edge relation</b>: every <em>statically-qualified</em> call reference from one
 *       generated unit to a generated class — {@code GeneratedClass.method(...)},
 *       {@code Generated.Nested.method(...)}, a fully-qualified form, or a
 *       {@code GeneratedClass::method} method reference ({@link #edges()}).</li>
 *   <li><b>Violations</b>: edges whose callee name resolves to a generated class but to no
 *       method that class declares ({@link #unresolved()}). Closure under reference — thread I's
 *       invariant at level 1 — is {@code unresolved().isEmpty()}.</li>
 * </ul>
 *
 * <p><b>Level-1 scope.</b> This is the characterization form of the oracle: it walks the emit
 * artifact, not the model, so it is valid before any re-platforming and survives it as the
 * harness (R333 Scope). The bidirectional form (every emitted method is exactly one command's
 * output; every callee resolves to a <em>committed command</em>) needs the command/name registry
 * and lands with the emit slices, first populated for the reentry family by R314. Two documented
 * blind spots at this level, both closed by javac in the compilation tier and by the level-2
 * registry later: unqualified same-class calls (rendered with no class qualifier, so no
 * generated-name token to resolve), and instance calls through variables (the qualifier is a
 * value, not a class name). The load-bearing cross-unit seams — {@code $fields}, the
 * rows-methods, scatter, conditions, order-by, bean/record instantiation — are all
 * class-qualified static calls and are all visible to this walk; the oracle test pins that
 * visibility so a scan regression cannot go silently green.
 *
 * <p><b>How references are detected.</b> Each unit is rendered through {@link JavaFile} (the
 * same render the writer ships), comments and string/char literals are blanked (a baked
 * code-in-string reference is level-2 registry material, and prose in javadoc or literals must
 * not phantom-edge), and qualified call chains are scanned. A chain's qualifier resolves to a
 * generated class through the rendered import list, the unit's own package (same-package
 * references have no import), or a full FQCN match — mirroring how javac itself will bind the
 * name. A middle segment that is not a nested type of the resolved class (an enum constant, a
 * static field whose value the call is on) makes the reference an instance read, not a static
 * callee, and is skipped. A {@code new}-prefixed match is a constructor, not a method callee.
 */
final class EmittedMethodClosure {

    /** One statically-qualified callee reference: {@code fromUnit} calls {@code targetUnit[.typePath].methodName}. */
    record CallEdge(String fromUnit, String targetUnit, String typePath, String methodName) {
        String targetKey() {
            return typePath.isEmpty() ? targetUnit : targetUnit + "." + typePath;
        }

        @Override
        public String toString() {
            return fromUnit + " -> " + targetKey() + "#" + methodName;
        }
    }

    private final Map<String, Map<String, Set<String>>> declared;
    private final List<CallEdge> edges;
    private final List<CallEdge> unresolved;

    private EmittedMethodClosure(Map<String, Map<String, Set<String>>> declared,
                                 List<CallEdge> edges, List<CallEdge> unresolved) {
        this.declared = declared;
        this.edges = edges;
        this.unresolved = unresolved;
    }

    /** Walks one run's emitted units ({@code FQCN -> TypeSpec}, the writer's full closure). */
    static EmittedMethodClosure walk(Map<String, TypeSpec> emittedUnits) {
        Map<String, Map<String, Set<String>>> declared = new LinkedHashMap<>();
        emittedUnits.forEach((fqcn, spec) -> {
            Map<String, Set<String>> byPath = new LinkedHashMap<>();
            collectDeclared(spec, "", byPath);
            declared.put(fqcn, byPath);
        });

        List<CallEdge> edges = new ArrayList<>();
        List<CallEdge> unresolved = new ArrayList<>();
        for (var entry : emittedUnits.entrySet()) {
            scanUnit(entry.getKey(), entry.getValue(), emittedUnits.keySet(), declared, edges, unresolved);
        }
        return new EmittedMethodClosure(declared, List.copyOf(edges), List.copyOf(unresolved));
    }

    /** The node relation: {@code unit FQCN -> (nested-type path, "" for top level) -> declared method names}. */
    Map<String, Map<String, Set<String>>> declaredMethods() {
        return declared;
    }

    /** The edge relation: every resolved statically-qualified generated-to-generated callee reference. */
    List<CallEdge> edges() {
        return edges;
    }

    /** Referential-integrity violations: callee names on generated classes that no emitted method declares. */
    List<CallEdge> unresolved() {
        return unresolved;
    }

    /** True iff some edge references {@code targetKey#methodName} (targetKey = FQCN, plus nested path if any). */
    boolean hasEdge(String fromUnit, String targetKey, String methodName) {
        return edges.stream().anyMatch(e -> e.fromUnit().equals(fromUnit)
            && e.targetKey().equals(targetKey) && e.methodName().equals(methodName));
    }

    // ---------------------------------------------------------------------------------------------
    // Node collection

    private static void collectDeclared(TypeSpec spec, String path, Map<String, Set<String>> byPath) {
        Set<String> methods = new LinkedHashSet<>();
        spec.methodSpecs().forEach(m -> methods.add(m.name()));
        byPath.put(path, methods);
        for (TypeSpec nested : spec.typeSpecs()) {
            collectDeclared(nested, path.isEmpty() ? nested.name() : path + "." + nested.name(), byPath);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Edge collection

    // A qualified call chain: at least one dot before the opening parenthesis.
    private static final Pattern QUALIFIED_CALL =
        Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\s*\\(");
    // A method reference: Qualifier::method (constructor refs ::new are not method callees).
    private static final Pattern METHOD_REF =
        Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)::([A-Za-z_$][\\w$]*)");
    private static final Pattern IMPORT_LINE =
        Pattern.compile("^import\\s+(static\\s+)?([\\w.$]+)\\s*;", Pattern.MULTILINE);

    private static void scanUnit(String fqcn, TypeSpec spec, Set<String> generatedUnits,
                                 Map<String, Map<String, Set<String>>> declared,
                                 List<CallEdge> edges, List<CallEdge> unresolved) {
        String packageName = packageOf(fqcn);
        String source = render(packageName, spec);

        Map<String, String> importedSimpleNames = new LinkedHashMap<>();
        Matcher imports = IMPORT_LINE.matcher(source);
        while (imports.find()) {
            if (imports.group(1) != null) {
                continue; // static imports carry a member, not a type binding; none are emitted today
            }
            String imported = imports.group(2);
            importedSimpleNames.put(imported.substring(imported.lastIndexOf('.') + 1), imported);
        }

        String code = blankCommentsAndLiterals(source);

        Matcher calls = QUALIFIED_CALL.matcher(code);
        while (calls.find()) {
            if (precededByNew(code, calls.start(1))) {
                continue;
            }
            String chain = calls.group(1);
            int lastDot = chain.lastIndexOf('.');
            record(fqcn, chain.substring(0, lastDot), chain.substring(lastDot + 1),
                packageName, importedSimpleNames, generatedUnits, declared, edges, unresolved);
        }

        Matcher refs = METHOD_REF.matcher(code);
        while (refs.find()) {
            String method = refs.group(2);
            if (!method.equals("new")) {
                record(fqcn, refs.group(1), method,
                    packageName, importedSimpleNames, generatedUnits, declared, edges, unresolved);
            }
        }
    }

    private static void record(String fromUnit, String qualifier, String method,
                               String packageName, Map<String, String> importedSimpleNames,
                               Set<String> generatedUnits, Map<String, Map<String, Set<String>>> declared,
                               List<CallEdge> edges, List<CallEdge> unresolved) {
        Resolved target = resolveQualifier(qualifier, packageName, importedSimpleNames, generatedUnits, declared);
        if (target == null) {
            return;
        }
        CallEdge edge = new CallEdge(fromUnit, target.unit(), target.typePath(), method);
        edges.add(edge);
        Set<String> methods = declared.get(target.unit()).get(target.typePath());
        if (methods == null || !methods.contains(method)) {
            unresolved.add(edge);
        }
    }

    private record Resolved(String unit, String typePath) {}

    /**
     * Resolves a call chain's qualifier to a generated unit plus nested-type path, or {@code null}
     * when the qualifier is not a static reference to a generated class (a variable, a library
     * class, an enum constant / field read on the way to the call).
     */
    private static Resolved resolveQualifier(String qualifier, String packageName,
                                             Map<String, String> importedSimpleNames,
                                             Set<String> generatedUnits,
                                             Map<String, Map<String, Set<String>>> declared) {
        // Fully-qualified (or import/same-package-anchored) class, longest generated prefix wins.
        String base = null;
        String rest = null;
        if (generatedUnits.contains(qualifier)) {
            base = qualifier;
            rest = "";
        } else {
            for (String unit : generatedUnits) {
                if (qualifier.startsWith(unit + ".")) {
                    base = unit;
                    rest = qualifier.substring(unit.length() + 1);
                    break;
                }
            }
        }
        if (base == null) {
            int firstDot = qualifier.indexOf('.');
            String head = firstDot < 0 ? qualifier : qualifier.substring(0, firstDot);
            String resolvedHead = importedSimpleNames.get(head);
            if (resolvedHead == null) {
                String samePackage = packageName.isEmpty() ? head : packageName + "." + head;
                if (generatedUnits.contains(samePackage)) {
                    resolvedHead = samePackage;
                }
            }
            if (resolvedHead == null || !generatedUnits.contains(resolvedHead)) {
                return null;
            }
            base = resolvedHead;
            rest = firstDot < 0 ? "" : qualifier.substring(firstDot + 1);
        }

        // Every remaining segment must be a nested type of the resolved unit; anything else
        // (an enum constant, a static field) makes this an instance read, not a static callee.
        if (rest.isEmpty()) {
            return new Resolved(base, "");
        }
        Map<String, Set<String>> byPath = declared.get(base);
        StringBuilder typePath = new StringBuilder();
        for (String segment : rest.split("\\.")) {
            String candidate = typePath.isEmpty() ? segment : typePath + "." + segment;
            if (!byPath.containsKey(candidate)) {
                return null;
            }
            typePath.setLength(0);
            typePath.append(candidate);
        }
        return new Resolved(base, typePath.toString());
    }

    private static boolean precededByNew(String code, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        return i >= 2 && code.startsWith("new", i - 2)
            && (i - 3 < 0 || !Character.isJavaIdentifierPart(code.charAt(i - 3)));
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering

    private static String packageOf(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot < 0 ? "" : fqcn.substring(0, lastDot);
    }

    private static String render(String packageName, TypeSpec spec) {
        return JavaFile.builder(packageName, spec).indent("    ").build().toString();
    }

    /**
     * Blanks comments and string/char literals (preserving length) so prose and baked text cannot
     * phantom-edge. Single linear scan, so a quote inside a comment or a {@code /*} inside a
     * string cannot derail the stripping.
     */
    private static String blankCommentsAndLiterals(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                int end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(out, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && out[j] != c) {
                    j += out[j] == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                blank(out, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = from; i < to && i < out.length; i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
    }
}
