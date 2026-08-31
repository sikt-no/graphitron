package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.AgreementObligation;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.CarrierNullRule;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.SetColumn;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.WalkerResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Produces the {@link UpdateRows} carrier for an {@code @mutation(typeName: UPDATE)} field
 * that returns its {@code @table} type directly. The walker's load-bearing claim is PK-or-UK
 * identification: it queries jOOQ's {@code Table.getPrimaryKey()} / {@code Table.getKeys()} (via
 * {@link JooqCatalog#candidateKeys(String)}) and picks the first candidate (PK preferred) whose
 * column set is a subset of the input-covered columns, then partitions the input fields into the
 * WHERE (matched-key) and SET (everything else) halves.
 *
 * <p><b>Substrate concession (mirrors {@code ServiceMethodCallWalker}).</b> The walker
 * translates over the already-classified {@link InputField} permits rather than re-deriving the
 * column classification from raw SDL: re-running {@code InputFieldResolver} /
 * {@code EnumMappingResolver.buildLookupBindings} (with {@code @reference} FK-join and {@code @nodeId}
 * decode resolution) here would duplicate a substantial classifier. The {@code field} parameter
 * exists for a direct-SDL substrate and is not read. The walk is
 * cardinality-independent: a self-FK {@code @reference} routes its columns wholly to SET on both the
 * single-row and bulk (list-input) forms, so the caller's list shape never reaches here.
 *
 * <p><b>What partitions, and at what grain.</b> Whole carriers partition whole; a cross-table
 * {@code @nodeId} reference partitions per column. The reason is that a reference's lifted columns
 * are a pointer at another row, so the ones that happen to also be this row's key are identity to be
 * filtered on and the rest are a value to be written, and one input field can legitimately be both.
 * Where such an in-key column already has an identity contributor the reference neither filters nor
 * writes it, and the walker records an {@link no.sikt.graphitron.rewrite.model.AgreementObligation}
 * so the emitters check the two decoded values agree before any DML runs. The same obligation covers
 * the self-FK overlap, which the emitters used to intersect for themselves.
 *
 * <p><b>What an explicit null means.</b> Stated per SET-contributing carrier as a
 * {@link no.sikt.graphitron.rewrite.model.CarrierNullRule}, for the same reason the obligations are:
 * the answer turns on the matched key, so an emitter cannot derive it, and four emit consumers would
 * otherwise each have to. The rule and the admission gate for a nullable straddler both follow from
 * one definition, of an <em>identity contributor</em> to a column: a carrier guaranteed present on
 * every call whose decode supplies, or can supply, that column's WHERE predicate. That is a whole
 * carrier other than a self-FK, or a non-null cross-table straddler lifting the column in its in-key
 * half, and it is what the two claim-resolution phases below transcribe.
 *
 * <p>Errors are collected across stages without short-circuiting so the LSP surfaces every
 * per-field issue at once.
 *
 * <p><b>Nested grouping inputs.</b> A plain (non-{@code @table}) input object grouping
 * columns of the outer table classifies as an {@link InputField.NestingField}; the walker flattens
 * it in place ({@link #classifyInto}) into the same flat leaf carriers it would admit at the
 * input root. A list-typed nesting is rejected.
 */
public final class UpdateRowsWalker {

    /**
     * What kind of thing a column-bearing input field points at, which is what decides how its
     * columns partition. Sealed on the three answers the classifier can give, so the partition
     * switch is exhaustive and a fourth carrier shape breaks it at compile time.
     *
     * <p>Replaces a {@code boolean selfReference} flag. The flag answered two of the three cases and
     * left the third (own columns versus a cross-table pointer) to be re-derived, which mattered
     * once the two stopped sharing a disposition. Nullability is not an arm's component: every arm
     * reads it now, because the rule for what an explicit null means is uniform over the three, so
     * it rides on {@link Contribution} instead.
     */
    private sealed interface CarrierRole {

        /** The field carries the row's <em>own</em> columns: a plain {@code @field}, or a same-table
         *  composite {@code @nodeId} with no {@code @reference}. These columns are this row's
         *  identity where they are in the key, so a straddle means moving the row and rejects. */
        record OwnColumns() implements CarrierRole {}

        /** A self-FK {@code @nodeId @reference}: the lifted columns are a pointer to a sibling row of
         *  the same table, never this row's identity, so they route wholly to SET regardless of key
         *  membership and never count toward key coverage. Nullability is not consulted: an omitted
         *  or null self-FK simply drops out under PATCH semantics, writing no half-key, because the
         *  whole foreign key is on the SET side. */
        record SelfFk() implements CarrierRole {}

        /** A cross-table FK {@code @nodeId} reference: a pointer at another table's row, whose lifted
         *  child columns can legitimately include this row's own identity. Partitions per column,
         *  whatever its spelling; a nullable one is measured against the settled WHERE partition and
         *  refused only where it is the sole contributor to a matched-key column. */
        record CrossTableFk() implements CarrierRole {}
    }

    /**
     * A reshaped, column-bearing input field: the SDL field name, its target columns on the
     * input's own table, the extraction shape the emitter reuses, and what the carrier points at.
     * A self-FK carrier's columns route wholly to the SET partition and
     * never count toward WHERE-key coverage; every other carrier partitions by key membership.
     *
     * <p>{@code columns} is positionally aligned with the decode record the carrier's
     * {@code @nodeId} produces, so a column's index here <em>is</em> its decode slot. The alignment
     * is the producer's promise, not this walker's: for a lifted FK-target carrier the tuple comes
     * from {@link no.sikt.graphitron.rewrite.model.FilterBinding.Local#ownTableColumns()}, which
     * {@link no.sikt.graphitron.rewrite.NodeIdLeafResolver.Resolved.FkTarget.DirectFk#liftedSourceColumns()}
     * documents as "where each key position landed, in {@code keyColumns} order", which is the same order
     * {@link no.sikt.graphitron.rewrite.model.HelperRef.Decode#outputColumnShape()} states for the
     * returned {@code Record<N>}. The type system cannot carry that alignment, which is why stage 6
     * reads the index off this list once and hands every downstream row an explicit slot rather than
     * letting each emitter re-derive one from its own partition's ordering.
     */
    private record Contribution(String sdlFieldName, List<ColumnRef> columns, CallSiteExtraction extraction,
                                CarrierRole role, boolean nonNull, SourceLocation location) {

        /** A self-FK's columns are a sibling pointer, so they never pin the row they live on. */
        boolean pinsIdentity() { return !(role instanceof CarrierRole.SelfFk); }
    }

    /**
     * True when the carrier decodes a {@code @nodeId} (directly or behind a nested-input access
     * path), i.e. its value is only knowable at runtime: the distinction between a
     * runtime-agreement overlap and the build-time plain-field collision.
     */
    private static boolean isDecodeExtraction(CallSiteExtraction extraction) {
        var leaf = extraction instanceof CallSiteExtraction.NestedInputField nif ? nif.leaf() : extraction;
        return leaf instanceof CallSiteExtraction.NodeIdDecodeKeys;
    }

    public WalkerResult<UpdateRows> walk(
        GraphQLFieldDefinition field,
        TableRef table,
        List<InputField> inputFields,
        JooqCatalog catalog,
        String outerArgName
    ) {
        var errors = new ArrayList<Rejection.AuthorError>();

        // Stage 2: classify each input field into a walker-local column contribution, flattening
        // any nested (non-@table) grouping input into its leaf carriers in place; collect
        // per-field admissibility rejections across the loop (no short-circuit).
        var contributions = new ArrayList<Contribution>();
        classifyInto(inputFields, List.of(), outerArgName, errors, contributions);
        if (!errors.isEmpty()) {
            // Unadmitted fields make the covered-column set unreliable; surface every per-field
            // issue without muddying the result with a spurious key-coverage error.
            return new WalkerResult.Err<>(errors);
        }

        // Stage 3: union of every admitted field's target columns (all carriers, for diagnostics),
        // plus the identity-only subset (non-self-FK carriers) the key match is computed over.
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        var identityColumnSqlNames = new LinkedHashSet<String>();
        for (var c : contributions) {
            for (var col : c.columns()) {
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
                if (c.pinsIdentity()) {
                    identityColumnSqlNames.add(col.sqlName());
                }
            }
        }

        // Stage 4-5: PK-or-UK identification via the shared matcher, over the identity
        // (non-self-FK) columns only. A self-FK points at a sibling row, so it can never
        // pin the row it lives on; a PK column reachable only through a self-FK fails coverage
        // (NoUniqueKeyCoverage), matching the semantic "your identity fields do not pin a key".
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, identityColumnSqlNames).orElse(null);
        if (matchedKey == null) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoUniqueKeyCoverage(
                    table.tableName(), inputColumns, MatchedKeys.candidates(catalog, table))));
        }

        // Stage 6: partition each admitted field into the WHERE (matched-key) and SET (everything
        // else) halves. The disposition follows the carrier's role rather than its column list:
        //
        //   - Own columns partition by key membership, whole. A straddle means writing half the
        //     row's identity, which is moving the row: MixedCarrierKeyMembership.
        //   - A self-FK routes wholly to SET regardless of membership. Its columns point at a
        //     sibling row, never this row's identity, so a key column it writes is an ordinary SET
        //     write; the FK forces it equal to the WHERE value, checked at emit.
        //   - A cross-table FK partitions PER COLUMN. It is equally a pointer rather than an
        //     identity, but unlike a self-FK its child columns can include this row's own key, so
        //     the in-key half stays identity and only the out-of-key half is written. Its spelling
        //     does not change the partition; a nullable one is measured against the settled WHERE
        //     partition below and refused only where it is a key column's sole contributor.
        //
        // Each flattened row carries its decode slot, read off the contribution's column index (see
        // Contribution). This is the one place the index is available as the decode slot; once a
        // carrier's columns are split across the two partitions, neither partition's ordering
        // recovers it.
        var keySqlNames = sqlNameSet(matchedKey.columns());
        var setColumns = new ArrayList<SetColumn>();
        var keyColumns = new ArrayList<KeyColumn>();
        // A straddler's in-key column is identity, but whether it is *this* field's job to supply
        // the WHERE predicate depends on whether anything else already does. Collected here and
        // resolved once every whole-carrier key column is known.
        var identityClaims = new ArrayList<ColumnSlot>();
        // The nullable straddlers, with the in-key columns each lifts, held aside for the pinning
        // gate below: whether one is admitted turns on the WHERE partition, which is not settled yet.
        var nullableStraddlers = new ArrayList<Straddle>();
        for (var c : contributions) {
            if (c.role() instanceof CarrierRole.SelfFk) {
                addSetColumns(setColumns, c);
                continue;
            }
            var inKey = new ArrayList<ColumnRef>();
            var outsideKey = new ArrayList<ColumnRef>();
            for (var col : c.columns()) {
                (keySqlNames.contains(col.sqlName()) ? inKey : outsideKey).add(col);
            }
            boolean straddles = !inKey.isEmpty() && !outsideKey.isEmpty();
            if (straddles) {
                switch (c.role()) {
                    case CarrierRole.OwnColumns ignored -> {
                        errors.add(new UpdateRowsError.MixedCarrierKeyMembership(
                            c.sdlFieldName(), inKey, outsideKey));
                        continue;
                    }
                    case CarrierRole.CrossTableFk ignored -> {
                        for (int slot = 0; slot < c.columns().size(); slot++) {
                            var col = c.columns().get(slot);
                            if (keySqlNames.contains(col.sqlName())) {
                                identityClaims.add(new ColumnSlot(c, col, slot));
                            } else {
                                setColumns.add(new SetColumn(c.sdlFieldName(), col, c.extraction(), slot));
                            }
                        }
                        if (!c.nonNull()) {
                            nullableStraddlers.add(new Straddle(c, List.copyOf(inKey), List.copyOf(outsideKey)));
                        }
                        continue;
                    }
                    // A self-FK never reaches the straddle branch; it returned above.
                    case CarrierRole.SelfFk ignored -> throw new IllegalStateException(
                        "self-FK carrier '" + c.sdlFieldName() + "' reached the straddle branch");
                }
            }
            if (outsideKey.isEmpty()) {
                addKeyColumns(keyColumns, c);
            } else {
                addSetColumns(setColumns, c);
            }
        }
        // No early return here. The two straddle refusals are one stage and are reported together,
        // an author fixing one of them meeting the other; the nullable one is only decidable once
        // the WHERE partition below has settled, so the stage's return moves down with it. A
        // rejected own-columns straddler contributes nothing to either partition, so the resolution
        // below runs over a smaller input rather than an inconsistent one.

        // Resolve the straddlers' identity claims, in two stated phases rather than one pass.
        //
        // Phase 1 settles the WHERE partition: whole carriers (already in keyColumns) and NON-NULL
        // straddlers. A claimed column that no whole carrier supplies gets its WHERE predicate from
        // the straddler (the sole-contributor case: there is nothing to check it against).
        // Otherwise the straddler contributes only an agreement obligation below. Two straddlers
        // claiming one column resolve in input-field order. That choice is observationally
        // irrelevant AMONG PHASE 1'S CLAIMANTS, whose agreement checks run whichever wins; which
        // carriers may claim at all is not irrelevant, and the phase split is what decides it.
        //
        // Phase 2 measures the nullable straddlers against the settled set. A nullable field cannot
        // be load-bearing identity: omitted, it leaves the row unidentifiable, and no per-row
        // conditional recovers a WHERE conjunct that was never sent. So it never claims, and it is
        // admitted exactly where every in-key column it lifts already has an identity contributor.
        var keyBySqlName = new java.util.LinkedHashMap<String, KeyColumn>();
        for (var kc : keyColumns) {
            keyBySqlName.putIfAbsent(kc.targetColumn().sqlName(), kc);
        }
        for (var claim : identityClaims) {
            if (!claim.owner().nonNull() || keyBySqlName.containsKey(claim.column().sqlName())) {
                continue;
            }
            var kc = new KeyColumn(claim.owner().sdlFieldName(), claim.column(),
                claim.owner().extraction(), claim.slot());
            keyColumns.add(kc);
            keyBySqlName.put(claim.column().sqlName(), kc);
        }
        // The pinning gate. keyBySqlName now holds exactly the identity contributors' columns: a
        // whole non-self-FK carrier supplies its columns directly, and a non-null straddler's in-key
        // column is either its own winning claim or one a whole carrier already carries. Either way
        // membership here IS "has an identity contributor", so the definition is applied once.
        for (var s : nullableStraddlers) {
            var unpinned = s.inKey().stream()
                .filter(col -> !keyBySqlName.containsKey(col.sqlName()))
                .toList();
            if (!unpinned.isEmpty()) {
                errors.add(new UpdateRowsError.NullableStraddlingReference(
                    s.carrier().sdlFieldName(), s.carrier().location(), table.tableName(), matchedKey,
                    unpinned, s.outsideKey()));
            }
        }
        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        // Stage 6a: the value-agreement obligations, the walker's finished decision about every
        // column two fields both decode a value for. Two sources, both folded here so no emitter has
        // to intersect the partitions for itself: a SET column that is also a key column (the
        // self-FK overlap), and a straddler's in-key column that some other field already pins.
        //
        // The walk is contribution order, then slot order within a contribution, which is the order
        // the SET partition itself is built in, so an input with only self-FK overlaps produces
        // exactly the pairs and the sequence the emitters used to derive.
        var obligations = new ArrayList<AgreementObligation>();
        for (var c : contributions) {
            for (int slot = 0; slot < c.columns().size(); slot++) {
                var col = c.columns().get(slot);
                var keySide = keyBySqlName.get(col.sqlName());
                if (keySide == null || keySide.sdlFieldName().equals(c.sdlFieldName())) {
                    continue; // not a key column, or this field is the one pinning it
                }
                // A self-FK routed every column to SET, so a key column among them is written and
                // checked. A straddler's in-key column is checked and nothing else, and it is a
                // claim rather than a KeyColumn precisely because something else pins it. Every
                // other carrier that reaches a key column here is one whose whole tuple is the key,
                // and it is either the pinning field itself or a duplicate the WHERE already carries.
                boolean writesIt = c.role() instanceof CarrierRole.SelfFk;
                boolean checksOnly = c.role() instanceof CarrierRole.CrossTableFk
                    && identityClaims.stream().anyMatch(cl -> cl.owner() == c && cl.column().sqlName().equals(col.sqlName()));
                if (!writesIt && !checksOnly) {
                    continue;
                }
                obligations.add(new AgreementObligation(col,
                    new AgreementObligation.Side(keySide.sdlFieldName(), keySide.extraction(), keySide.decodeSlot()),
                    new AgreementObligation.Side(c.sdlFieldName(), c.extraction(), slot)));
            }
        }

        // Stage 6b: two or more plain @field writers on one SET column silently
        // last-write-wins through the single-row Map.put (and crashes the bulk VALUES-join with a
        // duplicate derived column); reject at validate time, the UPDATE mirror of the INSERT-path
        // reject. An overlap involving a @nodeId decode is admitted and reconciled by the runtime
        // value-agreement check, so it is not caught here.
        var setByColumn = new java.util.LinkedHashMap<String, List<SetColumn>>();
        for (var sc : setColumns) {
            setByColumn.computeIfAbsent(sc.targetColumn().sqlName(), k -> new ArrayList<>()).add(sc);
        }
        for (var e : setByColumn.entrySet()) {
            var group = e.getValue();
            if (group.size() >= 2 && group.stream().noneMatch(sc -> isDecodeExtraction(sc.extraction()))) {
                errors.add(new UpdateRowsError.PlainColumnCollision(
                    group.get(0).sdlFieldName(), group.get(1).sdlFieldName(), e.getKey()));
            }
        }
        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        // Stage 7: UPDATE with nothing to set is structurally ill-formed.
        if (setColumns.isEmpty()) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoSetFields(table.tableName(), matchedKey)));
        }

        // Stage 7a: what an explicit null on each SET-contributing carrier means. Computed here
        // because this is where the matched key and each carrier's own SET columns are both in hand;
        // an emitter holding one of them cannot answer. The predicate is uniform over the three
        // carrier roles and never consults straddling: what decides is the SDL nullability and
        // whether any column this carrier WRITES is a matched-key column. That is what makes the
        // self-FK case fall out for free (it routes every lifted column to SET, so one overlapping
        // the key is refused, because clearing it would orphan the row) and an admitted straddler
        // fall out as a clear (its SET half is its out-of-key half by construction).
        var setColumnsByCarrier = new java.util.LinkedHashMap<Contribution, List<ColumnRef>>();
        for (var c : contributions) {
            for (var sc : setColumns) {
                if (sc.sdlFieldName().equals(c.sdlFieldName()) && sc.extraction().equals(c.extraction())) {
                    setColumnsByCarrier.computeIfAbsent(c, k -> new ArrayList<>()).add(sc.targetColumn());
                }
            }
        }
        var nullRules = new ArrayList<CarrierNullRule>();
        for (var e : setColumnsByCarrier.entrySet()) {
            var c = e.getKey();
            var identityWrites = e.getValue().stream()
                .filter(col -> keySqlNames.contains(col.sqlName()))
                .toList();
            CarrierNullRule.OnExplicitNull rule;
            if (c.nonNull()) {
                rule = new CarrierNullRule.OnExplicitNull.CannotArrive();
            } else if (!identityWrites.isEmpty()) {
                rule = new CarrierNullRule.OnExplicitNull.RefusedAsIdentity(identityWrites);
            } else {
                rule = new CarrierNullRule.OnExplicitNull.Clears();
            }
            nullRules.add(new CarrierNullRule(c.sdlFieldName(), c.extraction(), rule));
        }

        return new WalkerResult.Ok<>(
            new UpdateRows.Identified(matchedKey, setColumns, keyColumns, obligations, nullRules));
    }

    /**
     * Flatten {@code fields} into {@link Contribution}s, descending into any
     * {@link InputField.NestingField} grouping input so a plain non-{@code @table} input that
     * groups columns of the outer table contributes the same flat leaf carriers it would at the
     * input root. Nested-leaf extractions are rewrapped via {@link #wrap}. The PK-or-UK match
     * downstream runs over the flattened leaves' resolved columns unchanged: a nested leaf's
     * column counts toward key coverage exactly as a root leaf's.
     */
    private void classifyInto(
        List<InputField> fields, List<String> prefix, String outerArgName,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        for (var f : fields) {
            switch (f) {
                case InputField.ColumnBackedField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.columns(), wrap(c.extraction(), prefix, c.name(), outerArgName),
                    new CarrierRole.OwnColumns(), c.nonNull(), c.condition(), c.location(), errors, contributions);
                // A reference carrier contributes the tuple its binding names on this table. A
                // Remote binding has none: its decoded key identifies a target row and reaches this
                // table only through the join, so there is nothing to put in a SET or a WHERE
                // without a subquery. The switch is exhaustive so a third binding arm breaks here.
                case InputField.ColumnBackedReferenceField c -> {
                    switch (c.binding()) {
                        case FilterBinding.Local(var ownTableColumns) -> classifyColumnCarrier(
                            c.name(), c.list(), ownTableColumns, wrap(c.extraction(), prefix, c.name(), outerArgName),
                            c.selfReference() ? new CarrierRole.SelfFk() : new CarrierRole.CrossTableFk(),
                            c.nonNull(), c.condition(), c.location(), errors, contributions);
                        case FilterBinding.Remote ignored ->
                            errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                                c.name(), "translated FK-target @nodeId reference",
                                FilterBinding.remoteBindingUnsupported(c.name(),
                                    "written by @mutation(typeName: UPDATE)")));
                    }
                }
                case InputField.ConditionOwnedField c ->
                    errors.add(new UpdateRowsError.OverrideConditionNotSupported(c.name(), c.location()));
                case InputField.UnboundField u ->
                    errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                        u.name(), "UnboundField",
                        "the field binds no column and carries no @condition(override: true); "
                        + "UPDATE input fields must bind a column"));
                case InputField.NestingField n -> {
                    if (n.list()) {
                        errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                            n.name(), "list-typed NestingField",
                            "list-typed nested input types (e.g. '" + n.name() + ": [" + n.typeName()
                            + "!]') on @mutation(typeName: UPDATE) fields are not yet supported; "
                            + "a list grouping has no obvious meaning when flattening onto one outer row."));
                    } else if (n.condition().isPresent()) {
                        errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                            n.name(), "NestingField with @condition",
                            "@condition on a nested grouping input is not supported on "
                            + "@mutation(typeName: UPDATE); the filter would not be applied. Remove the directive."));
                    } else {
                        var childPrefix = new ArrayList<>(prefix);
                        childPrefix.add(n.name());
                        classifyInto(n.fields(), childPrefix, outerArgName, errors, contributions);
                    }
                }
                default ->
                    errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                        f.name(), f.getClass().getSimpleName(),
                        "input field shape is not a supported UPDATE input carrier"));
            }
        }
    }

    /**
     * Rewrap a leaf's call-site extraction so a nested leaf descends the wire map. Top-level leaves
     * ({@code prefix} empty) keep their extraction unchanged so the emit is byte-identical; nested
     * leaves get a {@link CallSiteExtraction.NestedInputField} with the full access path. The leaf
     * extraction (never itself a {@code NestedInputField}, since the classifier produces only
     * {@code Direct} / {@code NodeIdDecodeKeys} leaves) rides through as the {@code NestedInputField}
     * leaf, so deep nesting collapses to one carrier with a multi-segment path.
     */
    private static CallSiteExtraction wrap(
        CallSiteExtraction leaf, List<String> prefix, String leafName, String outerArgName
    ) {
        if (prefix.isEmpty()) {
            return leaf;
        }
        var path = new ArrayList<>(prefix);
        path.add(leafName);
        return new CallSiteExtraction.NestedInputField(outerArgName, path, leaf);
    }

    /**
     * Reshape an admitted column carrier into a {@link Contribution}, unless it carries a
     * field-level {@code @condition}. Input-field conditions are not emitted on UPDATE, so a
     * condition would be silently dropped, the same footgun {@link UpdateRowsError.OverrideConditionNotSupported}
     * makes honest; reject rather than admit. An {@code override: true} condition reports through
     * that arm; any other condition (e.g. {@code override: false}) reports as an unsupported shape.
     */
    private void classifyColumnCarrier(
        String name, boolean list, List<ColumnRef> columns, CallSiteExtraction extraction,
        CarrierRole role, boolean nonNull, Optional<ArgConditionRef> condition, SourceLocation location,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        if (list) {
            errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                name, "list-typed column carrier",
                "list-typed input field is not supported; list cardinality must live on the outer "
                + "@table argument, not on an individual input field."));
            return;
        }
        if (condition.isPresent()) {
            if (condition.get().override()) {
                errors.add(new UpdateRowsError.OverrideConditionNotSupported(name, location));
            } else {
                errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                    name, "column carrier with @condition",
                    "@condition on a @mutation(typeName: UPDATE) input field is not supported; the "
                    + "filter would not be applied. Remove the directive."));
            }
            return;
        }
        contributions.add(new Contribution(name, columns, extraction, role, nonNull, location));
    }

    /** One column of one contribution, with the decode slot it sits at: a straddler's in-key column
     *  held aside until every whole carrier's key columns are known. */
    private record ColumnSlot(Contribution owner, ColumnRef column, int slot) {}

    /** One straddling carrier's partition, held for the pinning gate: which of its columns are the
     *  matched key's and which are not. Only nullable straddlers are collected, those being the only
     *  ones the gate measures; the split is kept because the diagnostic renders both halves. */
    private record Straddle(Contribution carrier, List<ColumnRef> inKey, List<ColumnRef> outsideKey) {}

    /** Append every column of {@code c} to the SET partition, each at its decode slot. */
    private static void addSetColumns(List<SetColumn> setColumns, Contribution c) {
        for (int slot = 0; slot < c.columns().size(); slot++) {
            setColumns.add(new SetColumn(c.sdlFieldName(), c.columns().get(slot), c.extraction(), slot));
        }
    }

    /** Append every column of {@code c} to the WHERE partition, each at its decode slot. */
    private static void addKeyColumns(List<KeyColumn> keyColumns, Contribution c) {
        for (int slot = 0; slot < c.columns().size(); slot++) {
            keyColumns.add(new KeyColumn(c.sdlFieldName(), c.columns().get(slot), c.extraction(), slot));
        }
    }

    private static Set<String> sqlNameSet(List<ColumnRef> columns) {
        var out = new LinkedHashSet<String>();
        for (var c : columns) out.add(c.sqlName());
        return out;
    }
}
