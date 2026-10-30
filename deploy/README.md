# Deploy

Local and deployment configuration.

| Path | What |
| --- | --- |
| `../docker-compose.yml` | Local stack: Postgres 18, Keycloak, fake GCS |
| `postgres/init/` | Creates the Keycloak database on first Postgres start |
| `keycloak/realm.json` | The `zeitwide` realm, imported on Keycloak start |
| `fake-gcs/` | Buckets pre-created in the fake GCS server (one folder per bucket) |
| `gcp/` | OpenTofu for the dev and prod GCP environments; its README lists every manual step |

For local Google, Microsoft, or GitHub login, copy `.env.example` to `.env`, create OAuth
apps with callback URLs such as `http://localhost:8180/realms/zeitwide/broker/google/endpoint`,
and fill their client IDs and secrets into `.env` before the first Keycloak start. Run
`docker compose up -d keycloak` and open `http://localhost:8180/realms/zeitwide/account/`.

Alloy and Grafana configuration land here once observability is wired up.
