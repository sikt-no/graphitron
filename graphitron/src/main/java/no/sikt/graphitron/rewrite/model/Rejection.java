package no.sikt.graphitron.rewrite.model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Why a classifier failed to produce a model variant. The arms carry the structural
 * data each rejection class actually has; the validator and any downstream consumer
 * (LSP fix-its, watch-mode formatter) switch on the variant rather than parsing
 * prose.
 *
 * <p>{@link #message()} renders the variant's data as a single human-readable
 * sentence, for the build-time log surface that
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} writes today. Each
 * leaf record overrides it; the interface itself only declares the contract.
 */
public sealed interface Rejection permits Rejection.AuthorError, Rejection.InvalidSchema, Rejection.Deferred {

    String message();

    /**
     * Returns a new {@link Rejection} of the same variant with {@code prefix} prepended to the
     * leaf's user-facing prose ({@code summary} for {@link AuthorError.UnknownName} and
     * {@link Deferred}, {@code reason} otherwise). Used at wrap sites that thread caller-specific
     * context onto a producer's typed rejection ({@code "service method could not be resolved — "},
     * etc.) without collapsing typed components like {@link AuthorError.UnknownName#candidates} back
     * into prose. Each leaf arm switches on its variant.
     */
    Rejection prefixedWith(String prefix);

    /**
     * Author-correctable. Two sub-arms because the data shapes diverge: most
     * AUTHOR_ERROR sites today are structural rule violations carrying just prose;
     * a minority resolve a name against a closed set and carry the lookup attempt
     * + candidates. Each sub-arm's accessors apply uniformly to that arm.
     */
    sealed interface AuthorError extends Rejection permits AuthorError.UnknownName, AuthorError.Structural, AuthorError.AccessorMismatch, AuthorError.RecordBindingMultiProducer, AuthorError.TypeConflict, AuthorError.MultiProducerDomainTypeDisagreement, ServiceMethodCallError, ReflectionError, UpdateRowsError, DeleteRowsError, ErrorChannelWalkerError, WireCoercionError {

        /**
         * The classifier resolved a name (column, table, FK, service method,
         * NodeId key, lifter method, ...) that the catalog or SDL registry did not
         * recognise. {@code summary} is the prose preceding the candidate hint;
         * {@code attempt} is what the author wrote; {@code candidates} is the
         * closed set the catalog had at this site; {@code attemptKind} names the
         * lookup space for downstream tooling (LSP fix-its).
         */
        record UnknownName(
            String summary,
            AttemptKind attemptKind,
            String attempt,
            List<String> candidates
        ) implements AuthorError {
            public UnknownName {
                candidates = List.copyOf(candidates);
            }

            @Override public String message() {
                return summary + candidateHint(attempt, candidates);
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new UnknownName(prefix + summary, attemptKind, attempt, candidates);
            }
        }

        /**
         * Author-correctable structural rule violations that don't resolve a name
         * against a closed set. The majority arm.
         */
        record Structural(String reason) implements AuthorError {
            @Override public String message() { return reason; }

            @Override public Rejection prefixedWith(String prefix) {
                return new Structural(prefix + reason);
            }
        }

        /**
         * The class-backed parent's class doesn't expose an accessor matching the
         * SDL field's name, parameter shape, and return type. Produced by
         * {@link no.sikt.graphitron.rewrite.ClassAccessorResolver}; the {@code reason} carries the
         * resolver's enumeration of candidates tried with rejection cause for each. The
         * {@code @field(name:)} override hint is a property of the diagnostic kind itself, so it
         * attaches in {@link #message()} rather than at the validator's prefixing site.
         */
        record AccessorMismatch(String reason) implements AuthorError {
            @Override public String message() {
                return reason
                    + "\n  Hint: use @field(name: \"…\") to bind this SDL field to a differently-named accessor.";
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new AccessorMismatch(prefix + reason);
            }
        }

