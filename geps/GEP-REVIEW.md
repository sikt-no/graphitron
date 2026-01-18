# GEP Review: Feasibility and Trade-off Analysis

**Date:** 2026-01-18
**Reviewer:** Analysis of GEP-001, GEP-002, and GEP-003

This document provides a rigorous review of the three proposed GEPs, examining feasibility, implementation complexity, risks, and trade-offs.

---

## GEP-001: Parse-and-Validate Architecture

### Summary
Replace monolithic `ProcessedSchema` with two-phase parse-then-validate architecture for better error messages and clearer separation of concerns.

### ✅ Feasibility: HIGH

**Why this is feasible:**
- GraphQL-Java's `SchemaTraverser` API is stable and well-documented
- Sealed interfaces (Java 17+) provide compile-time exhaustiveness checking
- Pattern is well-established (compilers, linters, etc.)
- No runtime performance implications (all at code generation time)

**Technical risks: LOW**
- `SchemaTraverser` API is mature and unlikely to change
- No reflection at runtime (only during code generation)
- Testable in isolation (each phase can be unit tested)

### 📊 Implementation Complexity: MEDIUM-HIGH

**What needs to be built:**

1. **15 Mapping types** (sealed interface hierarchy)
   - Estimated: 300-500 lines per mapping type
   - Total: ~4500-7500 lines of code
   - Risk: Complexity in ensuring all edge cases are covered

2. **CodeGenerationConfig** and metadata classes
   - Estimated: 500-1000 lines
   - Risk: Need to handle all current directive combinations

3. **Parser implementation**
   - Estimated: 1000-1500 lines
   - Risk: Must replicate all current reflection-based lookup logic
   - Challenge: Current code is tightly coupled; extraction will be painful

4. **Validator implementation**
   - Estimated: 1500-2000 lines
   - Risk: Need comprehensive validation rules for all 15 mapping types
   - Challenge: Error message generation for every failure case

5. **Rich error messages**
   - Estimated: 500-1000 lines
   - Risk: Need to preserve source locations through entire pipeline

**Total estimated: 8000-12000 lines of new code**

### ⚠️ Critical Implementation Challenges

1. **Extracting from ProcessedSchema**
   - Current code is a ~3000-line monolith
   - Logic is interwoven (parsing + validation + generation)
   - No clear boundaries between phases
   - **Risk:** Incomplete extraction leads to silent failures

2. **Reflection isolation**
   - Current code uses reflection throughout
   - Must identify ALL reflection points
   - Must ensure validator never does reflection
   - **Risk:** Accidental reflection in validator breaks architecture

3. **Maintaining current behavior**
   - Must replicate exact same logic
   - Current code has undocumented edge cases
   - No comprehensive test coverage of ProcessedSchema
   - **Risk:** Subtle behavioral changes break existing schemas

4. **Backward compatibility**
   - Users may depend on ProcessedSchema public API
   - Some internal tools might use it directly
   - **Risk:** Breaking changes for internal users

### 💰 Benefits vs Costs

| Benefit | Impact | Cost |
|---------|--------|------|
| Better error messages | HIGH - major DX improvement | Medium - need error type per failure |
| Testability | HIGH - can test phases independently | Low - natural consequence |
| Maintainability | MEDIUM - clearer structure | Low - natural consequence |
| Fail-fast | HIGH - all errors at once | Low - natural consequence |

**Verdict:** Benefits are primarily developer experience (DX) improvements, not runtime performance or correctness. High implementation cost (2-3 months full-time work) for DX benefit.

### 🚨 Risks

1. **Scope creep**
   - Starting to refactor ProcessedSchema will reveal more problems
   - Temptation to "fix everything while we're here"
   - **Mitigation:** Strict scope - only parse/validate separation

2. **Incomplete validation**
   - Missing validation rules = silent failures return
   - Hard to know when you've covered everything
   - **Mitigation:** Comprehensive integration tests

3. **Performance regression**
   - Two passes over schema instead of one
   - More object allocation (metadata classes)
   - **Mitigation:** Benchmark before/after

### 🎯 Recommendation

**DEFER until pain point is clear.**

**Rationale:**
- This is a quality-of-life improvement, not a bug fix or feature
- High implementation cost (2-3 months)
- Current system works, just produces poor error messages
- GEP-002 and GEP-003 don't depend on this
- Better to implement when you have a specific error message problem to solve

