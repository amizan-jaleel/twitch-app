# JVM advisory review: 2026-11-21

Scope: November 8-21 vendor disclosures, compared with `master` at `36daefe`.
This is separate from the unmerged application-hardening PR #59. A source change
does not patch an already-running Render container.

## Urgent runtime update

Both Docker stages move from Temurin **21.0.13+9** to **21.0.13.1+2**, with
verified official-image digests. This is the November security release, not a
major Java upgrade. See [Adoptium's advisory](https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/)
and the [OpenJDK November 17 advisory](https://openjdk.org/groups/vulnerability/advisories/2026-11-17).

- **CVE-2026-114938 (Java4Shell):** heap corruption in the accelerated Base64
  decoder. Exploitation requires an affected HotSpot build, x86-64 hardware with
  the relevant AVX-512/BMI2 features, and attacker-controlled decoder input.
  Crashes and application-specific remote code execution are possible. The
  repository's explicit decoder reads the configured Firebase private key, not
  request input; this is not a proof that dependency paths are unreachable.
  The [researcher also demonstrates a malicious TLS server attack](https://calif.io/java4shell),
  so absence of an inbound Base64 endpoint is not a reason to postpone updating.
- **CVE-2026-114940:** an active attacker can bypass server authentication in
  certain outbound TLS 1.2 handshakes. This is relevant to an HTTPS client even
  when Render terminates inbound TLS. Actual negotiated production protocols
  and a complete exploit path have not been verified. See [the vendor analysis](https://access.redhat.com/security/cve/cve-2026-114940).
- The same release fixes the other November Java 21 findings:
  **CVE-2026-114939 and CVE-2026-114941 through CVE-2026-114946**. These include
  conditional Kerberos/JMX/JFR/vector/compiler issues; no claim is made that
  every affected feature is used by this app. See the [Oracle risk matrix](https://www.oracle.com/security-alerts/cspunov2026.html#AppendixJAVA).

CI and dependency-graph submission request the latest Temurin 21 and check the
installed JVM against the security minimum before sbt starts. The check uses
Java's version API, not SemVer, which does not preserve this four-part/build
minimum in range expressions. It rejects old builds and early-access releases,
but accepts future stable Java 21 security releases. The container smoke job
also verifies the actual shipped JVM version. Refresh its runtime assertion
together with future pinned Java image updates.

The newly published Noble image indexes currently contain AMD64, PPC64LE and
S390X, not ARM64. Local container verification therefore targets `linux/amd64`.
ARM64 is not affected by Java4Shell itself, but still needs applicable Java
security updates. Do not silently fall back to an old x86 runtime.

## http4s security patch

All existing backend and Scala.js http4s version declarations move from
**0.23.38 to 0.23.39**, released November 18. The vendor lists three new
advisories with no assigned CVEs:

| Advisory | Issue | Reachability in this app |
| --- | --- | --- |
| [GHSA-q22p-387c-3hw8](https://github.com/http4s/http4s/security/advisories/GHSA-q22p-387c-3hw8) | HTTP/2 reset floods start unbounded cleanup work | HTTP/2 is not enabled in the server/client builders |
| [GHSA-hcc2-5wp2-49fj](https://github.com/http4s/http4s/security/advisories/GHSA-hcc2-5wp2-49fj) | Quadratic WebSocket frame decoding | No WebSocket endpoint; notifications use SSE |
| [GHSA-g6c8-chr7-3mc2](https://github.com/http4s/http4s/security/advisories/GHSA-g6c8-chr7-3mc2) | Quadratic CookieJar header construction | No CookieJar middleware |

These are defense-in-depth fixes, not evidence of an exposed endpoint. See the
[release notes](https://github.com/http4s/http4s/releases/tag/v0.23.39). An OSV
query returned no matches for 152 pre-change coordinate/version pairs in the
backend runtime, frontend compile and sbt plugin compile graphs, illustrating
why vendor advisories must supplement scanner results.

Older compiler/Scaladoc-only Jsoup, Jackson and JLine alerts remain outside this
urgent runtime patch; they are not included in the clean runtime/plugin result.
The earlier report's pgJDBC certificate-file, H2 and build-only Zod residuals
also remain. No new material vendor security delta was located for the other
reviewed runtime libraries. Spring Security's November RCE advisory does not
apply to the checked graph, which does not contain Spring Security.

## Rollout

1. Merge and build a new server image, then deploy that commit to Render.
   Rebuilding an APK or changing Maven dependencies alone cannot update the
   server's bundled JVM. No mobile native dependency changes are included here.
2. Before the patched image is deployed, the vendor's Java4Shell workaround is
   to append `-XX:-UseBASE64Intrinsics` to Render's `JAVA_TOOL_OPTIONS` and restart
   or redeploy the service. Preserve existing options. This only mitigates
   Java4Shell, not the other November findings; remove it after confirming the
   patched runtime is live.
3. In the running container, `java -version` must show Temurin 21.0.13.1+2 (or a
   later confirmed security release). Update standalone developer JDKs too;
   build-tool configuration can override `JAVA_HOME`, so check sbt's banner.
4. Verify login, category search and notifications. Review unexpected JVM
   crashes, new processes and suspicious outbound activity if exposure is
   suspected. Updating prevents future exploitation; it does not prove that an
   old process was never compromised.

Do not merge the existing Dependabot PR #69 as a substitute: it proposes Java
24.0.2+12, not the patched Java 21 release. Major Temurin upgrades are now left
for explicit review while same-major image updates remain enabled. The deployed
CPU flags, running image and production logs were not inspected in this review.
