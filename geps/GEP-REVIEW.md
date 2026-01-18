# GEP Review: Feasibility and Implementation Strategy

**Date:** 2026-01-18 (Updated)
**Reviewer:** Comprehensive analysis of GEP-001, GEP-002, and GEP-003

This document provides a rigorous review of the three proposed GEPs, examining feasibility, implementation complexity, risks, and trade-offs.

---

## Executive Summary

All three GEPs are **well-designed and ready for implementation**. Each addresses real architectural problems with clear solutions and safe migration paths.

**Recommended Implementation Order:**
1. **GEP-001 Phase 1** (2-3 weeks) - Build config layer, validate in parallel
2. **GEP-002** (5-7 weeks) - Simplify architecture by removing DTOs
3. **GEP-003** (4-6 weeks) - Add selection-aware query optimization
4. **GEP-001 Phases 2-3** (5-7 weeks) - Complete config migration

**Total Timeline:** 16-23 weeks for complete architectural transformation

---

## GEP-001: Parse-and-Validate Architecture

### Summary
Replace ProcessedSchema God Object (1,323 lines, queried 248 times) with clean parse-then-validate architecture using immutable configuration objects.

### ✅ Feasibility: HIGH

**Why this is feasible:**
- GraphQL-Java's `SchemaTraverser` API is stable and well-documented
- Pattern is proven (compilers use this approach)
- Phase 1 runs in parallel with existing code (zero risk to prove concept)
- No runtime performance implications (all at code generation time)

**Technical risks: LOW**
- Phase 1 validates approach before committing to migration
- Each phase can be tested independently
- Old code path remains until Phase 3

### 📊 Implementation Complexity: MEDIUM

Based on codebase research, the current problems are clear and fixable:

**Current State:**
- ProcessedSchema: 1,323 lines with 70+ query methods
- Called 248 times across 39 generator files
- Directives parsed in 10+ constructors (scattered)
- Reflection static initialization with runtime errors
- 15+ TODOs indicating architectural pain
- "FS HACK" workaround baked into system

**What needs to be built:**

**Phase 1: Config + Validation (2-3 weeks, LOW risk)**
- CodeGenerationConfig structure (~300 lines)
- Parser using SchemaTraverser (~500 lines)
- Validator with error collection (~400 lines)
- Rich error message types (~200 lines)
- **Total: ~1,400 lines**
- **Risk: LOW** - runs in parallel, proves concept

**Phase 2: Migrate Generators (4-6 weeks, MEDIUM risk)**
- Update 39 generator files to use config instead of ProcessedSchema
- Eliminate 248 repeated queries
- Migrate one generator at a time with tests
- **Risk: MEDIUM** - touching generation, but config is validated

**Phase 3: Cleanup (1-2 weeks, LOW risk)**
- Delete ProcessedSchema (1,323 lines)
- Delete old validator code
- **Risk: LOW** - by this point, config is proven

### 💰 Benefits vs Current Architecture

| Aspect | Current | After GEP-001 |
|--------|---------|---------------|
| **Architecture** | Monolithic God Object | Parse → Validate → Generate |
| **Lines of code** | ProcessedSchema: 1,323 | Config classes: ~800 |
| **Query pattern** | 248 calls to ProcessedSchema | Config passed down, no queries |
| **Reflection** | Scattered, static init, runtime errors | Isolated in parser, fails fast |
| **Testability** | Need full schema + jOOQ setup | Mock FieldConfig/TypeConfig |
| **Error messages** | "Missing column EMAIL" | Full context, location, suggestions |
| **Validation** | Mixed with generation | Separate, all errors at once |

### 🎯 Recommendation

**✅ IMPLEMENT with phased migration as designed**

**Why this is the right approach:**
1. **Solves real problems** - 15+ TODOs, workarounds, repeated queries documented
2. **Safe migration** - Phase 1 proves concept with zero risk
3. **Foundation for GEP-002 and GEP-003** - Clean config needed for both
4. **Improves maintainability** - Replace God Object with clean structure
5. **Better error messages** - With location, context, and suggestions