**Alternative approach:**
- Start smaller: Add structured errors to ONE mapping type (e.g., TableMapping)
- Learn from that experience
- Decide if full refactor is worth it

---

## GEP-002: Simplify Mapping with JooqRecordDataFetcher

### Summary
Eliminate DTO layer. DataFetchers return `Result<Record>` directly. Use RuntimeWiring to extract values via `JooqRecordDataFetcher`.

### ✅ Feasibility: MEDIUM-HIGH

**Why this is feasible:**
- GraphQL-Java supports this pattern (PropertyDataFetcher proves it works)
- jOOQ Records are stable and well-understood
- RuntimeWiring generation is straightforward

**Why this is challenging:**
- **Major breaking change** - eliminates entire layer of generated code
- Nested data handling becomes more complex
- Edge cases in current system may be hidden

### 📊 Implementation Complexity: MEDIUM

**What needs to be built:**

1. **JooqRecordDataFetcher class**
   - Estimated: 100-200 lines
   - Simple class, similar to PropertyDataFetcher
   - **Risk: LOW**

2. **RuntimeWiring generation**
   - Estimated: 500-1000 lines
   - Generate `.dataFetcher()` calls for every field
   - Must handle all current @field mappings
   - **Risk: MEDIUM** - edge cases in field name mapping

3. **Remove DTO/TypeMapper generation**
   - Estimated: Delete ~3000-5000 lines
   - Clean up generator code
   - **Risk: LOW** - just removal

4. **Update DataFetcher generation**
   - Estimated: 500-1000 lines of changes
   - Change return type from `List<DTO>` to `Result<Record>`
   - Remove mapping calls
   - **Risk: LOW**

5. **Nested data handling**
   - Estimated: 1000-2000 lines
   - Must use multiset/JSON aggregation
   - Complex for deep nesting
   - **Risk: HIGH** - this is the hard part

**Total estimated: 2000-4000 lines of code + deletions**

### ⚠️ Critical Implementation Challenges

1. **Nested Data is Complex**

Current approach:
```java
// Clean, explicit
Order order = mapper.mapOrder(record);
List<OrderItem> items = order.getItems();
```

Proposed approach:
```java
// Must use multiset or multiple queries
multiset(select(ORDER_ITEMS.fields())
  .from(ORDER_ITEMS)
  .where(ORDER_ITEMS.ORDER_ID.eq(ORDERS.ID))
).as("items")
```

