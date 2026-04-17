# Rewrite Model — Visual Reference

Colour legend:

| Symbol | Meaning |
|---|---|
| 🔴 Red / bold | Core sealed interfaces — structural backbone |
| 🟢 Teal | `TableTargetField` group — primary SQL-generation abstraction |
| 🔵 Blue | `QueryField` / `MutationField` — entry-point fields on root types |
| 🟩 Green | `GraphitronType` variants |
| 🟣 Purple | Support / composition types |
| ⚫ Dark grey | Value / leaf types — stable, rarely changed |

---

## 1. Field Hierarchy

```mermaid
graph LR
    classDef core  fill:#922B21,stroke:#7B241C,color:#fff,font-weight:bold
    classDef ttf   fill:#0E6655,stroke:#0A5344,color:#fff,font-weight:bold
    classDef rootf fill:#1A5276,stroke:#154360,color:#fff
    classDef val   fill:#4A5568,stroke:#2C3E50,color:#fff

    GF["GraphitronField\n«sealed»"]:::core

    GF --> RF["RootField\n«sealed»"]:::rootf
    GF --> CF["ChildField\n«sealed»"]:::core
    GF --> INPF["InputField\n«sealed»"]:::rootf
    GF --> UNF["UnclassifiedField"]:::val
    GF --> NGF["NotGeneratedField"]:::val

    RF --> QF["QueryField\n«sealed»"]:::rootf
    RF --> MF["MutationField\n«sealed»"]:::rootf

    INPF --> InpCF["ColumnField"]:::rootf

    CF --> TTF["TableTargetField\n«sealed»"]:::ttf
    CF --> ColF["ColumnField"]:::core
    CF --> CRF["ColumnReferenceField"]:::core
    CF --> NIF["NodeIdField"]:::core
    CF --> NIRF["NodeIdReferenceField"]:::core
    CF --> TMF["TableMethodField"]:::core
    CF --> NstF["NestingField"]:::core
    CF --> IFld["InterfaceField"]:::core
    CF --> UnFld["UnionField"]:::core
    CF --> CnsF["ConstructorField"]:::core
    CF --> SvcRF["ServiceRecordField"]:::core
    CF --> RecF["RecordField"]:::core
    CF --> CmpF["ComputedField"]:::core
    CF --> PropF["PropertyField"]:::core
    CF --> MtRF["MultitableReferenceField"]:::core
```

---

## 2. Root Field Variants

```mermaid
graph LR
    classDef rootf fill:#1A5276,stroke:#154360,color:#fff
    classDef ttf   fill:#0E6655,stroke:#0A5344,color:#fff,font-weight:bold

    QF["QueryField\n«sealed»"]:::rootf
    QF --> QTF["QueryTableField"]:::rootf
    QF --> QLF["QueryLookupTableField"]:::rootf
    QF --> QTI["QueryTableInterfaceField"]:::rootf
    QF --> QTMF["QueryTableMethodTableField\n+ method"]:::rootf
    QF --> QNF["QueryNodeField"]:::rootf
    QF --> QEF["QueryEntityField"]:::rootf
    QF --> QIF["QueryInterfaceField"]:::rootf
    QF --> QUF["QueryUnionField"]:::rootf
    QF --> QSTF["QueryServiceTableField\n+ method"]:::rootf
    QF --> QSRF["QueryServiceRecordField\n+ method"]:::rootf

    MF["MutationField\n«sealed»"]:::rootf
    MF --> MIF["MutationInsertTableField"]:::rootf
    MF --> MUF["MutationUpdateTableField"]:::rootf
    MF --> MDF["MutationDeleteTableField"]:::rootf
    MF --> MUpF["MutationUpsertTableField"]:::rootf
    MF --> MSTF["MutationServiceTableField\n+ method"]:::rootf
    MF --> MSRF["MutationServiceRecordField\n+ method"]:::rootf

    TTF["TableTargetField\n«sealed»"]:::ttf
    TTF --> TF["TableField"]:::ttf
    TTF --> STF["SplitTableField\n+ batchKey"]:::ttf
    TTF --> LF["LookupTableField"]:::ttf
    TTF --> SLF["SplitLookupTableField\n+ batchKey"]:::ttf
    TTF --> TIF["TableInterfaceField"]:::ttf
    TTF --> SVCTF["ServiceTableField\n+ method · batchKey"]:::ttf
    TTF --> RTF["RecordTableField"]:::ttf
    TTF --> RLF["RecordLookupTableField"]:::ttf
```

All `TableTargetField` variants carry `returnType · joinPath · filters · orderBy · pagination`.
`QueryTableField`, `QueryLookupTableField`, and `QueryTableInterfaceField` carry `returnType · filters · orderBy · pagination` (no `joinPath` — no parent table to navigate from).

