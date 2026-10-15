# AGENTS.md — Corporate Billing Service

This file guides AI coding agents (Antigravity, Claude Code, Cursor, Copilot, etc.) working in this repository.

## Project Overview
- **Service**: Corporate Billing Service (`corporate-billing-service`)
- **Package**: `com.sistemaspalacios`
- **Stack**: Java 21, Spring Boot, Spring Data JPA, Flyway, PostgreSQL, Lombok, Testcontainers, JUnit 5.
- **Build Tool**: Gradle (`./gradlew` / `gradlew.bat`).

---

## Agent Skills Integration

This workspace has the full suite of 25 production-grade engineering skills installed under `.agents/skills/` and shared reference checklists under `.agents/references/`.

### Core Engineering Lifecycle & Skill Mapping

When working on tasks in this repository, follow the engineering lifecycle and activate the corresponding skill:

| Lifecycle Phase | Command / Intent | Skill Path | Purpose |
| :--- | :--- | :--- | :--- |
| **1. DEFINE** | `/spec` / Feature Definition | `.agents/skills/spec-driven-development/SKILL.md` | Define requirements, user stories, and acceptance criteria before coding. |
| **1b. REFINE** | Requirements Clarification | `.agents/skills/interview-me/SKILL.md` | Ask targeted clarifying questions before assuming requirements. |
| **2. PLAN** | `/plan` / Task Breakdown | `.agents/skills/planning-and-task-breakdown/SKILL.md` | Break features into atomic, testable increments. |
| **2b. CONSTRAINTS** | Architecture Rules | `.agents/skills/constraint-driven-development/SKILL.md` | Enforce domain boundaries, layering, and database constraints. |
| **3. DESIGN** | Contract & API Design | `.agents/skills/api-and-interface-design/SKILL.md` | Clean REST contracts, DTOs, idempotent billing operations, OpenAPI. |
| **4. BUILD** | `/build` / Implementation | `.agents/skills/incremental-implementation/SKILL.md` | Build one vertical slice at a time. |
| **4b. TEST** | `/test` / TDD | `.agents/skills/test-driven-development/SKILL.md` | Red-Green-Refactor loop with JUnit 5, Mockito, and Testcontainers. |
| **5. DEBUG** | Errors & Failures | `.agents/skills/debugging-and-error-recovery/SKILL.md` | Hypothesis-driven debugging, stack trace analysis, and root-cause fixes. |
| **6. REVIEW** | `/review` / Code Review | `.agents/skills/code-review-and-quality/SKILL.md` | 5-axis review: correctness, readability, architecture, security, performance. |
| **6b. SECURITY** | Security Audit | `.agents/skills/security-and-hardening/SKILL.md` | OWASP checks, PII protection, SQL/JPA injection prevention, access control. |
| **6c. SIMPLIFY** | `/code-simplify` | `.agents/skills/code-simplification/SKILL.md` | Remove unnecessary abstractions, dead code, and over-engineering. |
| **7. SHIP** | `/ship` / Launch Prep | `.agents/skills/shipping-and-launch/SKILL.md` | Production readiness, Flyway migrations verification, rollback strategies. |

---

## Shared References
Reference checklists are located in `.agents/references/`:
- `definition-of-done.md`: Project-wide Definition of Done.
- `security-checklist.md`: Security verification checklist.
- `performance-checklist.md`: Database query, caching, and throughput checks.
- `observability-checklist.md`: Metrics, health indicators, and logging standards.
- `testing-patterns.md`: Testing best practices.

---

## Repository Conventions & Quality Gates
1. **Verification**: Always run `./gradlew test` (or `gradlew.bat test` on Windows) before considering any task complete.
2. **Database Migrations**: Never modify existing Flyway migration files that have already been applied; always create a new versioned migration script (`V<timestamp>__description.sql`).
3. **Immutability & Safety**: Financial, billing, and tax calculations must use `BigDecimal` with explicit rounding modes (`RoundingMode.HALF_UP`), never floating-point primitives (`float` or `double`).
4. **Idempotency**: All billing transaction endpoints (charges, invoice generation, cancellations) must support idempotency keys to prevent double-billing.
5. **Language Conventions**: All Java code (classes, interfaces, records, enums, methods, fields, and variables) must be written in English (e.g., `Invoice`, `CorporateClient`, `BillingService`, `TaxCalculator`). Database tables, columns, and Flyway SQL migrations remain in Spanish (e.g., `factura`, `cliente_corporativo`), mapped explicitly via JPA annotations (`@Table(name = "...")`, `@Column(name = "...")`). Regulatory/tax terms (NIT, IVA, Retefuente, ReteICA, ReteIVA, CUFE, F1, F20) retain their official names.
