# mule-aws-secrets-poc

Phase 1 proof of concept: retrieve PostgreSQL credentials from **AWS Secrets Manager** through the
**aws-secretsmanager-jdbc** wrapper driver, from a Mule 4 application that will later run inside
**AWS EKS** using **EKS Pod Identity**.

---

## 1. Purpose of the PoC

This application exists to answer one question:

> Can a Mule application obtain its database credentials from AWS Secrets Manager via the AWS JDBC
> wrapper driver, and keep authenticating successfully **after the credential is rotated**, without
> restarting Mule, recreating the pod, or redeploying the application?

To answer that, Phase 1 deliberately isolates four moving parts:

1. Secrets Manager retrieval (does the driver resolve the secret at all?)
2. AWS JDBC secret caching (how long does the driver hold a stale credential?)
3. Physical JDBC session creation (when is a new PostgreSQL backend actually created?)
4. Credential rotation behaviour (does a new physical connection pick up the new password?)

Phase 2 (**not implemented here**) will extend this same application to a second PoC covering TLS
certificates / P12 files mounted into the Kubernetes pod.

## 2. Architecture

```
Mule Database Connector
        |
        v
Generic JDBC Connection          (url = jdbc-secretsmanager:postgresql://...)
        |
        v
AWSSecretsManagerPostgreSQLDriver
        |
        v
AWS SDK Default Credential Provider Chain
        |
        v
EKS Pod Identity
        |
        v
AWS Secrets Manager              (secret: mule-poc/database/app)
        |
        v
PostgreSQL RDS
```

Two things make this architecture unusual and are intentional:

- The Mule **Amazon Secrets Manager Properties Provider is deliberately NOT used**. That component
  resolves secrets at application startup into Mule properties. This PoC must test the *JDBC
  wrapper driver* instead, because the wrapper resolves credentials **per physical connection**,
  which is what makes rotation-without-restart possible.
- The JDBC `user` property carries the **secret id**, not a username. The wrapper driver treats the
  `user` value as the Secrets Manager secret to resolve, then injects the real username and password
  into the delegated PostgreSQL connection.

## 3. Mule Runtime version

- **Mule Runtime Engine 4.12.3** (Enterprise / `MULE_EE`, declared in `mule-artifact.json`)
- Packaged as a `mule-application`; the resulting JAR is deployed to a standalone Mule EE runtime.

## 4. Java version

- **Java 17** (`maven.compiler.source/target = 17`)
- Build verified with OpenJDK 17.0.19 and **Maven 3.9.15**.

## 5. Dependencies

| Artifact | Version | Notes |
|---|---|---|
| `org.mule.connectors:mule-http-connector` | 1.12.1 | `mule-plugin` |
| `org.mule.connectors:mule-db-connector` | 1.16.4 | `mule-plugin` |
| `com.amazonaws.secretsmanager:aws-secretsmanager-jdbc` | 2.1.3 | wrapper driver |
| `org.postgresql:postgresql` | 42.7.13 | delegated JDBC driver |
| `software.amazon.awssdk:sts` | 2.41.3 | required for EKS Pod Identity credential resolution |
| `software.amazon.awssdk:secretsmanager` | 2.41.3 | pinned (see below) |
| `software.amazon.awssdk:aws-crt-client` | 2.41.3 | pinned (see below) |
| `software.amazon.awssdk.crt:aws-crt` | 0.40.3 | transitive, deliberately not pinned |
| `org.mule.tools.maven:mule-maven-plugin` | 4.10.0 | packaging |

### AWS SDK v2 version alignment

Every AWS SDK v2 module is aligned on **2.41.3**, which is the version declared by
`aws-secretsmanager-jdbc:2.1.3` itself (`secretsmanager:2.41.3`, `aws-crt-client:2.41.3`). STS is
held on the same version.

This is a deliberate PoC choice: aligning *down* to the driver's own declared versions means the
driver runs against exactly the SDK it was built and tested against, and removes dependency-version
skew as a variable when we start interpreting rotation and caching behaviour. Any surprise during
the experiment is then attributable to Secrets Manager or JDBC behaviour rather than to an SDK
upgrade we introduced.

Alignment is enforced in two places, because the two halves of the build resolve independently:

1. `software.amazon.awssdk:bom` is imported in `dependencyManagement`, which governs the whole
   application dependency graph.