---

## 3. Type Hierarchy

```mermaid
graph LR
    classDef core  fill:#922B21,stroke:#7B241C,color:#fff,font-weight:bold
    classDef typeh fill:#145A32,stroke:#196F3D,color:#fff
    classDef val   fill:#4A5568,stroke:#2C3E50,color:#fff

    GT["GraphitronType\n«sealed»"]:::core

    GT --> TBT["TableBackedType\n«sealed»"]:::typeh
    TBT --> TT["TableType"]:::typeh
    TBT --> NT["NodeType"]:::typeh
    TBT --> TIT["TableInterfaceType"]:::typeh

    GT --> ResT["ResultType\n«sealed»"]:::typeh
    ResT --> JRT["JavaRecordType"]:::typeh
    ResT --> PRT["PojoResultType"]:::typeh
    ResT --> JRRT["JooqRecordType"]:::typeh
    ResT --> JTRT["JooqTableRecordType"]:::typeh

    GT --> InpT["InputType\n«sealed»"]:::typeh
    InpT --> JRIT["JavaRecordInputType"]:::typeh
    InpT --> PIT["PojoInputType"]:::typeh
    InpT --> JooqRI["JooqRecordInputType"]:::typeh
    InpT --> JTRIT["JooqTableRecordInputType"]:::typeh

    GT --> RootT["RootType"]:::typeh
    GT --> IntT["InterfaceType"]:::typeh
    GT --> UnT["UnionType"]:::typeh
    GT --> ErrT["ErrorType"]:::typeh
    GT --> TInpT["TableInputType"]:::typeh
    GT --> UncT["UnclassifiedType"]:::typeh
```

---

## 4. Support / Composition Types

```mermaid
graph LR
    classDef sup fill:#6C3483,stroke:#5B2C6F,color:#fff
    classDef val fill:#4A5568,stroke:#2C3E50,color:#fff

    RTR["ReturnTypeRef\n«sealed»"]:::sup
    RTR --> TBRT["TableBoundReturnType"]:::sup
    RTR --> PolRT["PolymorphicReturnType"]:::sup
    RTR --> ResRTR["ResultReturnType"]:::sup
    RTR --> ScRTR["ScalarReturnType"]:::sup

    WF["WhereFilter\n«sealed»"]:::sup
    WF --> CoF["ConditionFilter"]:::sup
    WF --> GCF["GeneratedConditionFilter"]:::sup

    OBS["OrderBySpec\n«sealed»"]:::sup
    OBS --> OBF["Fixed"]:::sup
    OBS --> OBA["Argument"]:::sup
    OBS --> OBN["None"]:::sup

    PSp["PaginationSpec\nfirst · last · after · before · defaultPageSize"]:::val

    FW["FieldWrapper\n«sealed»"]:::sup
    FW --> SFW["Single"]:::sup
    FW --> LFW["List"]:::sup
    FW --> CFW["Connection"]:::sup

    JS["JoinStep\n«sealed»"]:::sup
    JS --> FKJ["FkJoin"]:::sup
    JS --> CJ["ConditionJoin"]:::sup

    TR["TableRef"]:::val
    CR["ColumnRef"]:::val
    MR["MethodRef"]:::val
    PS["ParamSource\n«sealed»"]:::val
    SR["BatchKey\n«sealed»"]:::val
    PR["ParticipantRef\n«sealed»"]:::sup
    PR --> PRB["TableBound\ntypeName · table · discriminatorValue"]:::val
    PR --> PRU["Unbound\ntypeName"]:::val
```

`WhereFilter` has two variants: `ConditionFilter` (a developer-supplied `@condition` method on a `FIELD_DEFINITION`, implements both `WhereFilter` and `MethodRef`) and `GeneratedConditionFilter` (a Graphitron-generated filter built from filterable arguments).

`ParticipantRef` is a sealed interface with two variants: `TableBound(typeName, table, discriminatorValue)` and `Unbound(typeName)`. Non-table-backed members (e.g. `ErrorType`, structural interfaces) are recorded as `Unbound`. Generator switches must handle both variants and skip SQL-emitting paths for `Unbound`.

---

## 5. Key Compositions (HAS-A)

