# R1015 acceptance on sis: the lookup launcher's ON join runs on Oracle

Observed 2026-10-13 on sis (`sis/upgrade-graphitron`, clean tree), running the IT suite
`mvn verify -Psystemtest-ci -pl :fsapi-rest` against the local Oracle container (`fslokal:9.6.0.8`).
The run uses a jar built from trunk at `ef1255087`. It is compared with the second IT run recorded in
sis's `GRAPHITRON10_STATUS.md` ("Second IT run"), which used a jar from `dd940c703`. That run's
failsafe reports and `server.log` were still on disk and were saved as the baseline before anything
was rebuilt.

Outside `roadmap/`, `dd940c703..ef1255087` carries two commits: R1015's implementation (`6f813aa87`)
and R1014's test-only pin (`1a1d189b4`, graphitron's own tests, no generator code). So every
difference below that changes generated code is R1015's. The sis tree was copied to a scratch
directory and regenerated there with the Maven build cache off. The Oracle database is shared and was
already written to by the baseline run, so diffs in mutation-written data can come from database state
rather than code.

## Structural pin on the real schema

sis's generated sources carry 329 `.join(input).on(...)` joins and no `.using(...)` join. The three
named cases:

```java
// studieprogramGittStudieprogramkoder
.join(input).on(studieprogramTable.STUDIEPROGRAMKODE.eq(input.field("STUDIEPROGRAMKODE", studieprogramTable.STUDIEPROGRAMKODE.getDataType())))
// studenterGittFodselsnumre (FODSELSNR is a client-side computed column)
.join(input).on(studentTable.FODSELSNR.eq(input.field("FODSELSNR", studentTable.FODSELSNR.getDataType())))
// studenterGittFeideBrukere (FEIDE_BRUKER is a computed CASE column)
.join(input).on(studentTable.FEIDE_BRUKER.eq(input.field("FEIDE_BRUKER", studentTable.FEIDE_BRUKER.getDataType())))
```

## Result

| measure | baseline (`dd940c703`) | fix (`ef1255087`) |
|---|---|---|
| ITs run / failures / errors / skipped | 218 / 79 / 40 / 7 | 218 / 70 / 21 / 7 |
| failing ITs | 119 | 91 |
| ORA-25154 (qualified `USING` column) | 210 | 0 |
| ORA-03077 (`using (to_char(...) \|\| ...)`, `studenterGittFodselsnumre`) | 24 | 0 |
| ORA-00907 (`using (case ... end)`, `studenterGittFeideBrukere`) | 12 | 0 |
| failed `select` statements (`DataAccessException: SQL [select ...`) | 82 | 0 |
| every other ORA code (00001, 06512, 12899, 32575, 03301, 02291, 04088, 20986, 20114, 17023) | unchanged | unchanged |

All three `USING` signatures are gone. No other error code moved.

## Per test

28 ITs that failed in the baseline pass now, and no IT that passed in the baseline fails now.

- 11 named ITs: `FagpersonerQueryIT` (3 `fetchFagpersonGittPersonlopenr…`), `PersonQueryIT` (2
  `personProfilQueryFodselsnummer…`, ORA-03077), `StudentVedLarestedQueryIT` (4: the `FeideBruker`,
  `Studentnummer`, `OneFnr` and `FlereFodselsnumre` cases), `StudieprogramQueryIT` (2:
  `fetchGyldigeStudieretningsbeskrivelser`, `oppgaveEmneReturnererKorrektVerdi`).
- 17 `ReadOnlyApprovalIT` outputs, among them `emneLookup`, `studieprogramLookup` and the
  `maskinbruker…`/`…Les1Tilgang…` access-check outputs.

The status document estimated about 45 USING-attributed failures. That estimate counted log lines and
approval outputs, many of which also failed for a second reason. The baseline's 36 USING-attributed
request errors land in 12 named ITs and 24 approval outputs. Those that do not pass now fail for
another reason:

| test or output | fails now on | owner |
|---|---|---|
| `KullQueryIT.kullV3ReturnererPaakrevdeFelterForRedigering`, `StudieprogramQueryIT.kullV3ErAktivFilterFungerer` | `StudieprogramFetchers.rowsKullV3`: tenant binding value is absent | U4, R1018 / R1019 |
| `iamStudentMedCampusQuery-1`, `-2` | `StudentVedLarestedFetchers.rowsProgramStudieretter`: tenant binding value is absent | U4, R1018 / R1019 |
| `emneBeskrivelsesavsnittPaginert-2`, `studieprogramBeskrivelsesavsnittPaginert-1..3` | no error; the cursor format, and page 2 replays a hard-coded v9 cursor | B4, sis re-approval |
| `studieprogramfelter-1` | no error; more `beskrivelser` rows than approved, including one with content `<p>Test</p>` written by a mutation IT into the shared database | B4 / database state |

So the lookup fix unmasks two U4 sites the status document did not list: `rowsKullV3` (the "latent
root variant" it names at `StudieprogramFetchers:502` is a different site) and
`rowsProgramStudieretter`. Both have the U4 shape: a nested `@splitQuery` that routes on an optional
filter argument's tenant slot. The tenant-binding failure count in `server.log` rises from 6 to 12
for that reason, all on those two fetchers plus the three U4 already listed.

Every one of the 91 failures still standing carries the same failure message as in the baseline, test
by test (the two `kullV3` ITs above among them: their message is the generic "errors not null", and
only the server-side cause moved). The rest are the
causes the status document already sorts: U2 (payload `errors`, R1016), U3 (`DEFAULT` on view
inserts, R1017), U4 (R1018, R1019), U5 (R1020), and the sis decisions B1 to B4.