2. `mule-maven-plugin`'s `additionalPluginDependencies` are resolved **independently of the project's
   `dependencyManagement`**, and `<exclusions>` declared there are **ignored** by the plugin. The AWS
   modules are therefore re-declared with explicit versions inside the `additionalDependencies`
   block so nearest-wins resolution pins the connector classloader too.

`software.amazon.awssdk.crt:aws-crt` (the native binding) is intentionally **not** pinned; the build
takes whatever version `aws-crt-client` selects transitively, currently `0.40.3`.

Verified result: all `software.amazon.awssdk` artifacts in both the application graph and the
packaged artifact resolve to **2.41.3**, with a single sync HTTP client module (`apache-client`) and
a single async one (`netty-nio-client`).

`aws-crt-client` must **not** be excluded. The driver's `JDBCSecretCacheBuilderProvider` hard-wires
the Secrets Manager client to
`SecretsManagerClientBuilder.httpClientBuilder(AwsCrtHttpClient.builder().postQuantumTlsEnabled(...))`,
so removing it fails at connection time with `NoClassDefFoundError`. Because the HTTP client is
supplied explicitly by the driver, the SDK never performs `ServiceLoader` discovery for it, so the
apache client module coexisting on the classpath is harmless.

### Secret cache TTL (`AWS_SECRET_CACHE_TTL_SECONDS`)

The `aws-secretsmanager-jdbc` driver caches each retrieved secret. The AWS default is
`SecretCacheConfiguration.DEFAULT_CACHE_ITEM_TTL`, which is **one hour**. For this PoC we shorten it
to **60 seconds** so a rotated credential is picked up quickly enough to observe.

### Why a shim is needed

`aws-secretsmanager-jdbc` 2.1.3 exposes **no configuration property for the cache TTL**. The only
properties the driver reads are `drivers.region`, `drivers.vpcEndpointUrl`,
`drivers.vpcEndpointRegion`, `drivers.postQuantumTlsEnabled` and
`drivers.<subprefix>.realDriverClass`. There is deliberately no `drivers.cacheTTL` — it does not
exist, and inventing it would silently do nothing.

The only supported way to set the TTL is to construct the driver with a `SecretCacheConfiguration`.
`AWSSecretsManagerPostgreSQLDriver` exposes exactly that as a public constructor, but the class is
`final`, so it cannot be subclassed. The `jdbc-cache-ttl` module therefore provides
`com.poc.mule.awssecrets.jdbc.TtlConfiguredPostgreSQLDriver`, which implements `java.sql.Driver` and
delegates every call to a properly configured `AWSSecretsManagerPostgreSQLDriver` instance.

**No reflection is used and no private state is touched** — only public JDBC and AWS APIs.

### How the value reaches the driver's cache

1. The Deployment sets `AWS_SECRET_CACHE_TTL_SECONDS=60` on the container.
2. Mule's Database Connector resolves `driverClassName` with `Class.forName` in the connector
   classloader, loading the shim and running its static initializer.
3. The shim reads `AWS_SECRET_CACHE_TTL_SECONDS` (environment variable first, then a system property
   of the same name), defaulting to `3600`. A non-positive or non-numeric value fails startup
   immediately with a clear message rather than silently falling back.
4. Seconds are converted with `TimeUnit.SECONDS.toMillis(...)` (long arithmetic).
5. Loading `AWSSecretsManagerPostgreSQLDriver` runs its static block, which registers an instance
   using the default one-hour TTL. The shim deregisters that instance via
   `DriverManager.deregisterDriver`, which fires the AWS `DriverAction` and closes its cache and
   client.
6. The shim builds its Secrets Manager client through the driver's own
   `JDBCSecretCacheBuilderProvider`, so `AWS_SECRET_JDBC_REGION`, PrivateLink endpoint overrides and
   the default region provider chain behave exactly as they do by default.
7. It constructs `new AWSSecretsManagerPostgreSQLDriver(config)` with
   `withCacheItemTTL(ttlMillis)`. That constructor self-registers, leaving exactly one registered
   driver for the `jdbc-secretsmanager:postgresql:` scheme — the configured one.

This configures the **same** cache the JDBC driver uses. No second, independent cache is created.

### Why this must not live in the Mule application

