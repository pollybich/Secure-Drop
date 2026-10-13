![pollyDrop · requirements](../images/requirements-en.svg)

[Русский](../REQUIREMENTS.md) · **English** · [← pollyDrop](README.md)

# First-version requirements

The target scope includes guest exchange, accounts and corporate deployment. The current beta implements guest exchange; accounts and corporate features remain planned. Implementation status is in the [roadmap](ROADMAP.md). `Must` means required for the target first version; `Should` means a planned improvement.

## Product goals

| ID | Goal |
| --- | --- |
| B01 | Temporary file/text sharing with controlled access conditions. |
| B02 | Protect content, passwords and management operations. |
| B03 | Revoke access and automatically remove unavailable content. |
| B04 | Reproducible deployment and verifiable implementation. |
| B05 | Public use and internal company deployment. |

## Functional requirements

| ID | Goal | Priority | Requirement and acceptance criteria |
| --- | --- | --- | --- |
| F01 | B01 | Must | Upload a file without registration and receive a link. Failed uploads do not produce an accessible transfer. |
| F02 | B01 | Must | Create a text transfer without registration or a separate file and receive a link. |
| F03 | B01 | Must | Store the name, lifetime and download limit; reject invalid values. A new form defaults to 1 hour, adjustable from 10 minutes to 2 days. |
| F04 | B02 | Must | Recipient tokens contain at least 256 random bits and reveal no sequential record ID. |
| F05 | B01 | Must | Reject expired downloads regardless of background-cleanup state. |
| F06 | B01 | Must | Completed deliveries plus active reservations never exceed the limit, including concurrent requests. |
| F07 | B01 | Must | A one-time transfer allows at most one successful server-side delivery. Retry is allowed after an interrupted delivery stops, while other conditions remain valid. |
| F08 | B02 | Must | Protected transfers require the correct password; it does not override other limits. |
| F09 | B02 | Must | Five incorrect attempts within 15 minutes block further attempts for 15 minutes for that token/client. |
| F10 | B01 | Must | Viewing the page, reading metadata and link previews do not spend a download. |
| F11 | B01 | Must | Of 100 concurrent requests to a one-time transfer, only one reserves a slot. After success, others cannot receive content; after interruption, retry waits for reservation release. |
| F12 | B02 | Must | Register, sign in and sign out of an account; store account passwords as strong hashes. Guest creation and receiving remain available. |
| F13 | B02 | Must | Account management requires an owner session; guest revocation requires a separate management token. Sign-out invalidates the session; cookies use HttpOnly, Secure and SameSite, with CSRF protection. |
| F14 | B03 | Must | The dashboard lists only the owner’s transfers, dates, type, size, limit, count and state. |
| F15 | B03 | Must | Owners or management-token holders can revoke access. A different account or recipient token alone cannot revoke. |
| F16 | B03 | Must | Cleanup removes expired, revoked and exhausted content, is idempotent and retries temporary failures. |
| F17 | B02 | Must | Store content with AES-256-GCM and unique nonces; keep keys separate from content and the repository. |
| F18 | B02 | Must | Logs may include event type, time and internal ID, never text, passwords, keys or full link tokens. |
| F19 | B04 | Must | Deploy via Docker and environment configuration without editing application source. |
| F20 | B04 | Must | Migrations initialize an empty database; REST documentation covers creation, receiving, revocation and errors. |
| F21 | B04 | Must | Automated checks cover lifetime, limits, passwords, revocation, deletion, ownership, guest management, concurrency, interruptions and retries. |
| F22 | B05 | Should | Public mode does not disclose content, passwords or tokens to ad systems. No third-party ad scripts on creation, recipient or dashboard pages. |
| F23 | B05 | Must | Internal company deployment is supported and corporate mode disables advertising. |
| F24 | B04 | Should | Document installation, backups, restore, updates and rollback. |
| F25 | B02 | Must | Guest management tokens contain at least 256 random bits, differ from recipient tokens and are returned only to the sender on creation. |

## Nonfunctional requirements

| ID | Goal | Priority | Measurable criterion |
| --- | --- | --- | --- |
| N01 | B01 | Must | On 2 vCPU / 4 GiB with 20 concurrent clients and 100 metadata requests, p95 response time ≤500 ms with no server errors; file bytes excluded. |
| N02 | B02 | Must | Public TLS ≥1.2; AES-256-GCM content; Argon2id passwords with ≥64 MiB memory and three iterations. |
| N03 | B03 | Must | Delete content after the final successful delivery or revocation before request handling ends; expiry uses a timer without API requests. Retry temporary cleanup failures once per second. Deny new delivery immediately. |
| N04 | B04 | Must | With prepared images, installation ≤10 minutes and metadata restore ≤30 minutes. Revoke restored transfers to avoid repeating a previously consumed delivery. |

These are target acceptance criteria; the current beta is not a claim that every planned feature or performance target has passed.

## Access rules

- Validate ownership or guest management rights on the server.
- Check state, deadline, password and delivery availability before reserving.
- Reserve atomically; count complete server sends rather than client assertions.
- Release interrupted reservations only after the original stream stops.
- Recovery must prevent the original stream and a retry from sending concurrently.
- Retries cannot restore expired or revoked transfers.
- Background cleanup does not replace request-time access checks.
- Page views and content requests are separate operations.
- Recipients can preserve copies, including bytes received before an interruption.

## Definition of Ready

- Requirement, value, priority and testable criteria are clear.
- Errors, boundaries, dependencies and security conditions are defined.
- Scope, result, verification data and method are ready.

## Definition of Done

- Acceptance criteria, errors and boundaries are checked.
- Critical-logic tests pass.
- No secrets or content in logs or the repository.
- Relevant migrations, API documentation and instructions are updated.
- Blocking defects are resolved.

## Outside the first version

Permanent cloud storage, native mobile apps, corporate SSO, custom integrations and S3/MinIO.