**Critical insight:** This isn't "just better error messages" - it's fixing fundamental architectural problems that make the codebase hard to maintain and extend.

**Implementation notes:**
- Start with Phase 1 (validation only) to prove approach
- Keep ProcessedSchema running in parallel during Phases 1-2
- Only delete ProcessedSchema in Phase 3 when migration is complete

---

## GEP-002: Simplify Mapping with JooqRecordDataFetcher

### Summary
Eliminate DTO layer. DataFetchers return `Result<Record>`. GraphQL-Java handles traversal via RuntimeWiring with JooqRecordDataFetcher.

### ✅ Feasibility: HIGH

**Why this is feasible:**
- GraphQL-Java's execution engine already handles selection set traversal
- Pattern is proven (PropertyDataFetcher works this way)
- jOOQ Records are stable and well-understood
- Major simplification (75% code reduction)

### 📊 Implementation Complexity: MEDIUM

**What needs to be built:**

1. **JooqRecordDataFetcher class** (~150 lines, 1 week)
   - Simple class similar to PropertyDataFetcher
   - Two constructors (TableField and String alias)
   - Risk: LOW

2. **RuntimeWiring generation** (~800 lines, 2-3 weeks)
   - Generate `.type()` and `.dataFetcher()` calls for every field
   - Handle @field mappings, @splitQuery, nested data
   - Risk: MEDIUM - need to handle all directive combinations

3. **Remove DTO/TypeMapper generation** (delete ~3000+ lines, 1 week)
   - Clean up generator code
   - Risk: LOW - just removal

4. **Update DataFetcher generation** (~500 lines changes, 1-2 weeks)
   - Change return type from `List<DTO>` to `Result<Record>`
   - Remove mapping calls
   - Risk: LOW - simplification

**Total: 5-7 weeks implementation**

### ⚠️ Design Decisions (All Resolved)

#### 1. Selection Set Handling ✅

**How it works:**
- GraphQL-Java execution engine only calls DataFetchers for fields in selection set
- DataFetchers fetch all columns (over-fetching at database)
- GraphQL-Java naturally filters which fields are extracted
- No need for TypeMapper selection set checking

**Example execution flow:**
```graphql
query {
  users {
    id
    name
    # email NOT requested
  }
}
```

1. GraphQL-Java calls `UsersQueryDataFetcher.get()`
2. Returns `Result<Record>` with all columns (id, name, email, ...)
3. GraphQL-Java traverses each Record
4. Calls `JooqRecordDataFetcher` for `id` ✓
5. Calls `JooqRecordDataFetcher` for `name` ✓
6. Does NOT call DataFetcher for `email` (not in selection)

**Result:** No selection set problem. GraphQL-Java handles it naturally.

#### 2. The Simplify-First Strategy ✅

**Current architecture is too complex to optimize safely:**
```
ProcessedSchema (1,323 lines, 248 queries)
      ↓
DataFetchers (query logic)
      ↓
TypeMappers (selection set checking + field mapping)
      ↓
DTOs (duplicate schema structure)
```

**Problem:** Adding selection-aware queries to this would require modifying:
- DataFetchers (query building logic)
- TypeMappers (selection set checking)
- Would split selection set logic across two places
- Hard to test, easy to introduce bugs

**Strategy:**
1. **GEP-002: Simplify first** - Remove layers, get to clean base
2. **GEP-003: Optimize later** - Add selection-aware queries from stable base

**After GEP-002:**
```
DataFetchers (query logic only)
      ↓
Records
      ↓
GraphQL-Java traversal (handles selection naturally)
```

**Result:** Selection-aware queries (GEP-003) only need to modify DataFetchers. All complexity in one place.

#### 3. @splitQuery Implementation ✅

**Works the same as current, just without DTOs:**

Parent DataFetcher fetches PK/FK columns:
```java
public Result<Record> getUsersDataFetcher() {
    return ctx.select(
        USERS.ID,          // ← PK needed for DataLoader
        USERS.NAME
    ).from(USERS).fetch();
}
```