The Database Connector resolves the driver class inside its **own** classloader, which cannot see
Mule application classes. Registering a driver from the application classloader would not help
either, because `DriverManager.isDriverAllowed()` compares `Class` identity against the calling
classloader and would skip it. The shim is therefore a separate artifact injected into the
`mule-db-connector` classloader through `additionalPluginDependencies`.

### Relationship to rotation

TTL is not what makes rotation survivable. `AWSSecretsManagerDriver.connectWithSecret()` already
detects a PostgreSQL authentication failure (SQLSTATE `28P01`, `28000`, `08P01`), calls
`secretCache.refreshNow()` and retries, up to `MAX_RETRY = 5`. So a connection that fails against a
rotated password self-heals regardless of TTL.

What the TTL governs is the window in which a **stale but still valid** credential keeps being
served — precisely the AWS dual-secret window where the old password still works. Lowering it to 60s
lets the experiment observe proactive pickup rather than only failure-driven recovery.

### Operational notes

- The shim logs the configured TTL at `INFO` under `com.poc.mule.awssecrets.jdbc`, which the
  existing `com.poc.mule.awssecrets` logger in `log4j2.xml` already covers. It never logs secrets.
- The TTL is read once, at driver class initialization. Changing it requires a pod restart, which is
  incompatible with an in-flight rotation experiment — set it before starting a run.
- Because the client is supplied explicitly, the driver's `postQuantumTlsEnabled` option is not
  usable together with this shim (`SecretCache` rejects that combination). We do not enable PQTLS.
- Passing a pre-built client also skips the `USER_AGENT_SUFFIX` that `SecretCache` normally adds.
  This is AWS telemetry only and has no functional effect.

## Classloading

Mule 4 isolates connector classloaders, so the Database Connector cannot see the application's own
dependencies. The JDBC and AWS libraries are injected into the `mule-db-connector` plugin
classloader using `mule-maven-plugin`'s `additionalPluginDependencies`, which is MuleSoft's
documented driver-visibility model. No JARs are copied into the Mule runtime by hand.

## 6. Required environment variables

All configuration lives in `src/main/resources/application.properties`, which only references
environment variables. Nothing environment-specific is hardcoded in the Mule XML.

| Variable | Value for this PoC | Consumed by |
|---|---|---|
| `HTTP_PORT` | `8081` | Mule HTTP listener |
| `DB_HOST` | `mule-poc-postgres.c1ym8w0m4wxm.us-east-2.rds.amazonaws.com` | JDBC URL |
| `DB_PORT` | `5432` | JDBC URL |
| `DB_NAME` | `poctest` | JDBC URL |
| `AWS_SECRET_ID` | `mule-poc/database/app` | JDBC `user` (secret id) |
| `AWS_REGION` | `us-east-2` | AWS SDK / default credential provider chain |
| `AWS_SECRET_JDBC_REGION` | `us-east-2` | read directly by the wrapper driver to locate the secret |
| `AWS_SECRET_CACHE_TTL_SECONDS` | `60` (default `3600`) | secret cache TTL, see "Secret cache TTL" below |

`AWS_SECRET_JDBC_REGION` is read by the wrapper driver itself (confirmed in
`JDBCSecretCacheBuilderProvider`), which is why it is set in addition to `AWS_REGION`.

Non-secret AWS facts for reference: region `us-east-2`, secret `mule-poc/database/app`, database
application user `mule_app` (stored inside the secret, never in this repository).

There is intentionally **no `db.user` and no `db.password` property**.

## 7. `/health` behaviour

`GET /health` proves only that the Mule application itself is alive. It performs **no database
access**, so it stays green even when RDS or Secrets Manager is unreachable — which is exactly what
you want for a Kubernetes liveness/readiness probe.

Always returns HTTP `200`, `Content-Type: application/json`:

```json
{
  "status": "UP",
  "application": "mule-aws-secrets-poc"
}
```

## 8. `/db-test` behaviour

`GET /db-test` proves real database connectivity. It executes exactly one statement:

```sql
SELECT
    current_user   AS db_user,
    pg_backend_pid() AS backend_pid,
    current_timestamp AS db_timestamp;
```

Success — HTTP `200`, `Content-Type: application/json`:

```json
{
  "status": "UP",
  "database": {
    "user": "mule_app",
    "backendPid": 1234,
    "timestamp": "2026-01-01T00:00:00Z"
  }
}
```

