package no.sikt.graphitron.model.test;

import java.util.stream.IntStream;

/**
 * An SDL fixture that scales with a unit count and populates the derivation stratum's tables in
 * proportion to schema size, for the cases that need a population rather than a boundary: the
 * stage oracle's @nodeId fixture, the partition-selectivity worth test, and the stratum's statistics
 * test. Two stage tables stay empty at every size, measured rather than assumed:
 * {@code graphitron_mutation_payload_key_membership} and {@code graphitron_mutation_payload_refusal},
 * their rules reading a {@code @mutation} payload surface this schema holds fixed. A case over this
 * fixture that needs a populated table should establish it per table rather than read the property
 * here as universal: a case over empty relations measures its instrument's floor and reports a
 * number.
 *
 * <p>Shared rather than copied because what this schema populates is a fact re-established every
 * time a rule changes shape, and a second copy would be the one that quietly stopped populating
 * something.
 */
public final class ScaledSchemaFixture {

    private ScaledSchemaFixture() {}

    /**
     * The fixture: {@code units} repetitions of a film/language/inventory/store cluster of node types
     * over real catalog keys, plus one routine field, the mutations, and a routine-carrier cluster
     * per unit, so that nodehood, reference chains, node-id decoding, argument mapping and the
     * carrier family all have rows.
     *
     * <p>The routine-carrier cluster is a mutation-root {@code @routine} field per unit returning a
     * payload type that wraps one nullable data field beside an errors channel, the channel a union
     * whose one member carries {@code @error}. That shape is what populates
     * {@code intent_errors_field} and the relations over it ({@code graphitron_carrier_data_field},
     * {@code intent_field_error_channel}, {@code intent_mutation_routine_seat},
     * {@code intent_carrier_routine_hop}), all of which held no rows here before it: the fixture's
     * only other {@code @routine} sits on the Query root, so the seat relation was empty, and
     * {@code Film0}'s {@code @reference} fields disqualify it from the carrier view. Scaled with the
     * units so those targets hold rows proportional to schema size, like everything else here.
     *
     * <p>Every stratum table but the two named on this class is populated by this schema, and so is
     * every view reading them except the defect relations, which hold rows only on a schema with the
     * defect in it and whose emptiness here is the fixture being well-formed rather than being thin.
     * A schema of {@code @table}-bound types with a single scalar field, which is what the scaled
     * fixtures elsewhere in the reactor use, leaves much of the stratum empty, and a case over empty
     * relations measures the instrument's floor and nothing else.
     *
     * <p>The per-unit filter input is in that list for the same reason and is the second such arm.
     * Before it the fixture's whole input surface was two fields on two mutation payloads, neither
     * scaled with the units and neither carrying a {@code @reference}, so the input-field resolution
     * relations held a couple of flat rows and their reference walk held none. A gate over relations
     * that size prices the counter rather than the work, and it showed: with the surface flat, the
     * resolving table's storing read as a regression against all three of its readers, and with
     * the surface scaled every one of those three read as the improvement it was. The input is deliberately three
     * shapes rather than one, a plain column name, a nested input object so the descent has depth,
     * and a {@code @reference}-pathed field so the walk has a chain to follow, because the three
     * relations fork on exactly those.
     *
     * <p>{@code inventoryForFilm} is in that list for one target's sake and is the arm to understand
     * before touching it. A node-id argument populates the decode walk's hop relations only where the
     * argument's own scope table differs from the node type's table <em>and</em> exactly one foreign
     * key joins the two, which is what the endpoint view's {@code DISCOVERED_KEY} arm requires. The
     * {@code storeForFilm} arm above looks like it should qualify and does not: film reaches store
     * through inventory rather than directly, so no single key joins them and the endpoint contributes
     * no hop. Returning inventory instead gives the one key inventory declares on film. Without this
     * arm the hop-column table holds no rows at any size, and every case over this fixture that reads
     * the decode chain compares two readings of an empty table.
     *
     * <p>{@code media} is the third such arm and it is a per-unit union of two of the cluster's own
     * bound types with one filter argument over a column both their tables carry. Before it the
     * fixture had no multi-table polymorphic root at all, so the participant fan-out held no rows at
     * any size: the field scope's participant arm, and therefore the branch multiplicity every
     * relation below it inherits, priced as an empty relation. That is the state a previous increment
     * of this work walked into from the other direction, reading a shape choice off a fixture whose
     * units made both correlated arms of a ranked view unselective, and the lesson is the same one in
     * reverse. A gate blind to a shape does not price it conservatively; it prices the instrument's
     * floor and reports a number. The union's members are the existing {@code Film} and
     * {@code Inventory} types rather than new ones, so the arm adds branches to price without adding
     * a table, and the filter column is the key one of them declares on the other, which is what
     * makes the name resolve on both branches instead of on one.
     */
    public static String scaledSdl(int units) {
        var sdl = new StringBuilder("""
            interface Node { id: ID! }
            input FilmInput { title: String }
            input FilmKeyInput { filmId: Int! @field(name: "film_id") }
            type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
            """);
        sdl.append("type Mutation {\n").append("""
              createFilm(in: FilmInput!): Film0 @mutation(typeName: INSERT)
              createFilms(in: [FilmInput!]!): [Film0!]! @mutation(typeName: INSERT)
              deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              rentFilm%1$d(inventoryId: Int!, customerId: Int!): RentFilmPayload%1$d
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            """.formatted(i)));
        sdl.append("}\n");
        IntStream.range(0, units).forEach(i -> sdl.append("""
            type RentFilmFailed%1$d @error(handlers: [{
                handler: GENERIC,
                className: "org.jooq.exception.IntegrityConstraintViolationException"
              }]) {
              path: [String!]!
              message: String!
            }
            union RentFilmError%1$d = RentFilmFailed%1$d
            type RentFilmPayload%1$d {
              rental: Rental
              errors: [RentFilmError%1$d]
            }
            """.formatted(i)));
        IntStream.range(0, units).forEach(i -> sdl.append("""
            input NestedFilmFilter%1$d {
              releaseYear: Int @field(name: "release_year")
            }
            input FilmFilter%1$d {
              title: String
              nested: NestedFilmFilter%1$d
              inStore: Int @field(name: "store_id")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film%1$d implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
              releaseYear: Int @field(name: "release_year")
              language: Language%1$d @reference(path: [{key: "film_language_id_fkey"}])
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Language%1$d implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
              name: String
            }
            type Inventory%1$d implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id"]) {
              id: ID! @nodeId
              film: Film%1$d @reference(path: [{key: "inventory_film_id_fkey"}])
              store: Store%1$d @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            type Store%1$d implements Node @table(name: "store") @node(keyColumns: ["store_id"]) {
              id: ID! @nodeId
              inventory: [Inventory%1$d!]! @reference(path: [{key: "inventory_store_id_fkey"}])
            }
            union Media%1$d = Film%1$d | Inventory%1$d
            """.formatted(i)));
        sdl.append("type Query {\n").append("""
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film",
                         argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            """);
        IntStream.range(0, units).forEach(i -> sdl.append("""
              films%1$d(filter: FilmFilter%1$d): [Film%1$d!]!
              film%1$d(id: ID! @nodeId(typeName: "Film%1$d")): Film%1$d
              filmsByKey%1$d(film_id: [ID] @lookupKey): [Film%1$d!]!
              storeForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Store%1$d!]! @reference(path: [
                {key: "inventory_film_id_fkey"},
                {key: "inventory_store_id_fkey"}
              ])
              inventoryForFilm%1$d(id: ID! @nodeId(typeName: "Film%1$d")): [Inventory%1$d!]!
              media%1$d(filmId: Int @field(name: "film_id")): [Media%1$d!]!
            """.formatted(i)));
        return sdl.append("}\n").toString();
    }
}
