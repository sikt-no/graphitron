# Graphitron: Security Model

## Business People Understand Their Data

Ask a registrar who should see student grades, and they can tell you. Ask a finance manager who should access salary data, and they know. These aren’t technical questions—they’re business questions, and the people who work with the data every day have the answers.

A database has a finite number of tables and columns. You can enumerate them. You can point at a table and ask “who should see this?” You can have a concrete conversation that results in concrete rules.

This is the foundation of our security model: **security rules expressed in terms of data are discussable with the people who understand the business.**

## Why We Chose Database-Level Security

Security logic can live in many places: the database, a service layer, API middleware, or the UI. Each approach has merits, and thoughtful teams have built secure systems with each.

Application-layer security is well-understood territory with established patterns and tooling. It offers flexibility: you can express complex, context-dependent rules that might be hard to capture in database predicates. Many successful systems work this way.

For our specific context, we chose database-level security because:

- **Our system spans decades.** The database will outlast any individual application. Rules in the database persist; rules in applications must be carried forward intentionally.
- **Multiple applications access the same data.** Database-level rules apply uniformly. Application-level rules require coordination to keep consistent.
- **We want business stakeholders to verify rules directly.** Rules expressed as data predicates map to how they think about their domain.

Other systems—shorter-lived, single-application, or with authorization logic that doesn’t map to data predicates—might reasonably choose differently.

## What This Means for Graphitron

Graphitron generates code that queries the database. That generated code is intentionally “naive” about security—it doesn’t include authorization checks.

This is a feature. The database handles enforcement; the generated code stays simple. If we tried to generate authorization logic, we’d be reimplementing what the database already does, adding complexity that could drift out of sync.

## Security Is Cross-Cutting

Database-level enforcement doesn’t mean the UI can ignore security. Good usability requires that users don’t see buttons they can’t click or forms they can’t submit.

Security logic appears in multiple places:

- **The database enforces.** The authoritative layer. No matter what the UI does, the database won’t return unauthorized data.
- **The UI guides.** The usability layer. It hides or disables things the user can’t do.

The rules should be the same; the implementations differ. Ideally both derive from a shared source of truth, though in practice this is hard.

What we insist on: **the database is the enforcement point.** If the UI gets it wrong, the result is bad usability. If the database gets it wrong, data is exposed. These are not equivalent failures.

## Summary

Security rules expressed in terms of data can be discussed with business stakeholders and enforced consistently across applications. The database enforces; the UI guides. Graphitron generates simple queries and trusts the database to do its job.