Failure — HTTP `503`:

```json
{
  "status": "DOWN",
  "component": "database",
  "errorType": "DB:CONNECTIVITY"
}
```

The error response is deliberately reduced to a status, a component and a Mule error type. It never
returns the password, secret contents, AWS credentials, JDBC credential properties, stack traces or
raw exception objects. The flow logs the error type and the connector's error description at `ERROR`
level, which is enough to diagnose a failed DB operation without emitting secret material.

## 9. Why `pg_backend_pid()` is returned

`pg_backend_pid()` is the process id of the PostgreSQL backend serving the current connection. It is
the cheapest reliable way to identify a **physical** database session from the client side.

This is the key measurement of the whole PoC. After a password rotation we need to distinguish:

- **same `backend_pid`** — the request reused an existing physical JDBC session that was
  authenticated with the *old* password, so rotation has not actually been exercised yet; or
- **different `backend_pid`** — a new physical connection was created and authenticated, which is
  the only thing that proves the new credential was fetched and works.

Without the backend PID, a successful `/db-test` after rotation would be ambiguous.

## 10. Why the password is not a Mule property

If the password were a Mule property (for example via the Amazon Secrets Manager Properties
Provider), it would be resolved **once at application startup** and frozen into the Mule
configuration for the lifetime of the application. A rotation would then require an application
restart or redeployment — the exact thing this PoC is trying to avoid.

By pushing credential resolution down into the JDBC layer, the wrapper driver resolves the secret at
**connection establishment time**, and on an authentication failure it refreshes the cached secret
and retries. That is what makes rotation-without-restart possible. It also means the password never
exists as a Mule property, never appears in `application.properties`, and is never a candidate for
property-dump or configuration logging.

## 11. AWS Default Credential Provider Chain and EKS Pod Identity

The AWS libraries are configured to use the **AWS Default Credential Provider Chain**. Nothing in
this repository supplies AWS credentials.

When the application later runs in EKS with **EKS Pod Identity**, the EKS Pod Identity Agent exposes
container credentials to the pod, and the default chain picks them up automatically through its
container-credentials provider, exchanging them via **STS** (which is why the `sts` dependency is
required even though no application code calls STS directly). The pod's service account is mapped to
an IAM role with `secretsmanager:GetSecretValue` on `mule-poc/database/app`.

No configuration change is needed in this application to move from "no credentials" locally to
"Pod Identity credentials" in EKS — that is the point of relying on the default chain.

## 12. Why explicit DB pooling is intentionally disabled in Phase 1

There is **no `<db:pooling-profile>`** in the Database Config, on purpose.

The customer does not currently use explicit connection pooling, and more importantly a pool would
sit between the request and the physical JDBC session and confound every measurement this PoC makes.
With a pool in place, a `/db-test` call may return a long-lived pooled connection, so the
`backend_pid` would tell us about the pool's lifecycle rather than about Secrets Manager behaviour.

Phase 1 therefore observes raw connection creation, secret caching and rotation behaviour directly.
Pooling will be tested separately as its own experiment, once the baseline behaviour is understood.

## 13. No static AWS credentials

Static AWS credentials are not allowed in this project. Specifically, the following are **never**
configured, committed, or documented as real values:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- AWS session credentials / session tokens
- any hardcoded AWS credentials

Logging is configured accordingly in `log4j2.xml`: the AWS SDK loggers are held at `WARN` and AWS
SDK `DEBUG`/`TRACE` and HTTP wire logging are **not** enabled, because those levels can emit the
Secrets Manager `SecretString` (username and password) and STS session credentials. The file
documents which Database Connector / driver loggers can be temporarily raised to `DEBUG` for
troubleshooting, and which must never be.

## 14. Build instructions

Requires Java 17 and Maven 3.9.x, plus access to the MuleSoft Maven repositories (configured in
`~/.m2/settings.xml`).

```bash
java -version     # expect 17
mvn -version      # expect 3.9.x on Java 17

# 1. Install the JDBC cache-TTL shim (see "Secret cache TTL") into the local repository.
#    Only needs repeating when jdbc-cache-ttl/ changes.
cd jdbc-cache-ttl && mvn clean install && cd ..

# 2. Build the Mule application.
mvn clean package
```