**Problems:**
- jOOQ multiset requires database support (PostgreSQL arrays, JSON functions)
- Not all databases support multiset
- Complex queries become harder to read
- Debugging is harder (can't inspect intermediate DTOs)

2. **@splitQuery Interaction**

Current system uses DTOs as boundaries between split queries. Without DTOs:
- How do you pass data between DataFetchers?
- Records from different tables can't be stored in same Record
- Need to invent new conventions

**Example problem:**
```graphql
type User {
  address: Address @splitQuery
}
```

Current: `User` DTO has `Address` field, DataLoader populates it
Proposed: User Record has... what? A placeholder? How does wiring work?

**This is not fully designed in the GEP.**

3. **Type Safety Loss**

Current:
```java
public List<User> getUsers() {
  return users; // Type-safe
}
```

Proposed:
```java
public Result<Record> getUsers() {
  return records; // Generic Record, no type safety
}
```

**Impact:**
- Errors caught only at runtime
- Can't use IDE refactoring tools
- Harder to understand code

4. **Selection Set Problem**

GEP says "No runtime checking needed" but this is **wrong**.

Current:
```java
// TypeMapper checks selection set, doesn't populate unrequested fields
if (selection.contains("email")) {
  user.setEmail(record.get(USERS.EMAIL));
}
```

Proposed:
```java
// JooqRecordDataFetcher ALWAYS extracts field from Record
return record.get(USERS.EMAIL); // What if email wasn't selected?
```

**Problem:** If query doesn't fetch `email`, Record won't have it. `record.get(USERS.EMAIL)` will throw or return null unexpectedly.

**Solution:** Still need selection-set-aware queries (GEP-003), or accept over-fetching.

**This is a critical design flaw.**

### 💰 Benefits vs Costs

| Benefit | Impact | Cost |
|---------|--------|------|
| Less generated code | MEDIUM - ~3000 lines removed | LOW |
| Simpler architecture | HIGH - fewer layers | MEDIUM - complexity shifts to wiring |
| No DTO maintenance | LOW - DTOs are generated, not maintained | N/A |
| Faster compilation | LOW - marginal improvement | N/A |

**Costs:**
| Cost | Impact |
|------|--------|
| **Breaking change** | HUGE - all existing code breaks |
| **Type safety loss** | HIGH - runtime errors instead of compile-time |
| **Nested data complexity** | HIGH - multiset/JSON required |
| **Database dependency** | MEDIUM - features require modern DB |
| **Debugging harder** | MEDIUM - can't inspect DTOs |

### 🚨 Risks

1. **Massive breaking change**
   - Every user must regenerate code
   - Every user must update custom code
   - No migration path (can't support both)
   - **Impact: CRITICAL**

2. **Incomplete design**
   - @splitQuery interaction not fully specified
   - Selection set problem not addressed
   - Nested data complexity underestimated
   - **Impact: HIGH** - implementation will uncover issues

3. **Performance unknowns**
   - Multiset performance vs separate queries?
   - GraphQL-Java traversal overhead?
   - Memory usage with Records vs DTOs?
   - **Impact: MEDIUM** - need benchmarks

4. **Database compatibility**
   - Multiset requires modern PostgreSQL/MySQL
   - What about Oracle? SQL Server? H2?
   - Falls back to separate queries anyway
   - **Impact: MEDIUM** - limits usability

### 🎯 Recommendation

**DO NOT IMPLEMENT without fixing critical flaws.**

**Critical flaws:**
1. **Selection set problem** - Must be solved first (requires GEP-003 or accepting over-fetching)
2. **@splitQuery design** - Needs complete specification
3. **Nested data strategy** - Need fallback for databases without multiset

**Alternative approach:**
1. **Phase 1:** Implement GEP-003 first (selection-aware queries)
2. **Phase 2:** Prototype this with selection-awareness built in
3. **Phase 3:** Evaluate if benefits outweigh costs
4. **Phase 4:** If yes, create migration tool before shipping

**If benefits don't justify breaking changes, consider:**
- Keep DTOs but simplify generation (reduce boilerplate)
- Make DTOs optional (provide both modes)
- Focus on improving what exists rather than replacement

---

## GEP-003: Selection-Set-Driven Query Generation

### Summary
Generate queries that fetch only requested columns by parsing GraphQL selection set at runtime.

### ✅ Feasibility: HIGH

**Why this is feasible:**
- GraphQL-Java provides selection set API
- Pattern is proven (other GraphQL implementations do this)
- jOOQ supports dynamic column selection
- Backward compatible (can be opt-in)

### 📊 Implementation Complexity: MEDIUM

**What needs to be built:**

1. **Selection set parsing in DataFetchers**
   - Estimated: 1000-1500 lines
   - Generate if/else chains for each field
   - **Risk: LOW** - straightforward code generation

2. **Nested selection handling**
   - Estimated: 500-1000 lines
   - Recursive helper methods
   - **Risk: MEDIUM** - deep nesting complexity

3. **@splitQuery integration**
   - Estimated: 500-1000 lines
   - Pass selection set to DataLoader
   - **Risk: MEDIUM** - DataLoader context handling

4. **Configuration directive/flag**
   - Estimated: 200-300 lines
   - @selectiveQuery directive
   - Maven plugin configuration
   - **Risk: LOW**

**Total estimated: 2200-3800 lines of new code**

### ⚠️ Critical Implementation Challenges

1. **Query Plan Caching Loss**

**Current behavior:**
```sql
-- Same query every time
SELECT id, name, email, created_at FROM users WHERE id = $1
```
Database can cache query plan, reuse execution strategy.

**Proposed behavior:**
```sql
-- Different query per request
SELECT id, name FROM users WHERE id = $1
SELECT id, email, created_at FROM users WHERE id = $1
SELECT id FROM users WHERE id = $1
```

**Impact:**
- Database query plan cache thrashing
- No prepared statement caching benefits
- Slower for simple queries (overhead > savings)

**Mitigation:**
- Use only for wide tables (50+ columns)
- Opt-in per type
- Document when to enable

2. **Runtime Overhead**

Every DataFetcher call:
```java
// Added overhead
var selection = env.getSelectionSet();
var columns = new ArrayList<Field<?>>();
if (selection.contains("id")) columns.add(USERS.ID);
if (selection.contains("name")) columns.add(USERS.NAME);
// ... repeat for every field
```

For a 20-field type: 20 if-statements per request.

**Cost:** ~1-5ms per DataFetcher (rough estimate)

**When it matters:**
- High-traffic endpoints (1000+ req/sec)
- Small queries (few fields)
- Narrow tables (overhead > savings)

**When it doesn't:**
- Wide tables with rarely-used columns
- Low traffic
- Large blob/text fields

3. **Generated Code Size**

For a type with 30 fields, generates:
```java
if (selection.contains("field1")) columns.add(TABLE.FIELD1);
if (selection.contains("field2")) columns.add(TABLE.FIELD2);
// ... 28 more times
```

**Impact:**
- DataFetcher files become very long (500+ lines)
- Hard to read generated code
- More to compile

**Mitigation:**
- Extract to helper methods (per GEP)
- Still verbose but more organized

4. **DataLoader Complexity**

**Major challenge:** DataLoaders are registered at startup, but selection set varies per request.

**Problem:**
```java
public class OrdersDataLoader implements BatchLoader<Integer, List<Record>> {
  public CompletableFuture<List<List<Record>>> load(List<Integer> userIds) {
    var selection = /* WHERE DOES THIS COME FROM? */;
    var columns = buildColumns(selection);
    // ...
  }
}
```

**Solutions:**
1. **Per-request DataLoaders** - Create new DataLoader instance per request with selection set
   - Pro: Clean design
   - Con: Breaks DataLoader caching across requests

2. **Context threading** - Pass selection set through GraphQL context
   - Pro: Reuses DataLoader instances
   - Con: Global state, thread-safety concerns

3. **Selection set hashing** - DataLoader key includes selection set hash
   - Pro: Caching works
   - Con: Complex key structure, many cache entries

**The GEP doesn't specify which approach.** This is a significant design gap.

### 💰 Benefits vs Costs

| Benefit | Real Impact |
|---------|-------------|
| **Reduced bandwidth** | HIGH for wide tables, LOW for narrow |
| **Reduced memory** | MEDIUM - Records smaller in memory |
| **Database load** | MEDIUM - less data transferred |

**When benefits are significant:**
- Tables with 50+ columns where most are optional
- Large TEXT/BLOB columns rarely requested
- High-traffic APIs with bandwidth costs
- Example: User profile table with biography, profile_pic_data, resume_pdf

**When benefits are negligible:**
- Tables with 5-15 columns (most tables)
- Always-requested fields (id, name, etc.)
- Low-traffic internal APIs

**Costs:**
| Cost | Impact |
|------|--------|
| Runtime overhead | 1-5ms per DataFetcher |
| Query plan cache loss | Slower for repeated queries |
| Code complexity | Generated files harder to read |
| DataLoader complexity | Significant design challenge |

### 🚨 Risks

1. **Performance regression on narrow tables**
   - Overhead > savings for tables with <20 columns
   - Most tables in typical apps are narrow
   - **Impact: HIGH if enabled globally**
   - **Mitigation: Make opt-in per-type**

2. **DataLoader design not finalized**
   - Three possible approaches, each with trade-offs
   - Need to pick one and implement fully
   - **Impact: MEDIUM** - solvable but needs design work

3. **Debugging becomes harder**
   - Different SQL per request
   - Can't reproduce query from logs easily
   - **Impact: LOW-MEDIUM**
   - **Mitigation: Log selection set with queries**

### 🎯 Recommendation

**IMPLEMENT with strict guidelines on when to use.**

**Why implement:**
- Backward compatible (opt-in)
- Real performance benefits for specific cases
- No breaking changes
- Can be enabled incrementally

**Implementation order:**
1. **Phase 1:** Simple fields only (no nesting)
2. **Phase 2:** Nested inline queries (multiset)
3. **Phase 3:** Solve DataLoader design question
4. **Phase 4:** @splitQuery integration

**Mandatory requirements:**
1. **Must be opt-in** - Directive like `@selectiveQuery` or config flag
2. **Document when to use** - Include decision tree in docs
3. **Benchmarks required** - Show when overhead > benefit
4. **Logging support** - Log selection set for debugging

**When to enable:**
```
Enable selection-set-driven queries when:
✅ Table has 50+ columns
✅ Most fields are optional
✅ Large TEXT/BLOB columns exist
✅ Bandwidth costs matter

Do NOT enable when:
❌ Table has <20 columns
❌ Most fields always requested
❌ Internal APIs only
❌ No performance problem exists
```

---

## Cross-GEP Analysis

### Dependency Graph

```
GEP-001 (Parse-Validate)
   ↓ (optional - provides better error messages)
GEP-002 (Remove DTOs) ← requires → GEP-003 (Selection-aware queries)
   ↑                                       ↓
   └──────────────────────────────────────┘
        (GEP-002 requires GEP-003 to avoid selection set bug)
```

### Implementation Order

**If implementing multiple GEPs:**

1. **GEP-003 first** (selection-aware queries)
   - Independent, backward compatible
   - Solves over-fetching problem
   - Prerequisite for GEP-002

2. **GEP-002 second** (remove DTOs) - **IF benefits proven**
   - Requires GEP-003 to work correctly
   - Major breaking change
   - Consider NOT doing this

3. **GEP-001 last** (parse-validate) - **IF DX pain is real**
   - Quality-of-life improvement
   - No dependencies on others
   - Defer until needed

### Total Implementation Effort

Conservative estimates:

| GEP | Lines of Code | Time Estimate | Risk Level |
|-----|---------------|---------------|------------|
| GEP-001 | 8,000-12,000 | 2-3 months | MEDIUM |
| GEP-002 | 2,000-4,000 + major design work | 1-2 months | HIGH |
| GEP-003 | 2,200-3,800 | 1-2 months | LOW-MEDIUM |

**Sequential implementation: 4-7 months**

---

## Final Recommendations

### Tier 1: Do This

**✅ GEP-003: Selection-Set-Driven Query Generation**
- Real performance benefits for specific use cases
- Backward compatible (opt-in)
- No breaking changes
- Proven pattern in industry
- **Start here, make it opt-in, document when to use**

### Tier 2: Consider This (with caution)

**⚠️ GEP-002: Simplify Mapping with JooqRecordDataFetcher**
- Has merit but significant trade-offs
- **Critical flaws must be fixed first:**
  1. Selection set problem (requires GEP-003)
  2. @splitQuery design incomplete
  3. Nested data strategy unclear
- **Recommendation:** Prototype first, evaluate if benefits justify breaking changes
- **Alternative:** Keep DTOs, simplify generation instead

### Tier 3: Defer This

**⏸️ GEP-001: Parse-and-Validate Architecture**
- High implementation cost (2-3 months)
- Benefits are developer experience, not features/performance
- Current system works (just poor error messages)
- No dependencies from other GEPs
- **Recommendation:** Wait until error message pain is severe enough to justify investment

---

## Recommended Action Plan

### Short Term (Next 3 months)

1. **Implement GEP-003** (selection-aware queries)
   - Make opt-in via `@selectiveQuery` directive
   - Document decision criteria
   - Add benchmarks showing when it helps

2. **Monitor usage and feedback**
   - Where do people enable it?
   - What performance improvements?
   - What problems arise?

### Medium Term (3-6 months)

3. **Prototype GEP-002** (remove DTOs)
   - Fix selection set problem using GEP-003
   - Design @splitQuery interaction
   - Build proof-of-concept with Sakila example
   - Compare complexity and performance

4. **Decision point:** Ship GEP-002 or abandon it
   - If benefits clear: Create migration guide and tool
   - If benefits unclear: Abandon, improve DTOs instead

### Long Term (6-12 months)

5. **Evaluate GEP-001** (parse-validate)
   - Collect error message complaints
   - If pain is severe: Start with one mapping type
   - If pain is low: Defer indefinitely

---

## Key Insights

1. **GEP-002 has a critical design flaw** - Selection set problem not addressed. Cannot work without GEP-003 or accepting over-fetching.

2. **GEP-003 is the safest bet** - Backward compatible, opt-in, real benefits for specific cases, no breaking changes.

3. **GEP-001 solves a real problem but at high cost** - Better error messages are valuable but implementation cost (2-3 months) may not justify it yet.

4. **Breaking changes are expensive** - GEP-002 eliminates entire layer. Only worth it if benefits are overwhelming (currently unclear).

5. **Prototype before committing** - GEP-002 needs proof-of-concept to validate approach before major implementation.
