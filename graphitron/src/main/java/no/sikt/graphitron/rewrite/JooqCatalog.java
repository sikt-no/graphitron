package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ForeignKeyRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.jooq.Catalog;
import org.jooq.ForeignKey;
import org.jooq.Schema;
import org.jooq.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thin wrapper around jOOQ's {@link Catalog}. Loads the catalog once via reflection
 * ({@code DefaultCatalog.DEFAULT_CATALOG}) and provides lazy lookups — no pre-built maps.
 * Each method queries the catalog on demand using the jOOQ API.
 *
 * <p>When the generated jOOQ package is not on the classpath (e.g. in unit tests that do not
 * depend on jOOQ codegen output), all lookup methods return empty/empty-list rather than
 * throwing. A warning is logged at construction time so misconfiguration is visible in
 * production logs.
 */
public class JooqCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(JooqCatalog.class);

    private final Catalog catalog;
    private final ClassLoader codegenLoader;
    private final Map<String, NodeIdMetadataLookup> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> qualifierMapCache = new ConcurrentHashMap<>();

    public JooqCatalog(String generatedJooqPackage, ClassLoader codegenLoader) {
        this.codegenLoader = codegenLoader;
        this.catalog = loadDefaultCatalog(generatedJooqPackage, codegenLoader);
        if (this.catalog != null) {
            this.catalog.schemaStream().forEach(s -> verifyTablesClassPresent(s, codegenLoader));
        }
    }

    /**
     * Single-arg back-compat constructor: defaults {@code codegenLoader} to the current thread's
     * context classloader. Used by unit-tier test sites that reflect against classes on their
     * own module's classpath (where TCCL = system classloader and bare two-arg
     * {@code Class.forName} resolves identically).
     */
    public JooqCatalog(String generatedJooqPackage) {
        this(generatedJooqPackage, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Build-time precondition: every schema in the live jOOQ catalog must publish a generated
     * {@code Tables} class. A missing {@code Tables} class is a degenerate codegen state (jOOQ's
     * {@code <tables>false</tables>} flag, or equivalent) that breaks every per-table emit site;
     * surfacing it once at construction is friendlier than letting downstream lookups fail one
     * by one. The Mojo wraps the thrown {@link IllegalStateException} into a build-boundary
     * diagnostic.
     */
    static void verifyTablesClassPresent(Schema schema, ClassLoader codegenLoader) {
        verifyTablesClassPresent(schema.getName(), schema.getClass().getPackageName(), codegenLoader);
    }

    static void verifyTablesClassPresent(String schemaName, String packageName, ClassLoader codegenLoader) {
        try {
            Class.forName(packageName + ".Tables", false, codegenLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "jOOQ codegen produced no `Tables` class for schema '" + schemaName
                + "' (expected " + packageName + ".Tables). Set `<tables>true</tables>` in the"
                + " jOOQ codegen configuration; graphitron's catalog resolution depends on the"
                + " generated Tables constants and refuses to start when they are absent.");
        }
    }

    /**
     * Find a table by its SQL name, with optional schema qualification. Accepts both
     * unqualified ({@code "film"}) and qualified ({@code "public.film"}) values; the input is
     * routed through {@link #parseQualifiedTableName(String)} and either the unqualified
     * resolver below or the two-arg {@link #findTable(String, String)} form.
     *
     * <p>Unqualified values fan out into the {@link TableResolution} sub-taxonomy:
     * {@link TableResolution.Resolved} when exactly one schema carries the name,
     * {@link TableResolution.Ambiguous} when two or more schemas carry it,
     * {@link TableResolution.NotInCatalog} otherwise.
     * Qualified values resolve through the two-arg form and surface as {@code Resolved} or
     * {@code NotInCatalog} only — qualification eliminates the ambiguity branch by construction.
     * Schema and table matching is case-insensitive on both halves (consistent with
     * {@link #findColumn}).
     *
     * <p>Single-schema setups never fan out into {@code Ambiguous}; multi-schema setups with
     * non-colliding table names also stay on the resolved/missing axis. Only collision sites
     * are forced to qualify.
     */
    public TableResolution findTable(String sqlName) {
        return parseQualifiedTableName(sqlName)
            .map(qn -> qn.isQualified()
                ? findTable(qn.schema().get(), qn.table())
                    .<TableResolution>map(TableResolution.Resolved::new)
                    .orElseGet(TableResolution.NotInCatalog::new)
                : resolveUnqualified(qn.table()))
            .orElseGet(TableResolution.NotInCatalog::new);
    }

    private TableResolution resolveUnqualified(String unqualifiedSqlName) {
        var matches = findUnqualifiedTable(unqualifiedSqlName);
        return switch (matches.size()) {
            case 0 -> new TableResolution.NotInCatalog();
            case 1 -> new TableResolution.Resolved(matches.get(0));
            default -> new TableResolution.Ambiguous(matches.stream()
                .map(e -> e.table().getSchema().getName())
                .toList());
        };
    }

    /**
     * Two-arg lookup form: scopes resolution to the named schema directly, never re-parses,
     * never scans across schemas. Used by callers that already hold a resolved schema (or
     * that have split a qualified name themselves) and need a stable, case-insensitive
     * table-within-schema lookup. The single-arg {@link #findTable(String)} delegates here
     * once parsing is done.
     *
     * <p>Returns empty when the schema is not in the catalog or the table-within-schema does
     * not exist; both halves are matched case-insensitively.
     */
    public Optional<TableEntry> findTable(String schemaSqlName, String tableSqlName) {
        if (catalog == null) return Optional.empty();
        return catalog.schemaStream()
            .filter(s -> s.getName().equalsIgnoreCase(schemaSqlName))
            .findFirst()
            .flatMap(s -> entriesIn(s)
                .filter(e -> e.table().getName().equalsIgnoreCase(tableSqlName))
                .findFirst());
    }

    /**
     * Class-keyed lookup: returns the entry whose generated jOOQ table class matches the given
     * class. Used by the {@code @reference(path: [{condition:}])} resolver when reflecting on a
     * condition method's second parameter to find the target table — the parameter type, when
     * concrete, is a generated jOOQ table class that maps to exactly one catalog entry across
     * the whole schema set (each table's generated class is uniquely owned by its schema, so
     * class identity is schema-unique by construction).
     *
     * <p>Returns empty when the catalog is unavailable or no entry's table class equals the
     * given class. Wildcard types ({@code Table<?>}) and non-table classes are caller
     * responsibility; this accessor compares by exact class identity ({@code ==}), not
     * assignability.
     */
    public Optional<TableEntry> findTableByClass(Class<?> jooqTableClass) {
        if (catalog == null || jooqTableClass == null) return Optional.empty();
        return catalog.schemaStream()
            .flatMap(this::entriesIn)
            .filter(e -> e.table().getClass() == jooqTableClass)
            .findFirst();
    }

    /**
     * Resolves a {@code @routine(name:)} value against the catalog as a table-valued read function
     * (R300 day-one). jOOQ models such a function as a first-class catalog {@code Table<R>} tagged
     * {@code TableOptions.function()}, plus a convenience method on the schema's global
     * {@code Routines} class that returns the configured table for use in {@code FROM}. This resolves
     * both: the result table (so the caller binds the existing {@code @table} return-type machinery)
     * and the {@code Routines}-class call surface with the routine's IN parameters in declaration
     * order.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@link RoutineResolution.Resolved} — the name resolved to a table-valued function and its
     *       convenience method was found.</li>
     *   <li>{@link RoutineResolution.NotInCatalog} — the name resolves to no table at all (covers the
     *       deferred scalar-read / procedure-write forks, which jOOQ does not place in
     *       {@code getTables()}, so they reject here rather than throwing at emit).</li>
     *   <li>{@link RoutineResolution.NotATableValuedFunction} — the name resolves to a catalog object
     *       that is not a function (a plain table / view).</li>
     *   <li>{@link RoutineResolution.NoConvenienceMethod} — function table found, but no table-form
     *       method on the generated {@code Routines} class (degenerate codegen).</li>
     * </ul>
     */
    public RoutineResolution resolveTableValuedFunction(String routineName) {
        var resolution = findTable(routineName);
        if (!(resolution instanceof TableResolution.Resolved r)) {
            return new RoutineResolution.NotInCatalog();
        }
        var entry = r.entry();
        var table = entry.table();
        if (!table.getOptions().type().isFunction()) {
            return new RoutineResolution.NotATableValuedFunction();
        }

        String schemaPackage = table.getSchema().getClass().getPackageName();
        Class<?> routinesClass;
        try {
            routinesClass = Class.forName(schemaPackage + ".Routines", true, codegenLoader);
        } catch (ClassNotFoundException e) {
            return new RoutineResolution.NoConvenienceMethod(
                "no generated Routines class at " + schemaPackage + ".Routines");
        }

        // Pick the table-form convenience overload: returns the function table class, with value
        // parameters (not org.jooq.Field expressions). The Field overload and the Result<...> execute
        // form are skipped by these two filters.
        var candidate = Arrays.stream(routinesClass.getMethods())
            .filter(m -> m.getReturnType() == table.getClass())
            .filter(m -> Arrays.stream(m.getParameterTypes())
                .noneMatch(org.jooq.Field.class::isAssignableFrom))
            .findFirst();
        if (candidate.isEmpty()) {
            return new RoutineResolution.NoConvenienceMethod(
                "no table-form convenience method returning " + table.getClass().getName()
                + " on " + routinesClass.getName());
        }

        var method = candidate.get();
        var params = Arrays.stream(method.getParameters())
            .map(p -> new RoutineParam(p.getName(), TypeName.get(p.getType())))
            .toList();
        return new RoutineResolution.Resolved(
            ClassName.get(routinesClass), method.getName(), params, entry.toTableRef(routineName));
    }

    /**
     * Enumerate every table in the catalog as a {@link TableEntry}, in schema-then-table order
     * (the generated {@code Tables} class field order within each schema). Used by the R362
     * {@code CatalogFacts} build pass, which walks every table once while the codegen loader is
     * open and reduces each to resolved-immutable facts. Returns the live {@link Table} handles;
     * callers must consume them within the same build pass and never retain them past the loader's
     * lifetime.
     */
    public List<TableEntry> allTableEntries() {
        if (catalog == null) return List.of();
        return catalog.schemaStream().flatMap(this::entriesIn).toList();
    }

    private List<TableEntry> findUnqualifiedTable(String unqualifiedSqlName) {
        if (catalog == null) return List.of();
        return catalog.schemaStream()
            .flatMap(s -> entriesIn(s)
                .filter(e -> e.table().getName().equalsIgnoreCase(unqualifiedSqlName)))
            .toList();
    }

    private java.util.stream.Stream<TableEntry> entriesIn(Schema schema) {
        return tablesClass(schema).stream()
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f), this));
    }

    /**
     * Parses a {@code @table(name:)} directive value into schema and table components.
     * Splits on the first {@code .}: {@code "public.film"} → schema {@code "public"}, table
     * {@code "film"}; {@code "film"} → schema empty, table {@code "film"}.
     *
     * <p>Returns empty for malformed input: {@code null}, blank, {@code "film."}, or
     * {@code ".film"}. Both halves must be non-empty when a {@code .} is present;
     * quoted-identifier syntax with literal dots ({@code "my.schema"."weird.table"}) is out
     * of scope and parses as malformed (the unmatched quote leaves a blank half).
     *
     * <p>Inputs with multiple dots ({@code "a.b.c"}) parse as schema {@code "a"}, table
     * {@code "b.c"}; the resulting two-arg lookup will return empty because PostgreSQL
     * identifiers can't contain unquoted dots, so the classifier routes such values to
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType}.
     */
    public static Optional<QualifiedTableName> parseQualifiedTableName(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        int dotIdx = value.indexOf('.');
        if (dotIdx < 0) {
            return Optional.of(new QualifiedTableName(Optional.empty(), value));
        }
        String schema = value.substring(0, dotIdx);
        String table = value.substring(dotIdx + 1);
        if (schema.isBlank() || table.isBlank()) return Optional.empty();
        return Optional.of(new QualifiedTableName(Optional.of(schema), table));
    }

    /**
     * A {@code @table(name:)} directive value parsed into its (optional schema, table) pair.
     * {@code schema} is empty for unqualified values; both halves are case-preserved from the
     * input so error messages can echo what the user wrote.
     */
    public record QualifiedTableName(Optional<String> schema, String table) {
        public boolean isQualified() {
            return schema.isPresent();
        }
    }

    /**
     * Returns the SQL names of all tables known to the catalog, in the order they appear in the
     * generated {@code Tables} class. Used to build candidate hints in error messages.
     */
    public java.util.List<String> allTableSqlNames() {
        if (catalog == null) return java.util.List.of();
        return catalog.schemaStream()
            .flatMap(schema -> tablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> ((Table<?>) fieldValue(f)).getName())
            .toList();
    }

    /**
     * Returns the SQL constraint names of all foreign keys known to the catalog.
     * Used to build candidate hints when a {@code @reference(key:)} name cannot be resolved.
     */
    public java.util.List<String> allForeignKeySqlNames() {
        if (catalog == null) return java.util.List.of();
        return catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .map(fk -> fk.getName())
            .toList();
    }

    /**
     * Returns the jOOQ-generated Java constant names (the {@code TABLE__CONSTRAINT} field names in
     * each schema's {@code Keys} class) of all foreign keys known to the catalog. Companion to
     * {@link #allForeignKeySqlNames()}: {@link #findForeignKey(String, String)} resolves keys in either
     * namespace, so a candidate hint must be able to render in either to match the form a schema
     * author used in {@code @reference(key:)}.
     */
    public java.util.List<String> allForeignKeyConstantNames() {
        if (catalog == null) return java.util.List.of();
        return catalog.schemaStream()
            .flatMap(schema -> keysClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
            .map(Field::getName)
            .toList();
    }

    /**
     * Returns all foreign keys with an endpoint on {@code tableSqlName}, in either direction (the
     * table is the FK source or the referenced key side; case-insensitive). Used to scope a
     * {@code @reference(key:)} candidate hint to the FKs that are actually valid at a given path
     * position, rather than ranking the author's typo against every FK in the catalog. Returns an
     * empty list when the catalog is unavailable or {@code tableSqlName} is {@code null}.
     */
    @SuppressWarnings("unchecked")
    public List<ForeignKey<?, ?>> foreignKeysTouchingTable(String tableSqlName) {
        if (catalog == null || tableSqlName == null) return List.of();
        return (List<ForeignKey<?, ?>>) (List<?>) catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .filter(fk -> fk.getTable().getName().equalsIgnoreCase(tableSqlName)
                || fk.getKey().getTable().getName().equalsIgnoreCase(tableSqlName))
            .toList();
    }

    /**
     * Returns the jOOQ record class for the table with the given SQL name, or empty when the
     * table cannot be found or the catalog is unavailable. The returned class is whatever
     * {@link org.jooq.Table#getRecordType()} returns for the matching table — typically the
     * generated {@code *Record} class.
     */
    public Optional<Class<?>> findRecordClass(String tableSqlName) {
        return switch (findTable(tableSqlName)) {
            case TableResolution.Resolved r -> Optional.of(r.entry().table().getRecordType());
            case TableResolution.NotInCatalog n -> Optional.empty();
            case TableResolution.Ambiguous a -> Optional.empty();
        };
    }

    /**
     * Find a table by its jOOQ record class. Returns the table whose
     * {@link org.jooq.Table#getRecordType()} equals {@code recordClass}, or empty when no match
     * is found (e.g. the record comes from a different catalog).
     */
    public Optional<TableEntry> findTableByRecordClass(Class<?> recordClass) {
        if (catalog == null) return Optional.empty();
        return catalog.schemaStream()
            .flatMap(schema -> tablesClass(schema).stream())
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> Table.class.isAssignableFrom(f.getType()))
            .map(f -> new TableEntry(f.getName(), (Table<?>) fieldValue(f), this))
            .filter(e -> e.table().getRecordType().equals(recordClass))
            .findFirst();
    }

    /**
     * Scoped, sealed foreign-key lookup by name (R440). Replaces the {@code Optional}-returning
     * {@code findForeignKey(String)}: an {@code Optional} could only collapse a cross-schema
     * constraint-name collision into "not found", erasing exactly the wrong-join hazard this
     * surfaces. The result is a {@link ForeignKeyLookup} so the {@link ForeignKeyLookup.Ambiguous}
     * outcome reaches author-facing call sites as a typed rejection instead of a silent first-hit.
     *
     * <p>Matching keeps the historical dual namespace: first the SQL constraint name
     * (e.g. {@code "film_language_id_fkey"}), then, only if that finds nothing, the jOOQ-generated
     * Java constant name (e.g. {@code "FILM__FILM_LANGUAGE_ID_FKEY"}) via the {@code Keys} class.
     * Both are case-insensitive.
     *
     * <p>{@code sourceSqlName} (nullable) scopes the candidate set. When it is non-null and resolves
     * through {@link #findTable(String)} to a single catalog table, candidates are filtered to the
     * FKs that touch that table (class identity via {@link #foreignKeyTouchesTable}); a cross-schema
     * constraint-name collision then disambiguates naturally to the one FK on the author's table.
     * When {@code sourceSqlName} is {@code null}, does not resolve, or a genuine residual collision
     * survives scoping, more than one distinct FK still matches and {@link ForeignKeyLookup.Ambiguous}
     * is returned naming the colliding schemas.
     */
    @SuppressWarnings("unchecked")
    public ForeignKeyLookup findForeignKey(String name, String sourceSqlName) {
        if (catalog == null) return new ForeignKeyLookup.NotInCatalog();
        List<ForeignKey<?, ?>> bySql = (List<ForeignKey<?, ?>>) (List<?>) catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .filter(fk -> fk.getName().equalsIgnoreCase(name))
            .toList();
        List<ForeignKey<?, ?>> candidates = bySql.isEmpty()
            ? (List<ForeignKey<?, ?>>) (List<?>) catalog.schemaStream()
                .flatMap(schema -> keysClass(schema).stream())
                .flatMap(cls -> Arrays.stream(cls.getFields()))
                .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
                .filter(f -> f.getName().equalsIgnoreCase(name))
                .map(f -> (ForeignKey<?, ?>) fieldValue(f))
                .toList()
            : bySql;
        if (candidates.isEmpty()) return new ForeignKeyLookup.NotInCatalog();
        if (sourceSqlName != null) {
            var scoped = candidates.stream()
                .filter(fk -> foreignKeyTouchesTable(fk, sourceSqlName))
                .toList();
            if (!scoped.isEmpty()) candidates = scoped;
        }
        var distinct = candidates.stream().distinct().toList();
        if (distinct.size() == 1) return new ForeignKeyLookup.Resolved(distinct.get(0));
        List<String> schemas = distinct.stream()
            .map(fk -> fk.getTable().getSchema().getName())
            .distinct()
            .sorted()
            .toList();
        return new ForeignKeyLookup.Ambiguous(schemas);
    }

    /**
     * Returns the Java constant name (e.g. {@code "FK_FILM__FILM_LANGUAGE_ID_FKEY"}) of a foreign
     * key in the generated {@code Keys} class, given the jOOQ {@link ForeignKey} instance
     * (R440: resolved by class identity via {@link #findForeignKeyRef}, not by re-looking-up the
     * bare SQL name). Used at build time to emit {@code Keys.FK_...} references in generated code.
     *
     * <p>Returns empty when the catalog or Keys class is not available (e.g. unit tests that do
     * not depend on jOOQ codegen output) or when the FK is not in the catalog.
     */
    public Optional<String> fkJavaConstantName(ForeignKey<?, ?> fk) {
        return findForeignKeyRef(fk).asRef().map(ForeignKeyRef::constantName);
    }

    /**
     * Resolves a jOOQ {@link ForeignKey} instance into a typed {@link ForeignKeyRef} carrying the
     * schema-correct {@code Keys} {@link ClassName} together with the Java constant name, by
     * <em>reference identity</em> (R440). Scans only the {@code Keys} class of the FK-holder schema
     * (the schema of {@code fk.getTable()}, which structurally pins the owning schema) and matches
     * the constant whose value {@code == fk}. jOOQ's generated {@link ForeignKey#getReferences()}
     * returns the same {@code Keys.FK_*} singletons, so identity holds for every FK that flows out
     * of the catalog; that singleton assumption is an invariant whose named enforcer is
     * {@code JooqCatalogMultiSchemaTest.findForeignKeyRef_*}. No name matching anywhere, so a
     * constraint name colliding across schemas cannot mis-resolve.
     *
     * <p>Replaces per-emit-site {@code ClassName.get(jooqPackage, "Keys")} concatenation: the keys
     * class is read from the live {@link Class} of the matching FK constant, so multi-schema
     * codegen layouts produce schema-segmented FQNs (e.g. {@code multischema_a.Keys}) without any
     * caller-side derivation.
     *
     * <p>{@link ForeignKeyResolution.Resolved} carries the {@link ForeignKeyRef} when the FK is
     * found; {@link ForeignKeyResolution.NotInCatalog} when the constant is absent from the holder
     * schema's {@code Keys} class (a catalog-vs-FK mismatch; defensive).
     */
    public ForeignKeyResolution findForeignKeyRef(ForeignKey<?, ?> fk) {
        if (catalog == null) return new ForeignKeyResolution.NotInCatalog();
        return keysClass(fk.getTable().getSchema()).stream()
            .flatMap(cls -> Arrays.stream(cls.getFields()))
            .filter(f -> ForeignKey.class.isAssignableFrom(f.getType()))
            .filter(f -> fieldValue(f) == fk)
            .map(f -> new ForeignKeyRef(
                fk.getName(),
                ClassName.get(f.getDeclaringClass()),
                f.getName()))
            .findFirst()
            .<ForeignKeyResolution>map(ForeignKeyResolution.Resolved::new)
            .orElseGet(ForeignKeyResolution.NotInCatalog::new);
    }

    /**
     * True when {@code sourceSqlName} names a catalog table that is an endpoint of {@code fk}
     * (either the FK-child side or the referenced-key side). This is the FK source-side membership
     * predicate the {@code @reference} connection check needs: it answers "does this FK touch the
     * table the author is standing on", regardless of orientation.
     *
     * <p>Comparison is by <em>jOOQ table class identity</em> once {@code sourceSqlName} resolves
     * through {@link #findTable(String)} to a single catalog table (schema-aware, case-insensitive).
     * Class identity is schema-unique by construction (each table's generated class is owned by one
     * schema), so a schema-qualified or case-mismatched {@code @table(name:)} echo
     * ({@code "multischema_a.signal"}, {@code "multischema_a.SIGNAL"}) matches the endpoint the bare
     * {@code equalsIgnoreCase} against jOOQ's always-unqualified endpoint name missed, and it also
     * tells {@code multischema_a.signal} apart from a same-named {@code multischema_b.signal}.
     *
     * <p>Fallback: when {@code sourceSqlName} does not resolve to a single class ({@code Ambiguous}
     * or {@code NotInCatalog}), identity cannot fire, so the predicate falls back to the historical
     * bare {@code equalsIgnoreCase} against the endpoint names — preserving today's diagnostic surface
     * for genuinely-unknown or unqualified-ambiguous names.
     */
    public boolean foreignKeyTouchesTable(ForeignKey<?, ?> fk, String sourceSqlName) {
        if (fk == null || sourceSqlName == null) return false;
        Optional<Class<?>> resolved = findTable(sourceSqlName).asEntry().map(e -> e.table().getClass());
        return endpointMatches(fk.getTable(), sourceSqlName, resolved)
            || endpointMatches(fk.getKey().getTable(), sourceSqlName, resolved);
    }

    /**
     * Which end of {@code fk} the source sits on: {@code true} when {@code sourceSqlName} is the
     * FK-child (referencing) side, {@code false} when it is the referenced-key side. This is the FK
     * <em>orientation</em> predicate {@link BuildContext#synthesizeFkJoin} and
     * {@link BuildContext#resolveFkSlots} share to decide join/population direction.
     *
     * <p>Self-referential FKs (both endpoints are the same table, hence the same generated class)
     * cannot be told apart by identity or by name, so the caller's {@code selfRefHint} decides
     * (mirroring the pre-existing {@code selfRefFkOnSource} contract). For non-self-ref FKs the
     * decision is by class identity when {@code sourceSqlName} resolves, and by the historical bare
     * {@code equalsIgnoreCase} against the FK-child endpoint name otherwise. See
     * {@link #foreignKeyTouchesTable} for why identity fixes the schema-qualified / case-mismatched
     * {@code @table} echo the bare compare mis-oriented.
     */
    public boolean foreignKeyOnSource(ForeignKey<?, ?> fk, String sourceSqlName, boolean selfRefHint) {
        if (fk.getTable().getClass() == fk.getKey().getTable().getClass()) {
            return selfRefHint;
        }
        Optional<Class<?>> resolved = findTable(sourceSqlName).asEntry().map(e -> e.table().getClass());
        return endpointMatches(fk.getTable(), sourceSqlName, resolved);
    }

    /**
     * True when {@code endpoint} denotes the table named by {@code sqlName}: by jOOQ table class
     * identity when {@code resolvedClass} is present (the source resolved to a single catalog table),
     * otherwise by the historical bare case-insensitive endpoint-name compare (the source did not
     * resolve). Shared by {@link #foreignKeyTouchesTable}, {@link #foreignKeyOnSource}, and
     * {@link #findForeignKeysBetweenTables} so the resolve-to-identity-or-fall-back-to-name contract
     * lives in exactly one place.
     */
    private static boolean endpointMatches(Table<?> endpoint, String sqlName, Optional<Class<?>> resolvedClass) {
        return resolvedClass
            .map(c -> endpoint.getClass() == c)
            .orElseGet(() -> endpoint.getName().equalsIgnoreCase(sqlName));
    }

    /**
     * Returns all foreign keys that connect {@code tableA} and {@code tableB}, in either direction.
     * A FK is included when one endpoint is {@code tableA} and the other is {@code tableB}.
     * Used to resolve {@code @reference(path: [{table: "..."}])} elements when no explicit FK name
     * is given, and the empty-path FK inference.
     *
     * <p>Each argument is resolved through {@link #findTable(String)} and matched by jOOQ table
     * class identity when it resolves to a single catalog table; a non-resolving argument
     * ({@code Ambiguous} / {@code NotInCatalog}) falls back to the historical bare
     * {@code equalsIgnoreCase} against the endpoint names. Identity lets a schema-qualified or
     * case-mismatched {@code @table} echo match, and distinguishes same-named tables in different
     * schemas; see {@link #foreignKeyTouchesTable}.
     */
    @SuppressWarnings("unchecked")
    public List<ForeignKey<?, ?>> findForeignKeysBetweenTables(String tableA, String tableB) {
        if (catalog == null) return List.of();
        Optional<Class<?>> classA = findTable(tableA).asEntry().map(e -> e.table().getClass());
        Optional<Class<?>> classB = findTable(tableB).asEntry().map(e -> e.table().getClass());
        return (List<ForeignKey<?, ?>>) (List<?>) catalog.schemaStream()
            .flatMap(schema -> schema.getTables().stream())
            .flatMap(table -> table.getReferences().stream())
            .filter(fk -> {
                Table<?> fkSide  = fk.getTable();
                Table<?> keySide = fk.getKey().getTable();
                return (endpointMatches(fkSide, tableA, classA) && endpointMatches(keySide, tableB, classB))
                    || (endpointMatches(fkSide, tableB, classB) && endpointMatches(keySide, tableA, classA));
            })
            .toList();
    }

    /**
     * Returns the FK object when exactly one outgoing FK from {@code sourceTableSqlName} references
     * {@code targetTableSqlName}; empty otherwise (zero or many). Returns the resolved jOOQ
     * {@link ForeignKey} (R440) rather than its bare constraint name, so the caller can hand it
     * straight to {@link BuildContext#synthesizeFkJoin} by class identity instead of round-tripping
     * through a name re-lookup that would reintroduce cross-schema collision.
     *
     * <p>Directional: only FKs where {@code sourceTableSqlName} is the FK source (not the target)
     * are counted. Uses {@link #findForeignKeysBetweenTables} and filters to the source side via
     * {@link #foreignKeyOnSource} (identity-based, so a schema-qualified source is not silently
     * dropped by the bare endpoint-name compare).
     */
    public Optional<ForeignKey<?, ?>> findUniqueFkToTable(String sourceTableSqlName, String targetTableSqlName) {
        var matches = findForeignKeysBetweenTables(sourceTableSqlName, targetTableSqlName).stream()
            .filter(fk -> foreignKeyOnSource(fk, sourceTableSqlName, /*selfRefHint=*/true))
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /**
     * Builds and caches a field-name → FK-constraint-name lookup map for all outgoing FKs from
     * {@code sourceTableSqlName}. For each FK three lowercase keys are inserted:
     * <ul>
     *   <li>the raw qualifier lowercased ({@code role + targetTable + "_id"}), matching
     *       {@code @field(name:)} values case-insensitively (e.g. {@code "language_id"});</li>
     *   <li>{@code lowerFirst(qualifier).toLowerCase()}, matching bare singular field names
     *       (e.g. {@code "languageid"});</li>
     *   <li>the plural of the above (e.g. {@code "languageids"}), matching bare list field names
     *       like {@code languageIds: [ID!]}.</li>
     * </ul>
     * The shim arm looks up {@code columnName.toLowerCase()} so SDL case differences never miss.
     * The map is cached; if two FKs on the same source table produce a colliding key (implying
     * duplicate methods in the generated record class) {@link IllegalStateException} is thrown.
     * Returns an empty map when the catalog is unavailable or the table has no outgoing FKs.
     */
    public Map<String, String> buildQualifierMap(String sourceTableSqlName) {
        return qualifierMapCache.computeIfAbsent(sourceTableSqlName, this::doBuildQualifierMap);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> doBuildQualifierMap(String sourceTableSqlName) {
        var tableEntry = findTable(sourceTableSqlName).asEntry();
        if (tableEntry.isEmpty()) return Map.of();
        var result = new HashMap<String, String>();
        for (var fk : (List<ForeignKey<?, ?>>) (List<?>) tableEntry.get().table().getReferences()) {
            var qualifier = localGetQualifier(fk);
            var raw = (generateRolePrefix(fk) + fk.getKey().getTable().getName() + "_id").toLowerCase();
            var camel = lowerFirst(qualifier).toLowerCase();
            for (var key : List.of(raw, camel, camel + "s")) {
                var prev = result.put(key, fk.getName());
                if (prev != null && !prev.equals(fk.getName())) {
                    throw new IllegalStateException(
                        "FK qualifier map collision on key '" + key + "' for table '"
                        + sourceTableSqlName + "': FKs '" + prev + "' and '" + fk.getName()
                        + "' produce the same map key.");
                }
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Returns the UpperCamelCase qualifier that {@code KjerneJooqGenerator.getQualifier} would
     * produce for the FK named {@code fkName} on {@code sourceTableSqlName}
     * (e.g. {@code "LanguageId"} for {@code film_language_id_fkey} on {@code film}).
     * Empty when the FK is not found or does not originate from {@code sourceTableSqlName}.
     */
    public Optional<String> qualifierForFk(String sourceTableSqlName, String fkName) {
        // R440: scope the lookup by the source table so a constraint name colliding across schemas
        // resolves to this table's FK instead of the first-hit wrong schema. Keeps the Optional
        // contract (non-author-facing): NotInCatalog / Ambiguous both map to empty; scoping makes
        // the collision resolve, so callers' "unreachable" guards stay genuine can't-happens.
        if (!(findForeignKey(fkName, sourceTableSqlName) instanceof ForeignKeyLookup.Resolved r)) {
            return Optional.empty();
        }
        return Optional.of(r.fk())
            .filter(fk -> foreignKeyOnSource(fk, sourceTableSqlName, /*selfRefHint=*/true))
            .map(JooqCatalog::localGetQualifier);
    }

    private Optional<Class<?>> keysClass(Schema schema) {
        try {
            return Optional.of(Class.forName(schema.getClass().getPackageName() + ".Keys", false, codegenLoader));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Return the columns of the named index on a table, in index-field order. Each column is
     * resolved via {@link #findColumn(Table, String)}. Returns empty when the index is not found
     * in the table or when any column cannot be resolved.
     */
    public Optional<java.util.List<ColumnEntry>> findIndexColumns(String tableSqlName, String indexName) {
        return findTable(tableSqlName).asEntry().flatMap(te ->
            te.table().getIndexes().stream()
                .filter(idx -> idx.getName().equalsIgnoreCase(indexName))
                .findFirst()
                .map(idx -> idx.getFields().stream()
                    .map(sf -> findColumn(te.table(), sf.getName()))
                    .<ColumnEntry>flatMap(Optional::stream)
                    .toList()));
    }

    /**
     * Return the primary-key columns of a table in key-field order, or an empty list when the
     * table has no primary key or cannot be found. Each column is resolved via
     * {@link #findColumn(Table, String)}.
     */
    public java.util.List<ColumnEntry> findPkColumns(String tableSqlName) {
        return findTable(tableSqlName).asEntry().map(te -> {
            var pk = te.table().getPrimaryKey();
            if (pk == null) return java.util.List.<ColumnEntry>of();
            return pk.getFields().stream()
                .map(f -> findColumn(te.table(), f.getName()))
                .flatMap(Optional::stream)
                .toList();
        }).orElse(java.util.List.of());
    }

    /**
     * R246 — a candidate row-identifying key on a table: the primary key or a unique key, with its
     * columns resolved in key-declaration order. {@code primary} distinguishes the PK from a unique
     * key (the walker prefers the PK when both are covered). {@code keyName} echoes jOOQ's
     * {@code Key.getName()} for diagnostics.
     */
    public record KeyEntry(boolean primary, String keyName, java.util.List<ColumnEntry> columns) {
        public KeyEntry { columns = java.util.List.copyOf(columns); }
    }

    /**
     * Enumerate the table's row-identifying candidate keys for the R246 UPDATE PK-or-UK match: the
     * primary key first (when present), then every unique key in jOOQ declaration order, deduplicated
     * on column set so a unique key coinciding with the PK is not listed twice. Reads
     * {@code Table.getPrimaryKey()} and {@code Table.getKeys()} (jOOQ's {@code getKeys()} returns the
     * table's unique keys, PK included). Returns an empty list when the table cannot be found or has
     * no primary key and no unique key (the degenerate {@code NoUniqueKeyCoverage} case).
     */
    public java.util.List<KeyEntry> candidateKeys(String tableSqlName) {
        return findTable(tableSqlName).asEntry()
            .map(te -> candidateKeys(te.table()))
            .orElse(java.util.List.of());
    }

    /**
     * Table-scoped overload of {@link #candidateKeys(String)}: enumerates a resolved table's
     * row-identifying candidate keys directly, without a (potentially ambiguous) SQL-name lookup.
     * Used by the R362 {@code CatalogFacts} build pass, which already holds the live {@link Table}.
     */
    public java.util.List<KeyEntry> candidateKeys(Table<?> table) {
        var out = new ArrayList<KeyEntry>();
        var seenColumnSets = new java.util.HashSet<java.util.Set<String>>();
        var pk = table.getPrimaryKey();
        if (pk != null) {
            var cols = resolveKeyColumns(table, pk);
            if (seenColumnSets.add(sqlNameSet(cols))) {
                out.add(new KeyEntry(true, pk.getName(), cols));
            }
        }
        for (org.jooq.UniqueKey<?> uk : table.getKeys()) {
            var cols = resolveKeyColumns(table, uk);
            if (cols.isEmpty()) continue;
            if (seenColumnSets.add(sqlNameSet(cols))) {
                out.add(new KeyEntry(false, uk.getName(), cols));
            }
        }
        return java.util.List.copyOf(out);
    }

    private java.util.List<ColumnEntry> resolveKeyColumns(Table<?> table, org.jooq.UniqueKey<?> key) {
        return key.getFields().stream()
            .map(f -> findColumn(table, f.getName()))
            .flatMap(Optional::stream)
            .toList();
    }

    private static java.util.Set<String> sqlNameSet(java.util.List<ColumnEntry> columns) {
        return columns.stream().map(ColumnEntry::sqlName).collect(Collectors.toSet());
    }

    /**
     * Probe for KjerneJooqGenerator's node-identity metadata on the table class with the given
     * SQL name. Reads two static fields via reflection:
     *
     * <ul>
     *   <li>{@code public static final String __NODE_TYPE_ID} — the {@code typeId} that identifies
     *       the node type in encoded IDs (same value a consumer would write as
     *       {@code @node(typeId:)} in SDL for the same table).</li>
     *   <li>{@code public static final Field<?>[] __NODE_KEY_COLUMNS} — the underlying
     *       {@link org.jooq.Field} references in positional order. Each is resolved to a
     *       {@link ColumnRef} via the table's column set; any unresolved entry fails the probe.</li>
     * </ul>
     *
     * <p>Returns {@link Optional#empty()} when:
     * <ul>
     *   <li>the catalog is unavailable or the table cannot be found;</li>
     *   <li>either constant is absent on the table class;</li>
     *   <li>the constants fail the sanity checks: {@code __NODE_TYPE_ID} non-null and non-empty,
     *       {@code __NODE_KEY_COLUMNS} non-null, non-empty, and every entry resolvable to a column
     *       on the same table. Malformed metadata logs a warning keyed on the table SQL name; the
     *       classifier boundary surfaces this as an {@code UnclassifiedType} once the probe is
     *       consumed there.</li>
     * </ul>
     *
     * <p>Consumer-side: {@code NodeIdStrategy.createId(typeId, keyFields)} and
     * {@code NodeIdStrategy.hasIds(typeId, ids, keyFields)} both depend on the positional order of
     * {@code __NODE_KEY_COLUMNS} — reordering between releases would re-encode new IDs in a different
     * order than decoded IDs produced pre-upgrade, and {@code hasIds} would fail to match. The
     * probe treats the order as opaque but stable.
     */
    public Optional<NodeIdMetadata> nodeIdMetadata(String tableSqlName) {
        return lookup(tableSqlName) instanceof NodeIdMetadataLookup.Present p
            ? Optional.of(p.metadata())
            : Optional.empty();
    }

    /**
     * Sibling of {@link #nodeIdMetadata(String)} that surfaces malformed-metadata reasons at the
     * classifier boundary. Returns the reason string (without the {@code "KjerneJooqGenerator
     * metadata on table 'X' is malformed: "} prefix — the caller prepends it) when the constants
     * are present but fail validation; empty when the constants are absent or well-formed.
     *
     * <p>Classifier usage: call this first — if present, fail the type as {@code UnclassifiedType}
     * with the prefixed message; otherwise call {@link #nodeIdMetadata(String)} to get the
     * synthesized values. Results share a cache so reflection runs once per table per build.
     */
    public Optional<String> nodeIdMetadataDiagnostic(String tableSqlName) {
        return lookup(tableSqlName) instanceof NodeIdMetadataLookup.Malformed m
            ? Optional.of(m.reason())
            : Optional.empty();
    }

    private NodeIdMetadataLookup lookup(String tableSqlName) {
        return metadataCache.computeIfAbsent(tableSqlName, this::doLookup);
    }

    private NodeIdMetadataLookup doLookup(String tableSqlName) {
        return findTable(tableSqlName).asEntry().<NodeIdMetadataLookup>map(te -> {
            Class<?> tableClass = te.table().getClass();
            Object typeIdRaw;
            Object keyColumnsRaw;
            try {
                typeIdRaw = tableClass.getField("__NODE_TYPE_ID").get(null);
                keyColumnsRaw = tableClass.getField("__NODE_KEY_COLUMNS").get(null);
            } catch (NoSuchFieldException e) {
                return new NodeIdMetadataLookup.Absent();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            return validateLookup(tableSqlName, typeIdRaw, keyColumnsRaw,
                name -> findColumn(te.table(), name));
        }).orElseGet(NodeIdMetadataLookup.Absent::new);
    }

    /**
     * Validation half, factored so tests can exercise each malformed-metadata branch by passing
     * synthetic raw values without having to swap {@code static final} fields on a real table
     * class. Preserved signature — wraps the three-state {@link #validateLookup}.
     */
    static Optional<NodeIdMetadata> validateNodeIdMetadata(
            String tableSqlName,
            Object typeIdRaw,
            Object keyColumnsRaw,
            java.util.function.Function<String, Optional<ColumnEntry>> columnLookup) {
        return validateLookup(tableSqlName, typeIdRaw, keyColumnsRaw, columnLookup)
                instanceof NodeIdMetadataLookup.Present p
            ? Optional.of(p.metadata())
            : Optional.empty();
    }

    private static NodeIdMetadataLookup validateLookup(
            String tableSqlName,
            Object typeIdRaw,
            Object keyColumnsRaw,
            java.util.function.Function<String, Optional<ColumnEntry>> columnLookup) {
        if (!(typeIdRaw instanceof String typeId) || typeId.isEmpty()) {
            return malformed(tableSqlName,
                "__NODE_TYPE_ID must be a non-empty String (got: " + typeIdRaw + ")");
        }
        if (!(keyColumnsRaw instanceof org.jooq.Field<?>[] keyColumnFields) || keyColumnFields.length == 0) {
            return malformed(tableSqlName,
                "__NODE_KEY_COLUMNS must be a non-empty Field<?>[] (got: " + keyColumnsRaw + ")");
        }
        var resolved = new ArrayList<ColumnRef>(keyColumnFields.length);
        for (int i = 0; i < keyColumnFields.length; i++) {
            var f = keyColumnFields[i];
            if (f == null) {
                return malformed(tableSqlName, "__NODE_KEY_COLUMNS[" + i + "] is null");
            }
            Optional<ColumnEntry> col = columnLookup.apply(f.getName());
            if (col.isEmpty()) {
                return malformed(tableSqlName,
                    "__NODE_KEY_COLUMNS[" + i + "] references column '" + f.getName()
                    + "' which does not belong to this table");
            }
            var e = col.get();
            resolved.add(new ColumnRef(e.sqlName(), e.javaName(), e.columnClass()));
        }
        return new NodeIdMetadataLookup.Present(new NodeIdMetadata(typeId, List.copyOf(resolved)));
    }

    private static NodeIdMetadataLookup.Malformed malformed(String tableSqlName, String reason) {
        LOGGER.warn("KjerneJooqGenerator metadata on table '{}' is malformed: {}", tableSqlName, reason);
        return new NodeIdMetadataLookup.Malformed(reason);
    }

    /**
     * Resolves a single column on a table to its fully-resolved {@link ColumnRef} (SQL name,
     * Java field name, column class), matching the column SQL name case-insensitively. Empty
     * when the table or the column cannot be found.
     */
    public Optional<ColumnRef> resolveColumn(String tableSqlName, String columnSqlName) {
        return findTable(tableSqlName).asEntry()
            .flatMap(te -> te.allColumnRefs().stream()
                .filter(c -> c.sqlName().equalsIgnoreCase(columnSqlName))
                .findFirst());
    }

    /**
     * Returns all SQL column names for the given table, in the order they appear in the generated
     * jOOQ table class. Returns an empty list when the table cannot be found.
     */
    public java.util.List<String> columnSqlNamesOf(String tableSqlName) {
        return findTable(tableSqlName).asEntry()
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> ((org.jooq.Field<?>) instanceFieldValue(f, te.table())).getName())
                .toList())
            .orElse(java.util.List.of());
    }

    /**
     * Returns all Java field names for the given table, in the order they appear in the generated
     * jOOQ table class. Use this for error hints where the schema author is expected to supply
     * a Java field name (e.g. {@code @field(name: "FILM_ID")}). Returns an empty list when the
     * table cannot be found.
     */
    public java.util.List<String> columnJavaNamesOf(String tableSqlName) {
        return findTable(tableSqlName).asEntry()
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> f.getName())
                .toList())
            .orElse(java.util.List.of());
    }

    /**
     * Returns all columns for the given table, in the order they appear in the generated jOOQ
     * table class. Each entry includes the Java field name, fully qualified column type, SQL name,
     * and nullability. Returns an empty list when the table cannot be found.
     */
    public java.util.List<ColumnEntry> allColumnsOf(String tableSqlName) {
        return findTable(tableSqlName).asEntry()
            .map(te -> Arrays.stream(te.table().getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> {
                    var col = (org.jooq.Field<?>) instanceFieldValue(f, te.table());
                    return new ColumnEntry(f.getName(), col.getType().getName(), col.getName(), col.getDataType().nullable());
                })
                .toList())
            .orElse(java.util.List.of());
    }

    /**
     * R362 — full column facts for the catalog-discovery projection: adds the SQL data-type name
     * ({@code DataType.getTypeName()}) and the column comment ({@code Field.getComment()}) to the
     * {@link ColumnEntry} shape. Reads the same reflective field set {@link #allColumnsOf(String)}
     * does, so column order matches. The comment is empty when jOOQ codegen captured none. All
     * values are resolved-immutable, safe to retain past the codegen loader's lifetime.
     */
    public java.util.List<ColumnFacts> columnFactsOf(Table<?> table) {
        return Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .map(f -> {
                var col = (org.jooq.Field<?>) instanceFieldValue(f, table);
                String comment = col.getComment();
                return new ColumnFacts(
                    col.getName(),
                    f.getName(),
                    col.getDataType().getTypeName(),
                    col.getDataType().nullable(),
                    comment == null ? "" : comment);
            })
            .toList();
    }

    /**
     * R362 — every index on a table as a resolved-immutable (name, SQL-column-names) pair, in index
     * declaration order with columns in index-field order. Each index column is resolved through
     * {@link #findColumn(Table, String)} so the SQL name is canonical, falling back to the raw
     * sort-field name when (degenerately) unresolvable.
     */
    public java.util.List<IndexFacts> indexFactsOf(Table<?> table) {
        return table.getIndexes().stream()
            .map(idx -> new IndexFacts(
                idx.getName(),
                idx.getFields().stream()
                    .map(sf -> findColumn(table, sf.getName()).map(ColumnEntry::sqlName).orElse(sf.getName()))
                    .toList()))
            .toList();
    }

    /**
     * R362 — the outgoing foreign keys of a table reduced to resolved-immutable facts: the SQL
     * constraint name, the schema-qualified source and target table IDs, and the source / target
     * column SQL-name lists. Unlike {@link ForeignKeyRef} (constraint name + {@code Keys}
     * {@link ClassName} + constant, no column pairs), this carries the column pairs the
     * {@code catalog.describe} wire shape promises, pulled from the live {@link ForeignKey} during
     * the build pass and reduced to {@link String} immediately. The build pass groups these
     * catalog-wide to derive each table's incoming edges.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<ForeignKeyFacts> foreignKeyFactsOf(Table<?> table) {
        return ((List<ForeignKey<?, ?>>) (List<?>) table.getReferences()).stream()
            .map(fk -> new ForeignKeyFacts(
                fk.getName(),
                qualifiedName(fk.getTable()),
                qualifiedName(fk.getKey().getTable()),
                fk.getFields().stream().map(org.jooq.Field::getName).toList(),
                fk.getKey().getFields().stream().map(org.jooq.Field::getName).toList()))
            .toList();
    }

    private static String qualifiedName(Table<?> table) {
        return table.getSchema().getName() + "." + table.getName();
    }

    /**
     * R362 — a column's full discovery facts: SQL name, jOOQ Java field name, SQL data-type name,
     * nullability, and comment (empty when codegen captured none). The resolved-immutable superset
     * of {@link ColumnEntry} the {@code CatalogFacts} projection needs; see {@link #columnFactsOf}.
     */
    public record ColumnFacts(String sqlName, String javaName, String sqlType, boolean nullable, String comment) {}

    /**
     * R362 — an index's name and its SQL column names in index order. See {@link #indexFactsOf}.
     */
    public record IndexFacts(String name, java.util.List<String> columns) {
        public IndexFacts { columns = java.util.List.copyOf(columns); }
    }

    /**
     * R362 — a foreign key reduced to resolved-immutable facts: SQL constraint name, schema-qualified
     * source / target table IDs, and the source / target column SQL-name lists. See
     * {@link #foreignKeyFactsOf}.
     */
    public record ForeignKeyFacts(
        String constraintName,
        String sourceTable,
        String targetTable,
        java.util.List<String> columns,
        java.util.List<String> targetColumns
    ) {
        public ForeignKeyFacts {
            columns = java.util.List.copyOf(columns);
            targetColumns = java.util.List.copyOf(targetColumns);
        }
    }

    /**
     * Find a column in a table by either its Java field name or its SQL name. Java name is tried
     * first (case-insensitive), then SQL name (case-insensitive). Directive values in GraphQL
     * schemas may be either convention; trying Java name first handles custom jOOQ naming
     * strategies where {@code javaName} is not a simple {@code toUpperCase(sqlName)}.
     */
    public Optional<ColumnEntry> findColumn(Table<?> table, String columnName) {
        var entries = Arrays.stream(table.getClass().getFields())
            .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
            .map(f -> {
                var col = (org.jooq.Field<?>) instanceFieldValue(f, table);
                return new ColumnEntry(f.getName(), col.getType().getName(), col.getName(), col.getDataType().nullable());
            })
            .toList();
        return entries.stream().filter(e -> columnName.equalsIgnoreCase(e.javaName())).findFirst()
            .or(() -> entries.stream().filter(e -> columnName.equalsIgnoreCase(e.sqlName())).findFirst());
    }

    /**
     * Find a column by SQL table name and column name (Java or SQL). Delegates to
     * {@link #findTable(String)} followed by {@link #findColumn(Table, String)}.
     */
    public Optional<ColumnEntry> findColumn(String tableSqlName, String columnName) {
        return findTable(tableSqlName).asEntry()
            .flatMap(e -> findColumn(e.table(), columnName));
    }

    private Optional<Class<?>> tablesClass(Schema schema) {
        try {
            return Optional.of(Class.forName(schema.getClass().getPackageName() + ".Tables", false, codegenLoader));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private static Object fieldValue(Field f) {
        try {
            return f.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object instanceFieldValue(Field f, Object instance) {
        try {
            return f.get(instance);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Catalog loadDefaultCatalog(String generatedJooqPackage, ClassLoader codegenLoader) {
        try {
            // `initialize = true` here: the immediately-following getField("DEFAULT_CATALOG").get(null)
            // triggers static initialization regardless of the flag (JLS §12.4.1), and the catalog
            // value itself depends on those initializers having run. The other three call sites in
            // this class only read class metadata and use `initialize = false`.
            var cls = Class.forName(generatedJooqPackage + ".DefaultCatalog", true, codegenLoader);
            var field = cls.getField("DEFAULT_CATALOG");
            return (Catalog) field.get(null);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("{} did not contain a DefaultCatalog class — catalog lookups will return empty."
                + " This is expected in unit tests; in production it indicates a configuration error.",
                generatedJooqPackage);
            return null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(
                "Could not access " + generatedJooqPackage + ".DefaultCatalog.DEFAULT_CATALOG.", e);
        }
    }

    // --- Qualifier reproduction (KjerneJooqGenerator.getQualifier) ---
    // Keep these helpers in sync with upstream KjerneJooqGenerator. They reproduce the
    // qualifier algorithm so the rewrite can map FK constraint names to has<Q>(s) predicate
    // method names and reverse-map field names back to FK constraints without a runtime
    // dependency on KjerneJooqGenerator itself.

    /**
     * Reproduces {@code KjerneJooqGenerator.generateRoleName}. Returns the role discriminator
     * derived from the last source/target column pair. {@code "HAR"} means the columns are equal
     * (no role prefix needed).
     */
    static String generateRoleName(List<String> sourceColumns, List<String> targetColumns) {
        var last = sourceColumns.size() - 1;
        var src = sourceColumns.get(last);
        var tgt = targetColumns.get(last);
        if (src.equals(tgt))         return "HAR";
        if (src.startsWith(tgt))     return src.substring(tgt.length() + 1);
        if (tgt.startsWith(src))     return tgt.substring(src.length() + 1);
        return src;
    }

    /**
     * Reproduces {@code KjerneJooqGenerator.generateRolePrefix}. Returns {@code ""} when the
     * role is {@code "HAR"} (no distinguishing prefix), otherwise {@code role + "_"}.
     */
    static String generateRolePrefix(ForeignKey<?, ?> fk) {
        var srcCols = fk.getFields().stream().map(f -> f.getName().toLowerCase()).toList();
        var tgtCols = fk.getKey().getFields().stream().map(f -> f.getName().toLowerCase()).toList();
        var role = generateRoleName(srcCols, tgtCols);
        return "HAR".equals(role) ? "" : role + "_";
    }

    /**
     * Reproduces {@code KjerneJooqGenerator.getQualifier}. Returns an UpperCamelCase qualifier
     * derived from the FK's role prefix and target table name, e.g. {@code "LanguageId"} or
     * {@code "OriginalLanguageIdLanguageId"}.
     */
    static String localGetQualifier(ForeignKey<?, ?> fk) {
        var role = generateRolePrefix(fk);
        var raw = (role + fk.getKey().getTable().getName() + "_id").toLowerCase();
        return Arrays.stream(raw.split("_"))
            .map(s -> s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .collect(Collectors.joining());
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A table resolved from the jOOQ catalog: the {@link Table} instance, its Java field name in
     * the schema's {@code Tables} constants class (e.g. {@code "FILM"}), and a back-reference to
     * the catalog used for derived lookups (PK columns, the schema's {@code Tables} class).
     *
     * <p>The accessor methods ({@link #tableClass()}, {@link #recordClass()},
     * {@link #constantsClass()}, {@link #pkColumnRefs()}) read schema-correct values from the
     * resolved {@link Table}, so single-schema and multi-schema catalog layouts produce the
     * same call shape with no per-caller derivation.
     *
     * <p>The catalog reference is intentionally part of the record's identity — within a build
     * all entries come from one {@code JooqCatalog} instance, so its presence in {@code equals}
     * is a no-op for normal use; tests that compare entries across separate catalog instances
     * should compare {@link #table()} or {@link #javaFieldName()} explicitly.
     */
    public record TableEntry(String javaFieldName, Table<?> table, JooqCatalog catalog) {
        /**
         * The {@link ClassName} of the jOOQ-generated table class
         * (e.g. {@code multischema_a.tables.Widget}). Read directly from the live class via
         * reflection, so multi-schema layouts produce schema-segmented FQNs without per-caller
         * derivation.
         */
        public ClassName tableClass() {
            return ClassName.get(table.getClass());
        }

        /**
         * The {@link ClassName} of the jOOQ-generated record class
         * (e.g. {@code multischema_a.tables.records.WidgetRecord}). Sourced from
         * {@link Table#getRecordType()}, which is schema-correct for both single- and
         * multi-schema codegen.
         */
        public ClassName recordClass() {
            return ClassName.get(table.getRecordType());
        }

        /**
         * The {@link ClassName} of the schema's {@code Tables} constants class
         * (e.g. {@code multischema_a.Tables}). The catalog-construction precondition
         * (see {@link JooqCatalog#verifyTablesClassPresent}) guarantees every schema in a live
         * catalog has a generated {@code Tables} class; the {@link Optional} return is retained
         * as a defence-in-depth wrapper around reflection but in practice is always present for
         * any {@code TableEntry} produced by {@link JooqCatalog#findTable}.
         */
        public Optional<ClassName> constantsClass() {
            return catalog.tablesClass(table.getSchema()).map(ClassName::get);
        }

        /**
         * The primary-key columns of this table in key-field order, materialised as
         * {@link ColumnRef}s with the typed Java field names, SQL names, and column-class
         * FQNs. Empty list when the table has no primary key. Same shape as
         * {@link JooqCatalog#findPkColumns(String)} but scoped to this resolved entry without
         * a second catalog lookup.
         */
        public List<ColumnRef> pkColumnRefs() {
            var pk = table.getPrimaryKey();
            if (pk == null) return List.of();
            return pk.getFields().stream()
                .map(f -> catalog.findColumn(table, f.getName()))
                .<ColumnEntry>flatMap(Optional::stream)
                .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()))
                .toList();
        }

        /**
         * Every column on the table as fully-resolved {@link ColumnRef}s, in generated-jOOQ-class
         * declaration order (the same order {@code table.fields()} yields at runtime). Backs
         * {@link no.sikt.graphitron.rewrite.model.TableRef#allColumns()}: R436's typed-record key
         * reconstruction and reserved-alias full-row projection enumerate this at generation time
         * rather than reflecting the catalog at emit time. Mirrors {@link #allColumnsOf(String)}'s
         * reflection but keyed off this entry's already-resolved {@code table()}.
         */
        public List<ColumnRef> allColumnRefs() {
            return Arrays.stream(table.getClass().getFields())
                .filter(f -> org.jooq.Field.class.isAssignableFrom(f.getType()))
                .map(f -> {
                    var col = (org.jooq.Field<?>) instanceFieldValue(f, table);
                    return new ColumnRef(col.getName(), f.getName(), col.getType().getName());
                })
                .toList();
        }

        /**
         * Materialises a {@link no.sikt.graphitron.rewrite.model.TableRef} from this entry. The
         * {@code sqlName} argument is the directive-supplied form (case-preserved for error
         * messages), not {@code entry.table().getName()} — the catalog lookup key is preserved
         * from the user-visible name even when jOOQ canonicalises differently.
         *
         * <p>Non-{@link Optional} return: the catalog-construction precondition
         * ({@link JooqCatalog#verifyTablesClassPresent}) guarantees every {@link TableEntry} produced
         * by {@link JooqCatalog#findTable} has a resolvable {@code Tables} constants class. If the
         * precondition is bypassed (only possible by constructing a {@code TableEntry} outside the
         * normal catalog flow), the resulting {@link IllegalStateException} surfaces as a build
         * failure rather than a silent partial result.
         */
        public no.sikt.graphitron.rewrite.model.TableRef toTableRef(String sqlName) {
            ClassName cc = constantsClass().orElseThrow(() -> new IllegalStateException(
                "TableEntry for table '" + sqlName + "' (schema '" + table.getSchema().getName()
                + "') has no resolvable Tables constants class — catalog-construction precondition"
                + " should have rejected this state at JooqCatalog instantiation."));
            return new no.sikt.graphitron.rewrite.model.TableRef(
                sqlName,
                javaFieldName,
                tableClass(),
                recordClass(),
                cc,
                pkColumnRefs(),
                allColumnRefs());
        }
    }

    /**
     * Node-identity metadata read from a table class by {@link #nodeIdMetadata(String)}.
     *
     * <p>{@code typeId} is the {@code __NODE_TYPE_ID} constant. {@code keyColumns} is the positional
     * resolution of {@code __NODE_KEY_COLUMNS} to {@link ColumnRef}s on the same table.
     */
    public record NodeIdMetadata(String typeId, List<ColumnRef> keyColumns) {}

    /**
     * Three-state outcome of a {@link #nodeIdMetadata} reflection probe. {@link Absent} means the
     * table class has no {@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} constants (legal for
     * non-NodeId tables). {@link Present} carries the validated metadata. {@link Malformed}
     * carries a human-readable reason suitable for a classifier-boundary diagnostic; the caller
     * prepends the {@code "KjerneJooqGenerator metadata on table 'X' is malformed: "} prefix.
     *
     * <p>Not part of the primary public API — callers use {@link #nodeIdMetadata(String)} and
     * {@link #nodeIdMetadataDiagnostic(String)}. The sum type itself is public so tests and
     * future classifier helpers can switch over the three cases directly.
     */
    public sealed interface NodeIdMetadataLookup {
        record Present(NodeIdMetadata metadata) implements NodeIdMetadataLookup {}
        record Absent() implements NodeIdMetadataLookup {}
        record Malformed(String reason) implements NodeIdMetadataLookup {}
    }

    /**
     * Column resolution result.
     *
     * <p>{@code javaName} is the Java field name in the generated jOOQ table class
     * (e.g. {@code "FILM_ID"}). {@code columnClass} is the fully qualified Java type name of the
     * column (e.g. {@code "java.lang.Long"}). {@code sqlName} is the SQL column name used
     * internally for filtering; it is not exposed beyond the catalog. {@code nullable} reflects
     * the column's nullability as declared in the jOOQ data type.
     */
    public record ColumnEntry(String javaName, String columnClass, String sqlName, boolean nullable) {}

    /**
     * Sub-taxonomy of outcomes for {@link #findTable(String)}. Lookup failures fan out into
     * {@link NotInCatalog} (the name resolves nowhere) and {@link Ambiguous} (the unqualified
     * name resolves in two or more schemas). Diagnostic builders switch on the variant directly
     * so each failure mode reaches the schema author with the right prose; callers that only
     * care about the resolved entry use {@link #asEntry()}.
     */
    public sealed interface TableResolution {
        /** The name resolves to exactly one table in the catalog. */
        record Resolved(TableEntry entry) implements TableResolution {}

        /** No schema in the catalog carries this table name. */
        record NotInCatalog() implements TableResolution {}

        /**
         * Two or more schemas carry this (unqualified) table name. {@code schemas} carries the
         * candidate schema names so the diagnostic builder can suggest qualified forms without
         * a second catalog query. Qualified lookups never produce this variant.
         */
        record Ambiguous(List<String> schemas) implements TableResolution {
            public Ambiguous {
                schemas = List.copyOf(schemas);
            }
        }

        /**
         * Project to {@link Optional}{@code <TableEntry>} for callers that route every failure
         * variant identically (e.g. internal pipeline lookups that already surface their own
         * diagnostic). Diagnostic-bearing callers must switch on the variant instead.
         */
        default Optional<TableEntry> asEntry() {
            return this instanceof Resolved r ? Optional.of(r.entry()) : Optional.empty();
        }
    }

    /**
     * One routine IN parameter, in declaration order: the jOOQ-generated (camelCased) parameter
     * name and its boxed Java type. Read off the table-form convenience method on the generated
     * {@code Routines} class by {@link #resolveTableValuedFunction(String)}.
     */
    public record RoutineParam(String name, TypeName type) {}

    /**
     * Outcome of {@link #resolveTableValuedFunction(String)}. {@link Resolved} carries the call
     * surface ({@code Routines} class + method + ordered params) and the routine-result
     * {@link TableRef}; the three failure arms map to the typed validate-time rejections the
     * {@code RoutineDirectiveResolver} surfaces.
     */
    public sealed interface RoutineResolution {
        record Resolved(ClassName routinesClass, String methodName, List<RoutineParam> params, TableRef resultTable)
            implements RoutineResolution {
            public Resolved { params = List.copyOf(params); }
        }
        record NotInCatalog() implements RoutineResolution {}
        record NotATableValuedFunction() implements RoutineResolution {}
        record NoConvenienceMethod(String detail) implements RoutineResolution {}
    }

    /**
     * Sub-taxonomy of outcomes for {@link #findForeignKeyRef(ForeignKey)}. Symmetric in spirit to
     * {@link TableResolution} with one failure arm: the reference-identity lookup resolves an FK
     * that already flowed out of the catalog, so ambiguity cannot apply. Lets
     * {@link no.sikt.graphitron.rewrite.BuildContext#synthesizeFkJoin} distinguish "endpoint
     * table missing" from "FK not in catalog" instead of conflating both into a single empty.
     */
    public sealed interface ForeignKeyResolution {
        /** The FK instance resolves to exactly one {@code Keys}-class constant in its holder schema. */
        record Resolved(ForeignKeyRef ref) implements ForeignKeyResolution {}

        /** The FK instance is not present in its holder schema's {@code Keys} class (defensive). */
        record NotInCatalog() implements ForeignKeyResolution {}

        /** Project to {@link Optional}{@code <ForeignKeyRef>} for callers that ignore the failure mode. */
        default Optional<ForeignKeyRef> asRef() {
            return this instanceof Resolved r ? Optional.of(r.ref()) : Optional.empty();
        }
    }

    /**
     * Sealed outcome of the scoped name lookup {@link #findForeignKey(String, String)} (R440).
     * A {@code JooqCatalog}-local result type in the same family as {@link TableResolution},
     * {@link ForeignKeyResolution}, and {@link RoutineResolution}. Unlike the retired
     * {@code Optional<ForeignKey>} form, it keeps the raw jOOQ {@link ForeignKey} (a permitted
     * holder inside the catalog boundary) on {@link Resolved} and surfaces {@link Ambiguous} as a
     * first-class arm so a cross-schema constraint-name collision reaches author-facing call sites
     * as a typed rejection instead of a silent first-hit.
     *
     * <p>Not in scope for {@code VariantCoverageTest} (which covers classification leaves) or
     * {@code SealedHierarchyDocCoverageTest} (which walks only the {@code Rejection} hierarchy);
     * documented here by javadoc, in the sibling-result-type convention.
     */
    public sealed interface ForeignKeyLookup {
        /** The name resolves (within scope) to exactly one FK; carries the raw jOOQ instance. */
        record Resolved(ForeignKey<?, ?> fk) implements ForeignKeyLookup {}

        /** No FK matches the name in either the SQL or jOOQ-constant namespace. */
        record NotInCatalog() implements ForeignKeyLookup {}

        /** More than one distinct FK matches after scoping; {@code schemas} names the colliding schemas. */
        record Ambiguous(List<String> schemas) implements ForeignKeyLookup {
            public Ambiguous { schemas = List.copyOf(schemas); }
        }
    }
}
