# Resume project replacement (copy into PDF)

**Replaces:** Enterprise Event-Driven Microservices Gateway  
**Source of bullets:** v3 planned architecture (`todos/v3-implementation-plan/`), not the current baseline codebase  
**GitHub:** https://github.com/Hvmanker/insurance-claims-management-system

---

## Paste this block under PROJECTS (same format as before)

Insurance Claims Management System [Java 21, Spring Boot 3, Spring Cloud Gateway, Kafka, PostgreSQL, Redis, Next.js, Docker] [GitHub](https://github.com/Hvmanker/insurance-claims-management-system)

• Event-Driven Claims Microservices: Architecting a schema-per-service Spring Boot platform (claim, approval, settlement, notification) behind Spring Cloud Gateway, with Flyway-versioned PostgreSQL schemas, Redis-backed Spring Session, and a transactional outbox feeding Apache Kafka for crash-safe claim lifecycle events.

• Rules-Governed Decision Pipeline: Designing a Python analysis worker that extracts evidence from synthetic repair estimates while Java rule engines alone decide outcomes—auto-approval only when every safety rule passes, with rejection reserved as a human action and a nine-state claim lifecycle including review, investigation, and expiry paths.

• Enterprise Role Workspaces & Reliability: Planning an eight-role Next.js claims desk (Insured, Agent, Adjuster, Supervisor, SIU, Finance, Auditor, Admin) with server-side RBAC, document upload via short-lived S3-compatible URLs, idempotent consumers with retry/DLT replay, and Docker Compose as the primary end-to-end demo path.

---

Full updated resume: [himank-resume-updated.md](./himank-resume-updated.md)