Child DataLoader uses these columns:
```java
.type("User", builder -> builder
    .dataFetcher("orders", env -> {
        Record user = (Record) env.getSource();
        Integer userId = user.get(USERS.ID);  // ← Extract PK

        DataLoader<Integer, List<Record>> loader = env.getDataLoader("UserOrders");
        return loader.load(userId);
    })
)
```

**Key point:** Code generator knows `orders` is `@splitQuery`, so ensures PK columns are fetched.

#### 4. Nested Data via Multiset ✅

**jOOQ multiset returns `Result<Record>` - GraphQL-Java traverses naturally:**

```java
return ctx.select(
    USERS.ID,
    multiset(
        select(ADDRESS.fields())
        .from(ADDRESS)
        .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
    ).as("address")  // ← Returns Result<Record>
).from(USERS).fetch();
```

**Wiring:**
```java
.type("User", builder -> builder
    .dataFetcher("address", new JooqRecordDataFetcher("address"))  // Extracts Result<Record>
)
.type("Address", builder -> builder
    .dataFetcher("street", new JooqRecordDataFetcher(ADDRESS.STREET))
    .dataFetcher("city", new JooqRecordDataFetcher(ADDRESS.CITY))
)
```

**Flow:**
1. User DataFetcher returns Records with "address" field containing `Result<Record>`
2. GraphQL-Java extracts the `Result<Record>`
3. GraphQL-Java iterates each Address Record
4. For each, calls Address field DataFetchers

**Result:** No special handling needed. jOOQ multiset + GraphQL-Java traversal = works naturally.

### 💰 Benefits vs Current Architecture

| Aspect | Current (DTOs) | After GEP-002 |
|--------|----------------|---------------|
| **Generated code** | ~100 lines per type | ~25 lines per type |
| **Layers** | DataFetcher → TypeMapper → DTO | DataFetcher → Record |
| **Selection set handling** | TypeMapper checks | GraphQL-Java handles |
| **Mapping logic** | Imperative (if/else) | Declarative (wiring) |
| **Code complexity** | High (multiple layers) | Low (single layer) |
| **Code reduction** | Baseline | **75% reduction** |
| **Foundation for GEP-003** | Complex, risky | Simple, safe |

### 🚨 Trade-offs (Acknowledged and Acceptable)

#### Accept: Over-fetching (Temporary)

**Cost:** Database fetches all columns
**Duration:** Until GEP-003 implementation
**Why acceptable:**
- Current system already over-fetches (not a regression)
- Most tables are narrow (10-15 columns) - negligible cost
- Correctness over optimization (get architecture right first)
- GraphQL-Java ensures only requested fields in response
- GEP-003 adds optimization from stable base

#### Accept: Generic Return Type

**Cost:** `Result<Record>` instead of `List<User>`
**Impact:** Less compile-time type safety
**Why acceptable:**
- Generated code is correct by construction
- Tests catch issues
- Simpler code = fewer places for bugs
- 75% code reduction outweighs type safety loss

### 🎯 Recommendation

**✅ IMPLEMENT after GEP-001 Phase 1**

**Why this is the right approach:**
1. **Massive simplification** - 75% reduction in generated code
2. **All concerns addressed** - Selection set, @splitQuery, nested data all work
3. **Enables GEP-003** - Clean base for adding query optimization
4. **Proven pattern** - GraphQL-Java designed for this approach
5. **Simpler = safer** - Fewer layers = fewer bugs

**Implementation order:**
1. Complete GEP-001 Phase 1 first (need clean config layer)
2. Implement GEP-002 (5-7 weeks)
3. Then add GEP-003 optimization from stable base

**Critical insight:** The "simplify first, optimize later" strategy is correct. Current architecture is too complex to safely optimize. Clean it up first.

---

## GEP-003: Selection-Set-Driven Query Generation

### Summary
Generate jOOQ queries that fetch only requested columns by parsing GraphQL selection set at runtime.

### ✅ Feasibility: HIGH

