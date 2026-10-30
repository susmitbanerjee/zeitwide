# Keycloak for Cloud Run, pre-built so the service can `start --optimized`. The base is
# pinned by digest and matches docker-compose.yml.
FROM quay.io/keycloak/keycloak:26.7.5@sha256:37dbaf6f0722c9ec246335f36e1ef8b2e6cb960f7c27e0d8c615121a3d475a85 AS build
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true

FROM quay.io/keycloak/keycloak:26.7.5@sha256:37dbaf6f0722c9ec246335f36e1ef8b2e6cb960f7c27e0d8c615121a3d475a85
COPY --from=build /opt/keycloak/ /opt/keycloak/
ENV KC_DB_POOL_MAX_SIZE=3
# Imported on start with --import-realm; a realm that already exists is left alone.
COPY realm.json /opt/keycloak/data/import/realm.json
