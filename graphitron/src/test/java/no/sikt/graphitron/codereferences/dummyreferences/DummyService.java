package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Test producer stub. Reflection-only record binding (the deprecated {@code @record}
 * directive no longer drives a backing class) means a test type acquires its backing by being the
 * return type of a producer field. These methods exist purely so a test SDL can declare
 * {@code field: SomeType @service(service: {className: "...DummyService", method: "makeX"})} and
 * have {@code SomeType} bind to the method's reflected return class, exactly as the old
 * {@code @record(record: {className: "..."})} idiom did, but through the real reflection path.
 *
 * <p>Bodies never run: {@code ServiceCatalog} reads only the declared return type. One method per
 * distinct backing class referenced by the migrated tests.
 */
public final class DummyService {

    private DummyService() {}

    private static <T> T unused() {
        throw new UnsupportedOperationException("DummyService is a codegen-time return-type stub");
    }

    // ===== Top-level backing classes =====
    public static DummyRecord makeDummyRecord() { return unused(); }
    public static TestRecordDto makeTestRecordDto() { return unused(); }
    public static SakPayload makeSakPayload() { return unused(); }
    public static BothShapesSakPayload makeBothShapesSakPayload() { return unused(); }
    public static MultiCtorSakPayload makeMultiCtorSakPayload() { return unused(); }
    public static SetterShapeSakPayload makeSetterShapeSakPayload() { return unused(); }
    public static SettKvotesporsmalShapePayload makeSettKvotesporsmalShapePayload() { return unused(); }
    public static DeleteFilmPayload makeDeleteFilmPayload() { return unused(); }
    public static DeleteFilmRowOnlyPayload makeDeleteFilmRowOnlyPayload() { return unused(); }

    // ===== Input-axis producers: a method parameter grounds the input type's backing class
    // (reflection-only input binding). The SDL @service arg name must match the parameter name
    // ("in"). Used by InputTypeCase to exercise the PojoInputType.Backed / JavaRecordInputType /
    // JooqTableRecordInputType leaves without the removed @record-className idiom. =====
    public static String consumeDummyRecord(DummyRecord in) { return unused(); }
    public static String consumeTestRecordDto(TestRecordDto in) { return unused(); }
    public static String consumeFilmRecord(no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord in) { return unused(); }

    // ===== DummyFetcherFixtures.* (generic result-type emission tests) =====
    public static DummyFetcherFixtures.ContainerRecord makeContainerRecord() { return unused(); }
    public static DummyFetcherFixtures.FilmStatsRecord makeFilmStatsRecord() { return unused(); }
    public static DummyFetcherFixtures.FilmDetailsRecord makeFilmDetailsRecord() { return unused(); }
    public static DummyFetcherFixtures.DetailsProps makeDetailsProps() { return unused(); }
    public static DummyFetcherFixtures.FilmDetailsRating makeFilmDetailsRating() { return unused(); }

    // ===== AccessorPayloads.* =====
    public static AccessorPayloads.ListPayload makeAccessorListPayload() { return unused(); }
    public static AccessorPayloads.SinglePayload makeAccessorSinglePayload() { return unused(); }
    public static AccessorPayloads.RemappedPayload makeAccessorRemappedPayload() { return unused(); }
    public static AccessorPayloads.SetPayload makeAccessorSetPayload() { return unused(); }
    public static AccessorPayloads.HeterogeneousElementPayload makeAccessorHeterogeneousElementPayload() { return unused(); }
    public static AccessorPayloads.AmbiguousListPayload makeAccessorAmbiguousListPayload() { return unused(); }
    public static AccessorPayloads.SingleAccessorOnListField makeAccessorSingleAccessorOnListField() { return unused(); }

    // ===== R441EventAccessorPayloads.* (multischema typed-accessor / qualified @table match) =====
    public static R441EventAccessorPayloads.SchemaAEventsPayload makeR441SchemaAEventsPayload() { return unused(); }
    public static R441EventAccessorPayloads.SchemaBEventsPayload makeR441SchemaBEventsPayload() { return unused(); }

    // ===== R88AccessorFixtures.* (method name = "r88" + fixture simple name) =====
    public static R88AccessorFixtures.MissingGetterPojo r88MissingGetterPojo() { return unused(); }
    public static R88AccessorFixtures.ReturnTypeMismatchPojo r88ReturnTypeMismatchPojo() { return unused(); }
    public static R88AccessorFixtures.ArgumentBearingPojo r88ArgumentBearingPojo() { return unused(); }
    public static R88AccessorFixtures.OverridePojo r88OverridePojo() { return unused(); }
    public static R88AccessorFixtures.PublicFieldPojo r88PublicFieldPojo() { return unused(); }
    public static R88AccessorFixtures.FullEnvPojo r88FullEnvPojo() { return unused(); }
    public static R88AccessorFixtures.BooleanPojo r88BooleanPojo() { return unused(); }
    public static R88AccessorFixtures.BareNameRecord r88BareNameRecord() { return unused(); }
    public static R88AccessorFixtures.MissingComponentRecord r88MissingComponentRecord() { return unused(); }

    // ===== R461AccessorFixtures.* (walk-grounding parents; method name = "r461" + fixture simple name) =====
    public static R461AccessorFixtures.OrderParentPojo r461OrderParentPojo() { return unused(); }
    public static R461AccessorFixtures.OrderParentRecord r461OrderParentRecord() { return unused(); }
    public static R461AccessorFixtures.ZeroArgParent r461ZeroArgParent() { return unused(); }
    public static R461AccessorFixtures.PerArgParent r461PerArgParent() { return unused(); }
    public static R461AccessorFixtures.RenameParent r461RenameParent() { return unused(); }
    public static R461AccessorFixtures.NonBooleanIsParent r461NonBooleanIsParent() { return unused(); }
    public static R461AccessorFixtures.PublicFieldParent r461PublicFieldParent() { return unused(); }
    public static R461AccessorFixtures.CovariantParent r461CovariantParent() { return unused(); }
    public static R461AccessorFixtures.SubChildParent r461SubChildParent() { return unused(); }
}