        /**
         * Two or more producers reach the same SDL type with disagreeing reflected backing
         * classes. R96's reflection walk grounds at root producers ({@code @service},
         * {@code @table}, {@code @tableMethod}) and extends through parent accessor returns;
         * when the same SDL type accumulates more than one distinct class in its collection
         * set, this rejection surfaces with every disagreeing site listed.
         *
         * <p>Carries the SDL type name plus the typed {@link ProducerBinding} list; downstream
         * tooling switches on the arm rather than parsing prose. The producer-side rejection
         * fires from {@link no.sikt.graphitron.rewrite.RecordBindingResolver}'s per-SDL-type fold when the collection set
         * holds more than one distinct backing class.
         */
        record RecordBindingMultiProducer(String sdlTypeName, List<ProducerBinding> bindings)
                implements AuthorError {
            public RecordBindingMultiProducer {
                bindings = List.copyOf(bindings);
            }

            @Override public String message() {
                var sb = new StringBuilder()
                    .append("type '").append(sdlTypeName)
                    .append("' has disagreeing reflected backing classes from multiple producers:");
                for (ProducerBinding b : bindings) {
                    sb.append("\n  - ").append(b.describe())
                      .append(" → ").append(b.reflectedClass().getName());
                }
                sb.append("\n  Resolve by aligning the producers on a single backing class.");
                return sb.toString();
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new RecordBindingMultiProducer(prefix + sdlTypeName, bindings);
            }
        }

        /**
         * Two or more directive sites reference the same {@code contextArgument} name with
         * mutually-incompatible Java types. R190's per-name agreement walk over every
         * {@link MethodRef.Param.Typed} whose source is {@link ParamSource.Context} keys by
         * parameter name and requires every site to declare the same structural
         * {@link no.sikt.graphitron.javapoet.TypeName}. The factory emitter pastes that single
         * type verbatim into the generated {@code Graphitron.newExecutionInput(...)} parameter
         * list, and the call-site emitter pastes it as the {@code $T.class} literal at the
         * {@code getContextArgument} call: any disagreement would mis-type the generated cast.
         *
         * <p>Carries the contextArgument name plus the typed {@link ConflictSite} list (typed
         * structured data, not prose) so downstream tooling switches on the arm rather than
         * parsing prose. The producer-side rejection fires from
         * {@link no.sikt.graphitron.rewrite.ContextArgumentClassifier} when the per-name fold finds disagreeing
         * {@code TypeName}s across sites.
         */
        record TypeConflict(String contextArgumentName, List<ConflictSite> sites)
                implements AuthorError {
            public TypeConflict {
                sites = List.copyOf(sites);
            }

            @Override public String message() {
                var sb = new StringBuilder()
                    .append("contextArgument '").append(contextArgumentName)
                    .append("' has disagreeing Java types across directive sites:");
                for (ConflictSite cs : sites) {
                    sb.append("\n  - ").append(cs.site().className()).append('.').append(cs.site().methodName())
                      .append(" declared ").append(cs.declared().toString());
                }
                sb.append("\n  Resolve by aligning every directive site that references '")
                  .append(contextArgumentName).append("' on a single Java type.");
                return sb.toString();
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new TypeConflict(prefix + contextArgumentName, sites);
            }
        }

        /**
         * R204: two or more {@link OutputField} producers reach the same SDL return type with
         * disagreeing {@link DomainReturnType} arms. Each producer's emitted resolver puts a
         * different Java value at {@code env.getSource()} for that SDL type's child datafetchers;
         * the generator commits to one Java type per child-field coord at emit time, so whichever
         * producer the runtime invokes second feeds a datafetcher generated for the other's
         * record shape (returning silently wrong data or NPEing on a column the fetcher expects).
         *
         * <p>Carries the SDL return type name plus a typed list of {@link Participant} entries
         * (one per producer in the conflict group); downstream tooling switches on the arm
         * rather than parsing prose. The producer-side rejection fires from
         * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder} when two producers reach the same SDL return type
         * with disagreeing {@link DomainReturnType} arms.
         *
         * <p>The same rejection is attached to every demoted producer in the group, so each
         * surfaces independently in the validator's per-{@link GraphitronField.UnclassifiedField}
         * pass; the message names every participant (including the field itself) so the author
         * can resolve the conflict by aligning any one of them.
         */
        record MultiProducerDomainTypeDisagreement(
            String sdlTypeName,
            List<Participant> participants
        ) implements AuthorError {
            public MultiProducerDomainTypeDisagreement {
                participants = List.copyOf(participants);
            }

            @Override public String message() {
                var sb = new StringBuilder()
                    .append("type '").append(sdlTypeName)
                    .append("' is produced with disagreeing env.getSource() Java domain types:");
                for (Participant p : participants) {
                    sb.append("\n  - ").append(p.parentTypeName()).append('.').append(p.fieldName())
                      .append(" → ").append(p.domainReturnType());
                }
                sb.append("\n  Resolve by aligning the producers on a single env.getSource() Java type.");
                return sb.toString();
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new MultiProducerDomainTypeDisagreement(prefix + sdlTypeName, participants);
            }

            /**
             * One producer in a multi-producer-domain-type-disagreement group. Names the field
             * coord and the {@link DomainReturnType} arm it answers; every participant in the group
             * is listed on the single rejection the validator surfaces so downstream tooling can
             * cross-reference siblings.
             */
            public record Participant(
                String parentTypeName,
                String fieldName,
                DomainReturnType domainReturnType
            ) {}
        }
    }