**Why this is feasible:**
- GraphQL-Java provides selection set API
- Pattern is proven (other GraphQL implementations do this)
- jOOQ supports dynamic column selection
- Backward compatible (opt-in per type)
- **After GEP-002:** All complexity in DataFetchers only (not split across TypeMappers)

### 📊 Implementation Complexity: MEDIUM

**What needs to be built:**

1. **Selection set parsing in DataFetchers** (~1200 lines, 2-3 weeks)
   - Generate if/else chains for each field
   - `if (selection.contains("email")) columns.add(USERS.EMAIL_ADDRESS);`
   - Risk: LOW - straightforward code generation

2. **Nested selection handling** (~800 lines, 1-2 weeks)
   - Recursive helper methods for multiset
   - Type-erased `Field<?>` to avoid jOOQ generic complexity
   - Risk: MEDIUM - deep nesting edge cases

3. **@splitQuery integration** (~600 lines, 1-2 weeks)
   - Pass selection set to DataLoader context
   - Build columns based on requested fields
   - Risk: MEDIUM - DataLoader context threading

4. **Configuration directive/flag** (~300 lines, 1 week)
   - `@selectiveQuery` directive on types
   - Maven plugin configuration
   - Risk: LOW

**Total: 4-6 weeks implementation**

### ⚠️ Critical Implementation Challenges

#### 1. Query Plan Caching Loss

**Impact:**
```sql
-- Different query per request
SELECT id, name FROM users WHERE id = ?
SELECT id, email FROM users WHERE id = ?
SELECT id FROM users WHERE id = ?
```

Database can't cache query plans when queries vary.

**Mitigation:**
- Make opt-in per type (`@selectiveQuery` directive)
- Only enable for wide tables (50+ columns)
- Document when to use vs not use
- Most queries will NOT use this (narrow tables)

#### 2. Runtime Overhead

**Cost:** ~1-5ms per DataFetcher for selection set parsing

For 20-field type:
```java
if (selection.contains("field1")) columns.add(TABLE.FIELD1);
if (selection.contains("field2")) columns.add(TABLE.FIELD2);
// ... 18 more checks
```

**When overhead > benefit:**
- Narrow tables (5-15 columns)
- Always-requested fields (id, name)
- Low-traffic internal APIs

**When benefit > overhead:**
- Wide tables (50+ columns)
- Large TEXT/BLOB fields rarely requested
- High-traffic public APIs

**Mitigation:** Opt-in configuration, clear documentation

#### 3. DataLoader Selection Set Propagation

**Challenge:** DataLoaders registered at startup, selection set varies per request

**Three possible solutions:**

**Option A: Per-request DataLoaders**
- Create new DataLoader instance per request with selection set
- Pro: Clean design
- Con: Breaks DataLoader caching across requests

**Option B: Context threading**
- Pass selection set through GraphQL context
- Pro: Reuses DataLoader instances
- Con: Global state, thread-safety concerns

**Option C: Selection set hashing**
- DataLoader key includes selection set hash
- Pro: Caching works
- Con: Complex key structure, many cache entries

**Recommendation:** Start with Option B (context threading), evaluate if caching is needed.

### 💰 Benefits vs Current

| Aspect | Current (After GEP-002) | After GEP-003 |
|--------|-------------------------|---------------|
| **Database** | Fetches all columns | Fetches only requested columns |
| **Network** | Full rows to app server | Minimal rows to app server |
| **Memory** | Full records in memory | Minimal records in memory |
| **Query plan caching** | Works | Lost (queries vary) |
| **Runtime overhead** | None | 1-5ms per DataFetcher |

**When benefits are significant:**
- Tables with 50+ columns where most are optional
- Large TEXT/BLOB columns (biography, resume_pdf)
- High-traffic APIs with bandwidth costs

**When benefits are negligible:**
- Tables with 5-15 columns (most tables)
- Always-requested fields
- Low-traffic internal APIs

### 🎯 Recommendation

**✅ IMPLEMENT after GEP-002 with strict opt-in guidelines**

