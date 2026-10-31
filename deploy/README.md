# Deploy

Local and deployment configuration.

| Path | What |
| --- | --- |
| `../docker-compose.yml` | Local stack: Postgres 18, Keycloak, fake GCS |
| `postgres/init/` | Creates the Keycloak database on first Postgres start |
| `keycloak/realm.json` | The `zeitwide` realm, imported on Keycloak start |
| `keycloak/branding/` | Zeitwide logo for the Google and Microsoft OAuth consent screens |
| `fake-gcs/` | Buckets pre-created in the fake GCS server (one folder per bucket) |
| `gcp/` | OpenTofu for the dev and prod GCP environments; its README lists every manual step |

For local Google, Microsoft, or GitHub login, copy `.env.example` to `.env`, create OAuth
apps with callback URLs such as `http://localhost:8180/realms/zeitwide/broker/google/endpoint`,
and fill their client IDs and secrets into `.env` before the first Keycloak start. Run
`docker compose up -d keycloak` and open `http://localhost:8180/realms/zeitwide/account/`.

- **Google:** Google Cloud console > APIs & Services > OAuth consent screen (External,
  scopes `openid`, `email`, `profile`), then Credentials > Create credentials > OAuth
  client ID > Web application. Add the authorized redirect URI
  `http://localhost:8180/realms/zeitwide/broker/google/endpoint`. Copy the client ID and
  secret into `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.
- **Microsoft:** Microsoft Entra admin center > App registrations > New registration.
  Supported account types: any organizational directory and personal Microsoft accounts
  (Keycloak uses the `common` endpoint). Platform Web, redirect URI
  `http://localhost:8180/realms/zeitwide/broker/microsoft/endpoint`. Copy the
  Application (client) ID into `MICROSOFT_CLIENT_ID`, then Certificates & secrets > New
  client secret and copy its **Value** (not the Secret ID) into `MICROSOFT_CLIENT_SECRET`.
  Microsoft emails aren't verified, so the realm doesn't trust them (`trustEmail: false`).

Google and Microsoft show the name and logo set on the OAuth app itself, not anything
Keycloak sends, so set them in each console. The logos are in `keycloak/branding/`:

- **Google:** Google Auth Platform > Branding. App name `Zeitwide`, app logo
  `zeitwide-logo-120.png`, support email, and the home, privacy policy and terms links.
  Add the domain that serves Keycloak under authorized domains. Google shows the logo
  only after it verifies the brand (Verification centre), so until then the screen shows
  the name alone. Keep the app in Testing with listed test users until it is verified.
- **Microsoft:** the app registration > Branding & properties. Name `Zeitwide`, logo
  `zeitwide-logo-215.png` (215 x 215, under 100 KB), home page and terms and privacy
  links. Microsoft labels the app "unverified" until you complete publisher verification
  (a verified Microsoft AI Cloud Partner Program ID) and set a verified publisher domain.

Keycloak imports the realm only when it doesn't exist yet. If it already started without
these values, either paste the ID and secret into the admin console (realm `zeitwide` >
Identity providers > google or microsoft) or reset it with `docker compose down`, drop
the `keycloak` database, and start again. For dev and prod, the client IDs go in
`deploy/gcp/envs/*.tfvars` and the secrets in Secret Manager (see `gcp/README.md`), with
the deployed Keycloak URL in place of `http://localhost:8180`.

Alloy and Grafana configuration land here once observability is wired up.