    /**
     * Structural rule violations the author can't fix without dropping or
     * replacing a directive: "this combination cannot work, period". Two sub-arms
     * by the same logic as {@link AuthorError}: a minority of sites are
     * directive-conflict ("@X conflicts with @Y", "@asConnection on inline
     * (non-@splitQuery) TableField"), the majority are structural rules without a
     * clean directive enumeration ("lookup fields must not return a connection",
     * "result type does not match input cardinality").
     */
    sealed interface InvalidSchema extends Rejection permits InvalidSchema.DirectiveConflict, InvalidSchema.CaseFoldCollision, InvalidSchema.Structural {

        /**
         * Two or more directives co-occur on the same declaration in a combination
         * the classifier rejects, or one directive is rejected because of where it
         * appears. {@code directives} carries the bare directive names (no leading
         * {@code @}) for downstream tooling; {@code reason} is the prose that
         * surfaces on the validator log line.
         */
        record DirectiveConflict(
            List<String> directives,
            String reason
        ) implements InvalidSchema {
            public DirectiveConflict {
                directives = List.copyOf(directives);
            }

            @Override public String message() { return reason; }

            @Override public Rejection prefixedWith(String prefix) {
                return new DirectiveConflict(directives, prefix + reason);
            }
        }

        /**
         * Two or more type-name stems are case-equivalent. Graphitron emits one Java file per
         * type-name stem; on case-insensitive filesystems (APFS, NTFS) the colliding names map to
         * the same filename and clobber each other. Each member of the collision group is demoted
         * to {@link GraphitronType.UnclassifiedType} carrying its own {@code CaseFoldCollision}
         * with this same {@code group} and the demoted member's {@code origin}, so the validator
         * surfaces one {@link no.sikt.graphitron.rewrite.ValidationError} per member from either
         * entry point.
         *
         * <p>{@code group} carries every case-equivalent type name in registry-iteration order;
         * {@code origin} names the classifier arm the demoted member came from; {@code prefix}
         * accumulates wrap-site prose threaded through {@link #prefixedWith(String)} (the
         * validator prepends {@code "Type 'X': "} on every diagnostic). {@link #message()} renders
         * {@code prefix} ahead of the origin-specialised fix hint; the typed {@code group} and
         * {@code origin} survive every {@code prefixedWith} pass so downstream consumers (LSP
         * fix-its, watch-mode formatter) can read the collision group structurally on the
         * validator-projected rejection without re-parsing prose.
         *
         * <p>Producer: {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder} R194 case-fold
         * uniqueness pass.
         */
        record CaseFoldCollision(
            List<String> group,
            Origin origin,
            String prefix
        ) implements InvalidSchema {
            public CaseFoldCollision {
                group = List.copyOf(group);
            }

            @Override public String message() {
                String groupList = group.stream()
                    .map(n -> "'" + n + "'")
                    .collect(Collectors.joining(", "));
                String body = switch (origin) {
                    case SYNTH_CONNECTION ->
                        "synthesised connection type collides case-insensitively with " + groupList
                            + "; rename the source field or set @asConnection(connectionName: \"...\") to a name that is unique under case-folding";
                    case SYNTH_EDGE ->
                        "synthesised edge type collides case-insensitively with " + groupList
                            + "; rename the connection (source field or @asConnection(connectionName: \"...\")) so the derived edge name is unique under case-folding";
                    case SYNTH_PAGE_INFO ->
                        "synthesised PageInfo type collides case-insensitively with " + groupList
                            + "; rename the conflicting SDL type so PageInfo can be synthesised without clash";
                    case SDL ->
                        "collides case-insensitively with " + groupList
                            + "; rename one of the colliding types (case-only differences are not portable across case-insensitive filesystems)";
                };
                return prefix + body;
            }

            @Override public Rejection prefixedWith(String prefix) {
                return new CaseFoldCollision(group, origin, prefix + this.prefix);
            }

            /** Origin arm; identifies the classifier arm the demoted member came from. */
            public enum Origin {
                SDL, SYNTH_CONNECTION, SYNTH_EDGE, SYNTH_PAGE_INFO
            }
        }

