# Dependency advisory review: 2026-11-08

Scope: published vendor/CNA advisories and the resolved dependencies of `master`
at `30da6d6`. This update is independent of the unmerged application-hardening
PR #59. It does not establish that the deployed server or installed apps already
contain these changes.

## Findings and fixes

| Component | Previous resolved version | Updated version | Relevant advisories and applicability |
| --- | --- | --- | --- |
| Capacitor Android/iOS | 8.3.0 | 8.6.0 | [CVE-2026-103922](https://github.com/ionic-team/capacitor/security/advisories/GHSA-rvm3-566m-v7fv), published August 31: a malicious WebView link can load remote content at the app origin and expose native plugins. Victim interaction is required. Both Swift manifests and npm packages are updated. |
| http4s | 0.23.30 backend; 0.23.24 client in Scala.js | 0.23.38 throughout | July-September framing and resource-exhaustion fixes. [CVE-2026-69204](https://github.com/http4s/http4s/security/advisories/GHSA-8h4c-x2wg-6xp8), [CVE-2026-69205](https://github.com/http4s/http4s/security/advisories/GHSA-9998-894r-fwvr), [CVE-2026-69216](https://github.com/http4s/http4s/security/advisories/GHSA-jrpm-956j-96jg), and [bare-LF smuggling](https://github.com/http4s/http4s/security/advisories/GHSA-xmmj-wgv7-x3pq) matter behind proxies that forward ambiguous framing on reused connections. Render proxy behavior has not been tested. |
| Jawn JSON parser | 1.6.0 backend; 1.5.1 Scala.js | 1.7.0, transitively | [CVE-2026-59990](https://github.com/typelevel/jawn/security/advisories/GHSA-cc4v-rvgp-2pf3) and [CVE-2026-61814](https://github.com/typelevel/jawn/security/advisories/GHSA-w4cm-gvhj-cgw6): excessive nesting and fragmented-token parsing can exhaust memory/CPU. Server JSON entity decoding is an input path. |
| FS2 I/O | 3.11.0 | 3.14.0, transitively | [CVE-2025-58369](https://github.com/typelevel/fs2/security/advisories/GHSA-rrw2-px9j-qffj): a peer half-closing during TLS handshake can cause a spin loop. The app uses an FS2-backed HTTPS client. |
| pgJDBC | 42.7.5 (42.7.4 declared) | 42.7.14 | [Vendor advisories](https://jdbc.postgresql.org/security/) include CVE-2026-107313/107315 buffer disclosures, CVE-2026-54291 channel-binding downgrade, and CVE-2026-42198 SCRAM CPU exhaustion. GSS, mismatched declared write lengths, and required channel binding are conditional features; no app path was found using the first two. A hostile database endpoint or interception is required for authentication attacks. |
| Logback | 1.5.16 | 1.6.6 | [Vendor release notes](https://logback.qos.ch/news.html) cover CVE-2026-104721/19880, CVE-2026-10532/9828, CVE-2026-1225, and older configuration/deserialization issues. Optional socket receivers, SiftingAppender and attacker-controlled logging configuration were not found in this app. This removes vulnerable components as defense in depth. |
| sbt | 1.12.2 | 1.13.1 | [October 28 archive extraction advisory](https://github.com/sbt/sbt/security/advisories/GHSA-p8jf-532w-4p65), [August 21 BSP RCE advisory](https://github.com/sbt/sbt/security/advisories/GHSA-943m-f264-54p4), and [CVE-2026-32948](https://github.com/sbt/sbt/security/advisories/GHSA-x4ff-q6h8-v7gw). Build/developer exposure; TCP sbt mode and source dependencies are not configured here. The newest two advisories have no assigned CVE. |
| Build-plugin libraries | Guava 18.0; Commons Lang 3.8.1 | Guava 33.7.2-jre; Commons Lang 3.18.0 | [CVE-2026-102554](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) and [CVE-2025-48924](https://github.com/advisories/GHSA-j288-q9x7-2f5v). These libraries run in sbt plugins, not the application JAR. Overrides also remove older Guava advisory matches. |
| Java container runtime | Floating Java 21 image; deployed patch unknown | Temurin 21.0.13+9, Noble, digest-pinned | [October Temurin security update](https://adoptium.net/news/2026/10/temurin-october-2026-emudhra) removes roots associated with compromised eMudhra intermediates. This affects trust for outbound HTTPS, including OAuth and notifications. Both build and runtime images are pinned; CI requests the newest available Temurin 21. |
| APT in both container stages | 2.8.3 even in the new pinned base | At least 2.8.4ubuntu0.1 from Noble updates | [CVE-2026-109518](https://ubuntu.com/security/CVE-2026-109518), published November 4: an on-path attacker can bypass package verification during redirected/retried downloads. Install the patched APT packages in both stages; disable download retries during that bootstrap operation to mitigate the old installer's vulnerable path. |

The http4s upgrade also includes its HTTP/2, CookieJar, WebSocket, multipart and
static-service patches. HTTP/2 is not explicitly enabled, and the app does not use
CookieJar, DigestAuth, WebSockets or multipart decoding; their package matches
are not evidence of an exposed endpoint. See the [maintainer advisory list](https://github.com/http4s/http4s/security/advisories)
and [release notes](https://github.com/http4s/http4s/releases/tag/v0.23.38).

Cats Effect/Circe are aligned with the patched http4s dependency graph. Scala.js
1.22.0 is required to link the newer libraries. Docker uses supported Node 22
instead of end-of-life Node 20, and uses the committed npm lockfile with `npm ci`.
Scalawind moves from 1.0.3 to 1.0.4 because the older tarball is no longer served
by the public registry; generation was verified byte-for-byte against the old output.

## Residual issues and limits

- [pgJDBC certificate-file parsing](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hcjv-f5jh-r55v), published October 15, has no patched release as of this review, including 42.7.14. It requires control of `sslrootcert`/`sslcert` or their file contents. These come from trusted deployment configuration in this app, not HTTP input. Keep certificate paths trusted and finite; a finite `loginTimeout` is the vendor's partial mitigation. This is not claimed fixed by the version bump.
- [Zod CVE-2023-54404](https://www.vulncheck.com/advisories/zod-uncontrolled-resource-consumption-via-array-validation), disclosed October 1, remains applicable to unbounded collection validation even in 4.6.7. The scoped Scalawind override updates its build-only Zod dependency, but does not fully fix this CVE. Inspected Scalawind usage validates fixed scalar CLI options; there is no server/mobile request path to Zod. A clean npm audit must not be interpreted as complete remediation of this advisory.
- [H2 2.5.252 security fixes](https://github.com/h2database/h2database/releases/tag/version-2.5.252) were reviewed. No HTTP path controls its JDBC URL or invokes Java-object deserialization in the app's scalar schema. H2 remains 2.3.232; a separate upgrade should test copies of persistent databases for compatibility. The new deserialization filter also defaults to allowing all classes, so a version bump alone would not establish a restrictive allowlist.
- Managed PostgreSQL, host kernels, device WebView/iOS security updates and the actual deployed image are outside the dependency versions proven here. No claim is made that undisclosed zero-days can be ruled out or that exploitation has occurred.

## Verification and rollout

The review combines primary vendor advisories with OSV queries of resolved Maven
coordinates and the public npm audit service. Before upgrades, OSV returned 30
advisory/package matches across 137 resolved versions. After upgrades it returned
zero matches across 150 versions. Vendor-only findings above show why that result
is not a complete vulnerability guarantee. The Maven scan includes backend
runtime, Scala.js dependencies and sbt plugin dependencies; it excludes the sbt
launcher itself, which was checked against its vendor advisories separately.
Public-registry `npm audit` returns zero findings, and an empty-cache public npm
install succeeds without relying on corporate mirrors or pre-existing tarballs.

Regression tests exercise real Ember socket parsing for ambiguous framing and
HTTP JSON decoding for excessive nesting. All 126 tests, production Scala.js
linking, backend assembly and Capacitor asset sync pass locally. Docker build
and browser smoke checks validate the shipped server. Native compilation must pass the
platform CI jobs; local Xcode is unavailable on the review machine.

Deploy a newly built server image after merge. Rebuild and redistribute both
native apps for the Capacitor fix. Existing installed binaries keep their old
native runtime even when they load updated web content from the server. Future
Java/Node image updates must refresh the pinned tags/digests; changing dependencies
alone does not update already-running containers.