The shim is a separate artifact that the Mule application resolves through
`additionalPluginDependencies`, so it must exist in the local Maven repository before step 2. If
step 2 fails with an unresolved `com.poc.mule:mule-aws-secrets-poc-jdbc-cache-ttl`, step 1 was
skipped.

Artifact produced:

```
target/mule-aws-secrets-poc-1.0.0-mule-application.jar
```

To inspect dependency resolution:

```bash
mvn dependency:tree
```

## 15. Do not expect this to connect to RDS from a developer laptop

The RDS instance is private and the application has **no AWS credentials** locally. Running this
application on a developer laptop is expected to:

- serve `/health` successfully (it does not touch the database), and
- fail `/db-test` with HTTP `503`, because neither Secrets Manager nor the private RDS endpoint is
  reachable and the default credential provider chain has nothing to resolve.

That `503` is the correct and expected local behaviour, not a defect. Do not attempt to reach RDS
from a laptop, and do not work around it with static AWS credentials or an SSH tunnel.

## 16. Where real DB testing happens

Actual database testing happens **only after** the application is containerized and deployed into
EKS, where EKS Pod Identity supplies AWS credentials and the pod has network reachability to the
private RDS endpoint. Containerization and Kubernetes manifests are a later phase and are
intentionally not part of this repository yet.

---

# Container / EKS Deployment

Phase 1b: containerize the approved Mule application and prepare Kubernetes manifests for the
existing EKS PoC environment. **Nothing here creates AWS or Kubernetes resources**, and no image
has been pushed or deployed. The Mule application logic, AWS SDK versions, database configuration,
endpoints and Maven decisions are unchanged.

## Artifacts

| File | Purpose |
|---|---|
| `Dockerfile` | Two-stage build: unpack Mule EE 4.12.3, then assemble a non-root runtime image |
| `docker/mule-entrypoint.sh` | Validates and installs the mounted license, then execs Mule in the foreground |
| `.dockerignore` | Keeps license material and build noise out of the build context |
| `k8s/deployment.yaml` | Deployment `mule-aws-secrets-poc` in namespace `mule-poc` |
| `k8s/service.yaml` | ClusterIP Service on port 8081 |

## Why the license is mounted instead of baked into the image

A Mule EE license is licensed material and a credential-like artifact. Baking it into an image
layer would mean:

- it is permanently recoverable from the image history by anyone who can pull the image, even if a
  later layer deletes it;
- it would be replicated into ECR and every node that caches the image;
- rotating or replacing the license would require rebuilding and redeploying the image.

Instead the license stays outside the image entirely and is supplied at runtime from a Kubernetes
Secret, mounted read-only. The build actively enforces this: the Dockerfile asserts
`conf/muleLicenseKey.lic` does not exist after unpacking and again in the final stage, and
`.dockerignore` excludes `*.lic` and `**/*.lic` so no license can reach the Docker daemon even
accidentally. The entrypoint additionally refuses to start if a license is already present before
installation, which would indicate a license had been baked in.

`license2027.lic` and `muleLicenseKey.lic` are never copied into the image and are git-ignored.

## Required local Mule standalone ZIP

The Docker build does **not** download Mule. The Mule EE standalone distribution must be obtained
from the official MuleSoft source (Anypoint Platform / MuleSoft customer download portal) and
placed at exactly this path in the build context:

```
.local/mule/mule-enterprise-standalone-4.12.3.zip
```

`.local/` is git-ignored and must never be committed. It is deliberately **not** listed in
`.dockerignore`, because the Docker build needs to read the ZIP from the build context.

The build fails fast with a clear error if the ZIP is absent.

## EKS architecture check (do this before the final image build)

The developer machine is likely Apple Silicon (`arm64`). The EKS node architecture must **not** be
assumed to match. Check it first:

```bash
kubectl get nodes \
  -o custom-columns='NAME:.metadata.name,ARCH:.status.nodeInfo.architecture,OS:.status.nodeInfo.operatingSystem'
```

The `Dockerfile` is architecture-neutral (no architecture-specific downloads or binaries), so the
target platform is chosen at build time.

If the nodes report `amd64`:

```bash
docker buildx build \
  --platform linux/amd64 \
  -t mule-aws-secrets-poc:1.0.0 \
  --load \
  .
```

If the nodes report `arm64`, substitute `--platform linux/arm64`.

