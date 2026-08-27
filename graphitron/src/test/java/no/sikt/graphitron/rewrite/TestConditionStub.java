package no.sikt.graphitron.rewrite;

import org.jooq.Condition;

/**
 * Minimal condition-method stub used by {@link GraphitronSchemaBuilderTest} to test that
 * {@code @reference(path: [{condition: {…}}])} is classified as
 * {@link no.sikt.graphitron.rewrite.model.On.Predicate} hops.
 *
 * <p>Condition methods take two {@code Table<?>} parameters (source and target) and return a
 * {@link Condition}. Both parameters are classified as {@link
 * no.sikt.graphitron.rewrite.model.ParamSource.Table} by
 * {@link ServiceCatalog#reflectTableMethod}.
 */
class TestConditionStub {

    public static Condition join(org.jooq.Table<?> src, org.jooq.Table<?> tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Field-level {@code @condition} method: receives the target table plus every field argument.
     * Parameter names must match GraphQL argument names (requires the {@code -parameters} flag).
     */
    public static Condition fieldCondition(org.jooq.Table<?> table, String cityNames, String countryId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method: receives the target table plus one argument value.
     * Parameter name must match the GraphQL argument name.
     */
    public static Condition argCondition(org.jooq.Table<?> table, String cityNames) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method with a {@code contextArguments} entry: receives the
     * target table, the arg value, then context values as trailing parameters.
     */
    public static Condition argConditionWithContext(org.jooq.Table<?> table, String cityNames, String tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: {@code @condition} method whose {@code tenantId} parameter is declared as
     * {@link Long} instead of {@link String}. Pairs with {@link #argConditionWithContext} to force
     * a cross-site type-agreement conflict in {@link ContextArgumentClassifier} tests.
     */
    public static Condition argConditionTenantIdLong(org.jooq.Table<?> table, String cityNames, Long tenantId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} method: receives the target table plus the SDL input
     * field value. Used on {@code filmId} input fields.
     */
    public static Condition inputColumnCondition(org.jooq.Table<?> table, String filmId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} for a {@code @reference}-navigating field named
     * {@code languageName}.
     */
    public static Condition inputRefCondition(org.jooq.Table<?> table, String languageName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} for a {@code NestingField} named {@code details}.
     */
    public static Condition inputNestingCondition(org.jooq.Table<?> table, String details) {
        throw new UnsupportedOperationException();
    }

    /**
     * Arg-level {@code @condition} method whose Java parameter name diverges from the GraphQL
     * argument name. Used with {@code argMapping: "city: cityNames"} so {@code city} binds to
     * GraphQL arg {@code cityNames} (cross-axis test).
     */
    public static Condition argConditionRenamed(org.jooq.Table<?> table, String city) {
        throw new UnsupportedOperationException();
    }

    /**
     * Field-level {@code @condition} method with renamed Java parameters. Used with
     * {@code argMapping: "city: cityNames, country: countryId"} (cross-axis test).
     */
    public static Condition fieldConditionRenamed(org.jooq.Table<?> table, String city, String country) {
        throw new UnsupportedOperationException();
    }

    /**
     * Input-field-level {@code @condition} method whose Java parameter name diverges from the
     * input-field name. Used with {@code argMapping: "id: filmId"} (cross-axis test).
     */
    public static Condition inputFieldConditionRenamed(org.jooq.Table<?> table, String id) {
        throw new UnsupportedOperationException();
    }

    /**
     * Field-level {@code @condition} method on a field with no GraphQL arguments: receives the
     * target table only. Used by the {@code @sourceRow} + {@code @condition} interaction
     * test where the carrier field is a record-parent table-bound field with no extra args.
     */
    public static Condition lifterFieldCondition(org.jooq.Table<?> table) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: arg-level / input-field {@code @condition} on a multitable
     * interface/union query field. The {@code Table<?>} first parameter serves every participant
     * branch (the emitter passes each branch's own {@code stage1_<Type>} alias); {@code firstName}
     * binds the same-named GraphQL argument or input field.
     */
    public static Condition occupantsFirstName(org.jooq.Table<?> table, String firstName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: {@code @condition(override: true)} on an input field whose name does not
     * match any column on the resolving table. Parameter name {@code sakskode} matches the
     * GraphQL input field name used in {@link GraphitronSchemaBuilderTest}'s override-condition cases.
     */
    public static Condition sakskodeCondition(org.jooq.Table<?> table, String sakskode) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: {@code @condition(override: true)} on a {@code @table}-input field with no
     * matching column. Parameter name {@code syntheticName} matches the field used by the
     * @table symmetry test.
     */
    public static Condition syntheticNameCondition(org.jooq.Table<?> table, String syntheticName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: arg-level {@code @condition} whose Java parameter name does not match the
     * GraphQL argument name, but the signature is type-unambiguous (one {@code Table<?>} plus
     * one {@code String}). Used to assert that {@code argMapping} can be omitted when the
     * type-based pairing is unique.
     */
    public static Condition argConditionTypeUnique(org.jooq.Table<?> table, String whatever) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: arg-level {@code @condition} with two same-type non-Table parameters. Used
     * to assert that the inference falls back to name-based matching when type alone is not
     * sufficient to disambiguate the pairing.
     */
    public static Condition argConditionTwoStrings(org.jooq.Table<?> table, String first, String second) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: input-field {@code @condition} method whose two scalar parameters descend by
     * name into a nested {@code @condition}-slot input object ({@code SokVerdiRange { fra, til }}),
     * with no {@code argMapping}. Mirrors the motivating {@code searchVektingstallRange} shape.
     * Used to assert the inferred {@link no.sikt.graphitron.rewrite.PathExpr} chain equals the one
     * the explicit-{@code argMapping} sibling produces.
     */
    public static Condition searchVerdiRange(org.jooq.Table<?> table,
            java.math.BigDecimal fra, java.math.BigDecimal til) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: input-field {@code @condition} method whose single list parameter descends by
     * name into a list-shaped nested input-object field ({@code verdier: [BigDecimal]}). Pins the
     * computed {@code liftsList=true} on the inferred depth-1 {@code Step}.
     */
    public static Condition searchVerdiList(org.jooq.Table<?> table,
            java.util.List<java.math.BigDecimal> verdier) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: field-level {@code @condition} method whose parameter name matches a nested
     * field present in two distinct input-object arguments, so the depth-1 inference finds two
     * candidates across slots and yields, leaving {@code fra} unbound for the existing
     * name-mismatch rejection.
     */
    public static Condition searchVerdiAmbiguous(org.jooq.Table<?> table, java.math.BigDecimal fra) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a {@code @condition} method with concrete jOOQ table parameters, for hops with no
     * declared target. {@link no.sikt.graphitron.rewrite.BuildContext#resolveConditionJoinTarget}
     * then resolves the target table by reflecting on the second parameter type and looking it up
     * via {@link no.sikt.graphitron.rewrite.JooqCatalog#findTableByClass}. Source is
     * {@code film}; target is {@code film_actor}, so a subsequent {@code {table: "actor"}}
     * step derives the FilmActor → Actor FK.
     */
    public static Condition intermediate(
            no.sikt.graphitron.rewrite.test.jooq.tables.Film src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a <em>terminal</em>-hop condition on a filter path, where no return-type
     * {@code @table} binding exists, so the target is read off this signature: source is the
     * {@code film_actor} junction the previous FK hop landed on, target is {@code actor}. Pairs
     * with {@link #intermediate} to give filter paths a condition hop in either position.
     */
    public static Condition junctionToActor(
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor src,
            no.sikt.graphitron.rewrite.test.jooq.tables.Actor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Check 2 fixture: an ON-clause condition whose <em>first</em> (source)
     * parameter is a concrete table ({@code Actor}) other than the hop's actual source table
     * ({@code film}). The emitter passes the source alias positionally into parameter 0, so a
     * concretely-mistyped source parameter would compile to {@code aCondition(filmAlias, …)}
     * feeding a {@code film}-typed alias into an {@code Actor}-typed slot; javac rejects it. The
     * target ({@code FilmActor}) is correct so only the source-side mismatch fires.
     */
    public static Condition intermediateWrongSource(
            no.sikt.graphitron.rewrite.test.jooq.tables.Actor src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Check 2 fixture: a chain-ending condition hop whose <em>second</em> (target) parameter is a
     * concrete table ({@code Film}) other than the carrier field's return {@code @table}.
     * {@code resolveConditionJoinTarget} prefers the declared target, so it never reads this
     * parameter and without Check 2 the mistyped target parameter slips through to javac. That
     * preference direction is exactly why this stays a Check 2 finding rather than a resolution
     * failure. The source parameter ({@code City}) is correct so only the target-side mismatch
     * fires.
     */
    public static Condition terminalWrongTarget(
            no.sikt.graphitron.rewrite.test.jooq.tables.City src,
            no.sikt.graphitron.rewrite.test.jooq.tables.Film tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Happy-path fixture: a chain-ending condition hop whose concrete source ({@code City}) and
     * target ({@code Actor}) parameters both match the aliases the emitter passes (source = the
     * {@code City} carrier's table, target = the {@code Actor} return {@code @table}). Asserts the
     * concrete-parameter check does not reject a correctly-typed signature.
     */
    public static Condition terminalCorrect(
            no.sikt.graphitron.rewrite.test.jooq.tables.City src,
            no.sikt.graphitron.rewrite.test.jooq.tables.Actor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Check 2 fixture: a {@code {table:, condition:}} whereFilter whose first parameter is a
     * concrete table ({@code Actor}) other than the FK hop's source ({@code film}). The whereFilter
     * rides as {@code .where(filter(srcAlias, tgtAlias))} on the enclosing SELECT with source = the
     * FK hop's origin table and target = its target table, so a mistyped source parameter would
     * hand a {@code film}-typed alias to an {@code Actor}-typed slot.
     */
    public static Condition whereFilterWrongSource(
            no.sikt.graphitron.rewrite.test.jooq.tables.Actor src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: the per-participant overload set, on the {@code Customer | Staff} union the
     * multitable filter cases use. The two declarations agree on the binding shape and differ only
     * in their table slots, so they are one {@code @condition} target; the branch emitter passes
     * each participant's concretely-typed stage-1 alias and the consumer's javac picks the
     * declaration. The table slots are named differently on purpose: a per-participant author names
     * each slot after its own table, and no reader may take one of those names for a binding target.
     */
    public static Condition occupantNameOverload(
            no.sikt.graphitron.rewrite.test.jooq.tables.Customer customer, String firstName) {
        throw new UnsupportedOperationException();
    }

    /** The {@code Staff} branch of {@link #occupantNameOverload}. */
    public static Condition occupantNameOverload(
            no.sikt.graphitron.rewrite.test.jooq.tables.Staff ansatt, String firstName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: an overload set that disagrees on the binding shape. Position 1 shares its name and
     * differs in declared type, so the shared name denotes two call shapes and the set is refused
     * with the parameter-position axis, at every {@code @condition} coordinate alike.
     */
    public static Condition occupantNameDisagreeing(
            no.sikt.graphitron.rewrite.test.jooq.tables.Customer customer, String firstName) {
        throw new UnsupportedOperationException();
    }

    /** The disagreeing sibling of {@link #occupantNameDisagreeing}: {@code firstName} is an Integer. */
    public static Condition occupantNameDisagreeing(
            no.sikt.graphitron.rewrite.test.jooq.tables.Staff ansatt, Integer firstName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a single-declaration {@code @condition} whose table slot is named after the field's
     * only GraphQL argument. The table slot contributes no name to the claimed-slot set, so the
     * argument stays unclaimed and type-based inference binds {@code kriterier} to it; reading the
     * table slot's name as a claim used to leave {@code kriterier} unbound and reject structurally.
     */
    public static Condition tableSlotNamedLikeAnArgument(
            no.sikt.graphitron.rewrite.test.jooq.tables.Film film, FilmFilter kriterier) {
        throw new UnsupportedOperationException();
    }

    /** The filter bean {@link #tableSlotNamedLikeAnArgument} takes: a named input's Java carrier. */
    public record FilmFilter(String navn) {}

    /**
     * Fixture: a path-step overload set whose second slots agree. Both declarations arrive at
     * {@code film_actor} and depart from different tables, so a {@code Film} parent's hop has one
     * applicable declaration and one resolved join target. Pairs with a following
     * {@code {table: "actor"}} step exactly as {@link #intermediate} does.
     */
    public static Condition agreeingIntermediate(
            no.sikt.graphitron.rewrite.test.jooq.tables.Film src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /** The {@code City}-departing declaration of {@link #agreeingIntermediate}. */
    public static Condition agreeingIntermediate(
            no.sikt.graphitron.rewrite.test.jooq.tables.City src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /**
     * Fixture: a path-step overload set whose second slots name different tables. The set is
     * admitted (it agrees on the binding shape) and then resolves no join target, because the hop
     * emits one joined table and there is no consumer call site to defer the choice to.
     */
    public static Condition disagreeingIntermediate(
            no.sikt.graphitron.rewrite.test.jooq.tables.Film src,
            no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor tgt) {
        throw new UnsupportedOperationException();
    }

    /** The {@code Actor}-arriving declaration of {@link #disagreeingIntermediate}. */
    public static Condition disagreeingIntermediate(
            no.sikt.graphitron.rewrite.test.jooq.tables.Film src,
            no.sikt.graphitron.rewrite.test.jooq.tables.Actor tgt) {
        throw new UnsupportedOperationException();
    }
}
