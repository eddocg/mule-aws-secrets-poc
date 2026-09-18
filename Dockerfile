# syntax=docker/dockerfile:1.7
#
# mule-aws-secrets-poc - Mule EE Standalone 4.12.3 on Java 17.
#
# Architecture-neutral by design: no architecture-specific downloads or binaries are
# referenced, so the same Dockerfile builds for linux/amd64 and linux/arm64. The target
# platform is selected at build time with `docker buildx build --platform ...`, because the
# developer machine architecture (often arm64 on Apple Silicon) must NOT be assumed to match
# the EKS node architecture. See README "Container / EKS Deployment".
#
# The Mule EE standalone distribution is NOT downloaded by this build. It must be supplied
# manually from the official MuleSoft download portal / Anypoint Platform and placed at
# .local/mule/mule-enterprise-standalone-4.12.3.zip in the build context. That path is
# git-ignored and never committed.
#
# No Mule license and no AWS credentials are baked into this image. The license is mounted at
# runtime from a Kubernetes Secret and installed by docker/mule-entrypoint.sh. AWS credentials
# come from EKS Pod Identity via the AWS SDK default credential provider chain.

ARG JAVA_BASE_IMAGE=eclipse-temurin:17-jre-jammy
ARG MULE_VERSION=4.12.3
ARG APP_VERSION=1.0.0


# ---------------------------------------------------------------------------
# Stage 1: unpack the Mule EE standalone distribution.
# Kept separate so unzip and the ~700MB zip never land in the final image layers.
# ---------------------------------------------------------------------------
FROM ${JAVA_BASE_IMAGE} AS mule-unpack

ARG MULE_VERSION

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

COPY .local/mule/mule-enterprise-standalone-${MULE_VERSION}.zip /tmp/mule.zip

RUN set -eux; \
    mkdir -p /unpack; \
    unzip -q /tmp/mule.zip -d /unpack; \
    test -d "/unpack/mule-enterprise-standalone-${MULE_VERSION}"; \
    mv "/unpack/mule-enterprise-standalone-${MULE_VERSION}" /opt/mule; \
    rm -f /tmp/mule.zip; \
    # Fail the build early if a license was somehow shipped inside the distribution.
    test ! -e /opt/mule/conf/muleLicenseKey.lic; \
    test -x /opt/mule/bin/mule


# ---------------------------------------------------------------------------
# Stage 2: runtime image
# ---------------------------------------------------------------------------
FROM ${JAVA_BASE_IMAGE}

ARG MULE_VERSION
ARG APP_VERSION
ARG MULE_UID=10001
ARG MULE_GID=10001

LABEL org.opencontainers.image.title="mule-aws-secrets-poc" \
      org.opencontainers.image.description="Mule 4 PoC for AWS Secrets Manager JDBC credential retrieval and rotation" \
      org.opencontainers.image.version="${APP_VERSION}" \
      com.mulesoft.runtime.version="${MULE_VERSION}"

ENV MULE_HOME=/opt/mule \
    MULE_LICENSE_PATH=/run/secrets/mule/license.lic

# Dedicated non-root account. Mule standalone runs correctly as an unprivileged user as long
# as it owns MULE_HOME, which it does below.
RUN set -eux; \
    groupadd --gid "${MULE_GID}" mule; \
    useradd --uid "${MULE_UID}" --gid "${MULE_GID}" --home-dir "${MULE_HOME}" \
            --shell /usr/sbin/nologin --no-create-home mule

COPY --from=mule-unpack --chown=mule:mule /opt/mule ${MULE_HOME}

# The deployable Mule application. Mule auto-deploys anything in apps/ at startup.
COPY --chown=mule:mule \
     target/mule-aws-secrets-poc-${APP_VERSION}-mule-application.jar \
     ${MULE_HOME}/apps/mule-aws-secrets-poc.jar

COPY --chown=root:root docker/mule-entrypoint.sh /usr/local/bin/mule-entrypoint.sh

# Mule needs write access to conf (license installation), apps (application explosion on
# deploy), logs, domains, and its runtime temp/work directories. Ownership is granted to the
# mule user only - deliberately no world-writable permissions and no chmod 777.
RUN set -eux; \
    chmod 0755 /usr/local/bin/mule-entrypoint.sh; \
    mkdir -p \
        "${MULE_HOME}/conf" \
        "${MULE_HOME}/apps" \
        "${MULE_HOME}/logs" \
        "${MULE_HOME}/domains" \
        "${MULE_HOME}/tmp" \
        "${MULE_HOME}/.mule"; \
    chown -R mule:mule "${MULE_HOME}"; \
    chmod -R u+rwX,go-w "${MULE_HOME}"; \
    test ! -e "${MULE_HOME}/conf/muleLicenseKey.lic"

USER mule
WORKDIR ${MULE_HOME}

# Informational only. The listener binds to ${HTTP_PORT}, supplied by the Deployment.
EXPOSE 8081

ENTRYPOINT ["/usr/local/bin/mule-entrypoint.sh"]