Note that `aws-crt` ships native binaries for multiple platforms inside its JAR, so the
application layer itself is portable; only the base image layer is platform-specific.

## Image build prerequisites

1. `mvn clean package` has produced `target/mule-aws-secrets-poc-1.0.0-mule-application.jar`.
2. `.local/mule/mule-enterprise-standalone-4.12.3.zip` exists.
3. The Docker daemon is running.

Image layout:

| Path in image | Contents |
|---|---|
| `/opt/mule` | Mule EE standalone 4.12.3 (`MULE_HOME`) |
| `/opt/mule/apps/mule-aws-secrets-poc.jar` | the deployable application |
| `/usr/local/bin/mule-entrypoint.sh` | entrypoint |
| `/run/secrets/mule/license.lic` | mounted at runtime, not in the image |

Base image: `eclipse-temurin:17-jre-jammy`. No incompatibility with Mule 4.12.3 was found — Mule
4.12 supports Java 17, and the standalone runtime needs only a JRE plus a standard glibc userland,
which the Jammy-based image provides (including the `bash` the entrypoint requires).

## Runtime user and security

The container runs as a dedicated non-root user `mule` (uid/gid `10001`), which owns `MULE_HOME` so
Mule can write the directories it needs: `conf` (license installation), `apps` (application
explosion at deploy time), `logs`, `domains`, `tmp` and `.mule`. Permissions are granted through
ownership (`chown -R mule:mule` plus `chmod -R u+rwX,go-w`) — no `chmod 777` and nothing
world-writable.

The pod sets `runAsNonRoot: true`, `runAsUser/runAsGroup: 10001`, `fsGroup: 10001` (so the
non-root user can read the mounted license Secret) and `seccompProfile: RuntimeDefault`. The
container sets `allowPrivilegeEscalation: false` and drops **all** Linux capabilities. Nothing runs
privileged and no host path is mounted.

`readOnlyRootFilesystem` is deliberately left `false`: Mule standalone writes runtime state under
`MULE_HOME`. Hardening that would require mapping every writable directory to a volume, which is
out of scope for Phase 1.

## Expected Kubernetes Secret

The Deployment references an **existing** Secret named `mule-runtime-license`. Create it manually
(this repository intentionally contains no manifest with the license in it):

```bash
kubectl create secret generic mule-runtime-license \
  --namespace mule-poc \
  --from-file=license.lic=/path/to/license2027.lic
```

It is mounted read-only with `defaultMode: 0440` at `/run/secrets/mule/license.lic`, and
`optional: false` so the pod fails loudly rather than starting unlicensed.

## Pod Identity ServiceAccount

The Deployment uses `serviceAccountName: mule-secrets-reader`. That ServiceAccount already exists
in namespace `mule-poc` and already has an EKS Pod Identity association, so this repository does
**not** create it.

At runtime the EKS Pod Identity Agent exposes container credentials to the pod, and the AWS SDK
default credential provider chain picks them up with no application configuration. This is what
lets the `aws-secretsmanager-jdbc` driver call `secretsmanager:GetSecretValue` on
`mule-poc/database/app`.

## No static AWS credentials

`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` are **absent by design** from
the image, the Dockerfile, the entrypoint and the Deployment. Do not add them. The only AWS-related
environment variables are region hints and the non-secret secret ID.

## Environment variables supplied by the Deployment

| Variable | Value |
|---|---|
| `HTTP_PORT` | `8081` |
| `DB_HOST` | `mule-poc-postgres.c1ym8w0m4wxm.us-east-2.rds.amazonaws.com` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `poctest` |
| `AWS_SECRET_ID` | `mule-poc/database/app` |
| `AWS_REGION` | `us-east-2` |
| `AWS_DEFAULT_REGION` | `us-east-2` |
| `AWS_SECRET_JDBC_REGION` | `us-east-2` |
| `AWS_SECRET_CACHE_TTL_SECONDS` | `60` |

## Health probes

All three probes use `GET /health` on port 8081:

| Probe | Period | Failure threshold | Effective allowance |
|---|---|---|---|
| `startupProbe` | 10s | 30 | up to ~5 minutes for Mule to boot and deploy the app |
| `readinessProbe` | 10s | 3 | removed from Service endpoints after ~30s unhealthy |
| `livenessProbe` | 20s | 3 | restarted after ~60s unhealthy |