**Why implement:**
- Real performance benefits for specific use cases
- Backward compatible (opt-in)
- No breaking changes
- Can be enabled incrementally

**Implementation strategy:**
1. Implement for simple fields first (no nesting)
2. Add nested inline queries (multiset)
3. Solve DataLoader selection set propagation
4. Add @splitQuery integration

**Mandatory requirements:**
1. **Must be opt-in** - `@selectiveQuery` directive or config flag
2. **Document when to use** - Decision tree in documentation
3. **Benchmarks required** - Show overhead vs benefit
4. **Logging support** - Log selection set for debugging

**Decision tree for users:**
```
Enable selection-set-driven queries when:
✅ Table has 50+ columns
✅ Most fields are optional
✅ Large TEXT/BLOB columns exist
✅ Bandwidth costs matter
✅ High traffic (1000+ req/sec)

Do NOT enable when:
❌ Table has <20 columns
❌ Most fields always requested
❌ Internal APIs only
❌ No performance problem exists
❌ Low traffic
```

---

## Cross-GEP Analysis

### Dependency Relationships

```
GEP-001 Phase 1 (Config layer foundation)
      ↓
GEP-002 (Simplify: remove DTOs, uses config)
      ↓
GEP-003 (Optimize: selection-aware queries, needs simple base)
      ↓
GEP-001 Phases 2-3 (Complete migration, delete ProcessedSchema)
```

**Key insights:**
1. **GEP-001 Phase 1 first** - Provides clean config layer for GEP-002
2. **GEP-002 before GEP-003** - Must simplify before optimizing
3. **GEP-003 after GEP-002** - Selection logic only in DataFetchers (not split across layers)
4. **GEP-001 Phases 2-3 last** - Complete migration after proving new architecture works

### Implementation Timeline

| Phase | Duration | Risk | Cumulative |
|-------|----------|------|------------|
| **GEP-001 Phase 1** | 2-3 weeks | LOW | 2-3 weeks |
| **GEP-002** | 5-7 weeks | MEDIUM | 7-10 weeks |
| **GEP-003** | 4-6 weeks | LOW-MEDIUM | 11-16 weeks |
| **GEP-001 Phases 2-3** | 5-7 weeks | LOW-MEDIUM | 16-23 weeks |

**Total: 16-23 weeks for complete architectural transformation**

### Effort Breakdown

| GEP | New Code | Deleted Code | Net Change |
|-----|----------|--------------|------------|
| **GEP-001 (all phases)** | ~2,000 | ~2,500 | -500 lines |
| **GEP-002** | ~1,500 | ~3,000+ | -1,500+ lines |
| **GEP-003** | ~2,900 | 0 | +2,900 lines |
| **Total** | ~6,400 | ~5,500 | +900 lines |

**Net result:** Similar code size, but **vastly improved architecture**
- Eliminated God Object (ProcessedSchema)
- Eliminated duplicate layers (DTOs, TypeMappers)
- Added optimization capability (selection-aware queries)
- Cleaner, more maintainable codebase

---

## Risks and Mitigations

### Risk: Config Structure Incomplete

**GEP-001 Phase 1 risk**
- Likelihood: MEDIUM
- Impact: HIGH (need to redesign)

**Mitigation:**
- Phase 1 runs in parallel with existing code
- Discover gaps early before committing to migration
- Keep ProcessedSchema until Phase 3

### Risk: Breaking Changes Impact

**GEP-002 breaking change**
- Likelihood: HIGH (definitely will break)
- Impact: HIGH (all users must regenerate)

**Mitigation:**
- Major version bump (1.x → 2.0)
- Clear migration guide
- Test with example project first
- Communicate breaking change clearly

### Risk: Performance Regression

**GEP-003 concern**
- Likelihood: MEDIUM (for narrow tables)
- Impact: MEDIUM (1-5ms overhead)

**Mitigation:**
- Opt-in only (not enabled by default)
- Document decision criteria clearly
- Benchmark before enabling
- Can disable if problems arise

---

## Final Recommendations