        /** Structural-rule majority: just prose. */
        record Structural(String reason) implements InvalidSchema {
            @Override public String message() { return reason; }

            @Override public Rejection prefixedWith(String prefix) {
                return new Structural(prefix + reason);
            }
        }
    }

    /**
     * Recognised but not yet generator-supported. {@code summary} is the prose
     * preceding the optional roadmap-path suffix; {@code planSlug} names the
     * roadmap file under {@code roadmap/} (no extension), or is
     * empty when the deferred message has no associated plan; {@code stubKey}
     * names the variant class or other anchor inside that plan. The render form
     * embeds the plan path verbatim, separator-prefixed by an em-dash; LSP
     * fix-its read the slug as a typed value and offer "open the roadmap item"
     * instead of parsing the path back out.
     */
    record Deferred(
        String summary,
        String planSlug,
        StubKey stubKey
    ) implements Rejection {
        @Override public String message() {
            return planSlug.isEmpty()
                ? summary
                : summary + " — see roadmap/" + planSlug + ".md";
        }

        @Override public Rejection prefixedWith(String prefix) {
            return new Deferred(prefix + summary, planSlug, stubKey);
        }
    }

    /**
     * Lookup-space identifier for {@link AuthorError.UnknownName}. Every space
     * carries the same {@code (attempt, candidates)} shape; the kind is a typed
     * tag for downstream tooling rather than an arm split.
     */
    enum AttemptKind {
        COLUMN, TABLE, FOREIGN_KEY, SERVICE_METHOD, LIFTER_METHOD,
        ENUM_CONSTANT, TYPE_NAME, NODEID_KEY_COLUMN, DML_KIND
    }

    /**
     * Stable identifier for a deferred-stub site. Two arms: a variant-class key
     * (the {@code TypeFetcherGenerator.STUBBED_VARIANTS} key form, used when the generator stubs
     * an entire variant class — or a feature-shape inline-defer with no leaf anchor, signalled by
     * a null {@link VariantClass#fieldClass}) and an emit-block key (a typed enum, used when a
     * variant classifies cleanly but a particular shape inside the emitter doesn't yet emit).
     * Both forms feed the validator's deferred gate via the shared {@link Deferred#message()}
     * renderer. {@link VariantClass#fieldClass} is nullable: validator and emitter consumers
     * treat a null {@code fieldClass} the same way they treat a {@code VariantClass} entry for
     * log output.
     */
    sealed interface StubKey permits StubKey.VariantClass {
        /**
         * Variant-class key. {@code fieldClass} may be {@code null} for inline-defer sites whose
         * rejection names a feature shape ("@service returning a polymorphic type") rather than a
         * stubbed leaf class; downstream tooling that wants to jump to a leaf class checks for
         * non-null first.
         */
        record VariantClass(Class<? extends GraphitronField> fieldClass) implements StubKey {}
    }

    // ===== Factories =====

    /** {@link AuthorError.Structural} factory; the majority shape. */
    static Rejection structural(String reason) {
        return new AuthorError.Structural(reason);
    }

    /**
     * {@link AuthorError.AccessorMismatch} factory. Produced by
     * {@link no.sikt.graphitron.rewrite.ClassAccessorResolver} when a class-backed
     * parent's class doesn't expose an accessor matching the SDL field's name, parameter shape,
     * and return type. {@code reason} is the resolver's enumeration of candidates tried; the
     * {@code @field(name:)} override hint is appended automatically by {@link #message()}.
     */
    static Rejection accessorMismatch(String reason) {
        return new AuthorError.AccessorMismatch(reason);
    }