The generous `startupProbe` exists because Mule standalone boot plus application deployment is slow
relative to typical containers; the liveness probe only begins after the startup probe succeeds, so
a slow boot cannot cause a restart loop.

### Why `/db-test` is not a liveness probe

`/health` proves only that Mule is alive and deliberately performs no database access.

Using `/db-test` for liveness would be actively harmful to this PoC. A Secrets Manager outage, an
RDS failover, or the credential rotation we are deliberately inducing would make `/db-test` return
`503`, Kubernetes would restart the pod, and the restart would reset the pod UID, `restartCount`,
the driver's secret cache and every PostgreSQL backend PID — destroying exactly the evidence the
experiment is built to collect. Worse, it would mask the failure as "Kubernetes healed it" instead
of surfacing the credential behaviour we need to observe.

Database health is therefore observed **on demand** by calling `/db-test`, never by the kubelet.

## Why `replicas: 1`

The rotation experiment tracks one stable pod: its UID, its `restartCount`, and the sequence of
`pg_backend_pid()` values it reports. Additional replicas would produce interleaved backend PIDs
from different JVMs with independent secret caches, making it impossible to tell a
new-connection-after-rotation from a request simply landing on a different pod.

For the same reason the Deployment uses `strategy: Recreate` rather than `RollingUpdate`, which
would otherwise briefly run two pods during any redeploy.

## Resource sizing

```
requests: cpu 500m,  memory 1Gi
limits:   cpu 1,     memory 2Gi
```

**These values are PoC-only and are not a production or Runtime Fabric sizing recommendation.**
They are intended to let one Mule runtime boot and serve two trivial endpoints. JVM heap is
deliberately left untuned — no `-Xmx`/`-Xms` overrides are set — because tuning now would add a
variable to the rotation experiment. Real sizing requires load testing, GC analysis and an actual
application workload.

## Internal testing only

The Service is `ClusterIP` on port 8081. There is no LoadBalancer, no Ingress and no public
exposure. Test from inside the cluster, for example:

```bash
kubectl -n mule-poc port-forward svc/mule-aws-secrets-poc 8081:8081
curl -s localhost:8081/health
curl -s localhost:8081/db-test
```

## Not in this phase

TLS is Phase 2: no Secrets Store CSI driver, no P12 mounts, no HTTPS listener, no TLS context and
no Ingress. The build-time HTTP-not-HTTPS warning from Mule is accepted for Phase 1.

## Next phase

Pushing the image to ECR and deploying to EKS is the **next** phase, after review of these
artifacts. That phase will need to: create/confirm the ECR repository, authenticate Docker to ECR,
tag and push `mule-aws-secrets-poc:1.0.0`, replace `IMAGE_REPOSITORY_PLACEHOLDER` in
`k8s/deployment.yaml` with the ECR URI, create the `mule-runtime-license` Secret, and only then
apply the manifests.

---

## Future experiment: credential rotation without restart

**Not implemented yet.** No rotation automation exists in this repository. Documented here so the
Phase 1 design can be reviewed against the experiment it has to support.

**Baseline**

1. Deploy to EKS. Secret `mule-poc/database/app` contains password **A**, which works.
2. Call `/db-test` several times. Record `backendPid`, `user` and `timestamp`.
3. Record the Kubernetes pod UID and restart count.

**Rotation**

4. Change the database password from **A** to **B** and update the secret accordingly.

**Restrictions** — none of the following are allowed during the experiment:

- do **not** restart Mule
- do **not** recreate or delete the pod
- do **not** redeploy the application

**Evidence to collect**

5. Call `/db-test` repeatedly after rotation and record each `backendPid`.
6. Compare `pg_backend_pid()` values before and after rotation, to distinguish a reused physical
   session from a newly authenticated one.
7. Confirm the pod UID and restart count are unchanged, proving Mule was never restarted.
8. Optionally correlate with `pg_stat_activity` on the RDS side.

**Goal**

Prove that a **new physical database connection can authenticate after rotation without restarting
the Mule pod** — that is, a `/db-test` call that yields a new `backend_pid` succeeds using password
**B**, while the pod UID and restart count remain unchanged.

Open questions this experiment should also answer: how long the driver serves a cached secret before
refetching, and whether an authentication failure triggers a secret refresh and retry or surfaces as
a hard `503`.