| Holder | Field | Type |
|---|---|---|
| `TableTargetField` | `returnType` | `TableBoundReturnType` |
| `TableTargetField` | `joinPath` | `List<JoinStep>` |
| `TableTargetField` | `filters` | `List<WhereFilter>` |
| `TableTargetField` | `orderBy` | `OrderBySpec` |
| `TableTargetField` | `pagination` | `PaginationSpec?` |
| `QueryTableField` / `QueryLookupTableField` / `QueryTableInterfaceField` | `filters` | `List<WhereFilter>` |
| `QueryTableField` / `QueryLookupTableField` / `QueryTableInterfaceField` | `orderBy` | `OrderBySpec` |
| `QueryTableField` / `QueryLookupTableField` / `QueryTableInterfaceField` | `pagination` | `PaginationSpec?` |
| `TableBoundReturnType` | `table` | `TableRef` |
| `TableBackedType` | `table` | `TableRef` |
| `TableRef` | `primaryKey?` | `List<ColumnRef>?` |
| `ConditionFilter` | `className · methodName · params` | implements `WhereFilter` and `MethodRef` — signature: `(Table tgt, Arg...)` |
| `FkJoin` | `whereFilter?` | `MethodRef?` — signature: `(SourceTable src, Table tgt)` |
| `ConditionJoin` | `condition` | `MethodRef` — signature: `(SourceTable src, Table tgt)` |
| `QueryServiceTableField` | `method` | `MethodRef` |
| `MutationServiceTableField` | `method` | `MethodRef` |
| `ServiceTableField` (child) | `method` | `MethodRef` |

---

## Notes on potential cleanup

### Structural redundancy in `TableTargetField`

`TableField`, `SplitTableField`, `LookupTableField`, `SplitLookupTableField`, `RecordTableField`,
and `RecordLookupTableField` all share the same component set
(`returnType · joinPath · filters · orderBy · pagination`). The only classifying difference is:

| Type | Parent context | Split query | Lookup key |
|---|---|---|---|
| `TableField` | table-mapped | ✗ | ✗ |
| `SplitTableField` | table-mapped | ✓ | ✗ |
| `LookupTableField` | table-mapped | ✗ | ✓ |
| `SplitLookupTableField` | table-mapped | ✓ | ✓ |
| `RecordTableField` | result-mapped | ✗ | ✗ |
| `RecordLookupTableField` | result-mapped | — | ✓ |

These could potentially be collapsed into fewer types with boolean flags, or further intermediate
sealed interfaces (e.g., `StandardTableField permits TableField, SplitTableField`,
`RecordBoundField permits RecordTableField, RecordLookupTableField`).

### `QueryField` mirrors `ChildField`

Several `QueryField` variants structurally mirror their `ChildField` counterparts:

| `QueryField` | `ChildField` counterpart |
|---|---|
| `QueryTableField` | `TableField` / `SplitTableField` |
| `QueryLookupTableField` | `LookupTableField` / `SplitLookupTableField` |
| `QueryTableInterfaceField` | `TableInterfaceField` |
| `QueryServiceTableField` | `ServiceTableField` |
| `QueryServiceRecordField` | `ServiceRecordField` |

The only structural difference is that root fields have no `joinPath` — there is no parent table
to FK-navigate from. Both the child and root variants listed above carry the same
`filters · orderBy · pagination` triple via `SqlGeneratingField`.
`QueryTableMethodTableField` and `QueryServiceTableField` intentionally do not carry those
components — the developer-controlled method/service replaces SQL generation entirely.

Whether a shared interface between root and child table-bound fields could capture the common
parts (`returnType · filters · orderBy · pagination`) is worth exploring.

### `TableTargetField` interface vs. `NestingField`

`NestingField` carries `ReturnTypeRef.TableBoundReturnType` but is intentionally excluded from
`TableTargetField` because it does not navigate to a new table scope. This exclusion is
architecturally correct but worth documenting clearly at the use sites.

### `ConditionJoin` vs. `FkJoin.whereFilter` vs. `ConditionFilter`

All three hold (or are) a `MethodRef`, which is intentional: `MethodRef` is the general model-level
representation of any user-provided Java method (the javadoc says so explicitly). What varies is
the calling convention, and that is already encoded per-parameter in `MethodRef.Param.source`
via `ParamSource`:

| Use site | `ParamSource` sequence | Generated call |
|---|---|---|
| `ConditionJoin.condition` | `SourceTable`, `Table` | `method(srcAlias, tgtAlias)` → ON clause |
| `FkJoin.whereFilter` | `SourceTable`, `Table` | `method(srcAlias, tgtAlias)` → WHERE clause |
| `ConditionFilter` (implements `MethodRef`) | `Table`, then `Arg`/`Context`... | `method(tgtTable, arg1, ...)` → WHERE predicate |

The interesting structural observation is that `ConditionJoin.condition` and `FkJoin.whereFilter`
share an identical calling convention (`SourceTable, Table → Condition`), while
`ConditionFilter` is structurally different (`Table, Arg... → Condition`). The model does
not express this grouping. A potential improvement: introduce a `JoinConditionRef` wrapper used
in both join-step types to make the shared `(source, target)` contract explicit in the type system
and separate it cleanly from the field-condition contract.