### Tier 1: Implement These (High Value, Ready)

**✅ GEP-001 Phase 1** (2-3 weeks, LOW risk)
- Build config layer and validator
- Run in parallel to prove concept
- Improves error messages immediately
- Foundation for GEP-002

**✅ GEP-002** (5-7 weeks, MEDIUM risk, after GEP-001 Phase 1)
- 75% reduction in generated code
- Simplifies architecture dramatically
- All design concerns addressed
- Foundation for GEP-003

**✅ GEP-003** (4-6 weeks, LOW-MEDIUM risk, after GEP-002)
- Real performance benefits for specific cases
- Opt-in, backward compatible
- No breaking changes
- Can be enabled incrementally

**✅ GEP-001 Phases 2-3** (5-7 weeks, LOW-MEDIUM risk, after GEP-003)
- Complete config migration
- Delete ProcessedSchema
- Clean up architecture

### Recommended Action Plan

**Weeks 1-3: GEP-001 Phase 1**
- Build CodeGenerationConfig structure
- Implement parser and validator
- Run in parallel, validate approach
- Improve error messages

**Weeks 4-10: GEP-002**
- Implement JooqRecordDataFetcher
- Generate RuntimeWiring instead of DTOs/TypeMappers
- Remove DTO/TypeMapper generation
- Test with example project
- **Checkpoint:** Verify 75% code reduction achieved

**Weeks 11-16: GEP-003**
- Implement selection-aware query generation
- Make opt-in via @selectiveQuery directive
- Document when to enable
- Add benchmarks showing benefits
- **Checkpoint:** Verify optimization works for wide tables

**Weeks 17-23: GEP-001 Phases 2-3**
- Migrate generators to use config
- Eliminate 248 ProcessedSchema queries
- Delete ProcessedSchema (1,323 lines)
- **Checkpoint:** Verify all generators work with config

---

## Key Insights

### 1. All Three GEPs Are Well-Designed

After deep analysis and clarifications:
- **GEP-001:** Solves real architectural problems (not just DX)
- **GEP-002:** All design concerns addressed (selection set, @splitQuery, nested data)
- **GEP-003:** Clear opt-in strategy for performance optimization

### 2. The Simplify-First Strategy Is Correct

**Current architecture is too complex to optimize safely:**
- ProcessedSchema: 1,323 lines, 248 queries
- TypeMappers: selection set checking + field mapping
- DTOs: duplicate schema structure

**Strategy:**
1. Clean up architecture (GEP-002)
2. Then optimize (GEP-003)

**Result:** Selection-aware queries only need to modify DataFetchers, not split across layers.

### 3. GEP-001 Phase 1 Provides Foundation

**Key insight:** Building config layer first enables:
- Clean field mappings for GEP-002 wiring generation
- Type metadata for GEP-003 to know which fields are DB-mapped
- Better error messages as immediate benefit
- Safe migration path (runs in parallel)

### 4. Implementation Order Matters

**Correct order:**
```
GEP-001 Phase 1 → GEP-002 → GEP-003 → GEP-001 Phases 2-3
```

**Why:**
- GEP-001 Phase 1: Foundation for others
- GEP-002: Must simplify before optimizing
- GEP-003: Needs simple base to safely add complexity
- GEP-001 Phases 2-3: Complete migration after validation

### 5. Breaking Changes Are Worth It

**GEP-002 is breaking, but:**
- 75% reduction in generated code
- Vastly simpler architecture
- Foundation for future improvements
- Major version bump (1.x → 2.0) signals this clearly

---

## Conclusion

**All three GEPs are ready for implementation** with clear designs, safe migration paths, and well-understood trade-offs.

**Total transformation:** 16-23 weeks
**Net code change:** +900 lines but vastly improved architecture
**Major benefits:**
- Replace God Object with clean config
- 75% reduction in generated code
- Foundation for optimization
- Better error messages
- Simpler, more maintainable codebase

**Recommendation:** Begin with GEP-001 Phase 1 (2-3 weeks) to prove the approach, then proceed with full implementation plan.
