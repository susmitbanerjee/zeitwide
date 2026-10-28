# Deploy

Local and deployment configuration.

| Path | What |
| --- | --- |
| `../docker-compose.yml` | Local stack: Postgres 16, Keycloak, fake GCS |
| `postgres/init/` | Creates the Keycloak database on first Postgres start |
| `keycloak/settlecal-realm.json` | The `settlecal` realm, imported on Keycloak start |
| `fake-gcs/` | Buckets pre-created in the fake GCS server (one folder per bucket) |
| `gcp/` | OpenTofu for the dev and prod GCP environments; its README lists every manual step |

Alloy and Grafana configuration land here once observability is wired up.