    /**
     * {@link AuthorError.TypeConflict} factory. Produced by the cross-site
     * {@code contextArgument} type-agreement classifier when two or more directive sites
     * reference the same name with mutually-incompatible Java types.
     */
    static Rejection contextArgumentTypeConflict(String name, List<ConflictSite> sites) {
        return new AuthorError.TypeConflict(name, sites);
    }

    /** {@link InvalidSchema.Structural} factory; the majority shape. */
    static Rejection invalidSchema(String reason) {
        return new InvalidSchema.Structural(reason);
    }

    /**
     * {@link InvalidSchema.DirectiveConflict} factory carrying the conflicting
     * directive names; {@code directives} are bare names (no leading {@code @}).
     */
    static Rejection directiveConflict(List<String> directives, String reason) {
        return new InvalidSchema.DirectiveConflict(directives, reason);
    }

    /**
     * {@link InvalidSchema.CaseFoldCollision} factory. Carries the full case-equivalent collision
     * group and the demoted member's classifier-arm origin so the message renderer can specialise
     * the actionable fix hint.
     */
    static Rejection caseFoldCollision(List<String> group, InvalidSchema.CaseFoldCollision.Origin origin) {
        return new InvalidSchema.CaseFoldCollision(group, origin, "");
    }

    /**
     * {@link Deferred} factory keyed on a stubbed variant class, with optional roadmap slug and
     * optional variant-class anchor. {@code planSlug} is the file basename under
     * {@code roadmap/} (no extension) or empty when the deferred message has
     * no associated plan; {@code fieldClass} is {@code null} when the rejection names a feature
     * shape rather than a stubbed leaf class.
     */
    static Rejection deferred(String summary, String planSlug, Class<? extends GraphitronField> fieldClass) {
        return new Deferred(summary, planSlug, new StubKey.VariantClass(fieldClass));
    }

    /**
     * {@link Deferred} factory for sites with a roadmap slug but no specific variant-class
     * anchor. Equivalent to {@link #deferred(String, String, Class)} with {@code fieldClass} =
     * {@code null}.
     */
    static Rejection deferred(String summary, String planSlug) {
        return new Deferred(summary, planSlug, new StubKey.VariantClass(null));
    }

    /** {@link AuthorError.UnknownName} factory for column-name lookups. */
    static Rejection unknownColumn(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.COLUMN, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for table-name lookups. */
    static Rejection unknownTable(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.TABLE, attempt, candidates);
    }

    /**
     * Produces the {@code "; did you mean: X, Y, Z"} suffix used by
     * {@link AuthorError.UnknownName#message()}. Mirrors {@code BuildContext.candidateHint}; kept
     * here so the rejection model is self-contained and consumers outside the rewrite package can
     * render rejection messages without pulling in the package-private {@code BuildContext}.
     */
    private static String candidateHint(String attempt, List<String> candidates) {
        if (candidates.isEmpty()) return "";
        String lc = attempt.toLowerCase();
        return "; did you mean: " + candidates.stream()
            .sorted(Comparator.comparingInt(c -> levenshteinDistance(lc, c.toLowerCase())))
            .limit(5)
            .collect(Collectors.joining(", "));
    }

    private static int levenshteinDistance(String a, String b) {
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

    /** {@link AuthorError.UnknownName} factory for foreign-key-name lookups. */
    static Rejection unknownForeignKey(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.FOREIGN_KEY, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for service-method lookups. */
    static Rejection unknownServiceMethod(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.SERVICE_METHOD, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for lifter-method lookups. */
    static Rejection unknownLifterMethod(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.LIFTER_METHOD, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for type-name lookups (typename in {@code @nodeId}, refType lookups). */
    static Rejection unknownTypeName(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.TYPE_NAME, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for enum-constant lookups against a Java enum class. */
    static Rejection unknownEnumConstant(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.ENUM_CONSTANT, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for {@code @node} key-column lookups. */
    static Rejection unknownNodeIdKeyColumn(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.NODEID_KEY_COLUMN, attempt, candidates);
    }

    /** {@link AuthorError.UnknownName} factory for {@code @mutation(typeName:)} DML-kind lookups. */
    static Rejection unknownDmlKind(String summary, String attempt, List<String> candidates) {
        return new AuthorError.UnknownName(summary, AttemptKind.DML_KIND, attempt, candidates);
    }
}
