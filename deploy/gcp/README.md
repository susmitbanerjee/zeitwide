# GCP infrastructure (OpenTofu)

OpenTofu for one Zeitwide environment per workspace (`dev`, `prod`) in `us-central1`.
It replaces Cloud SQL from [docs/design.md](../../docs/design.md) with PostgreSQL on
Aiven's free plan, which you create by hand.

| File | Creates |
| --- | --- |
| `apis.tf` | Required Google APIs (left on when a workspace is destroyed) |
| `iam.tf` | One service account per workload, resource-level role bindings |
| `secrets.tf` | Secret Manager secrets, **containers only**: values are added by hand |
| `cloud_run.tf` | Services `zeitwide-api-<env>`, `zeitwide-web-<env>`, `keycloak-<env>`; job `zeitwide-pipeline-<env>` |
| `scheduler.tf` | Three Cloud Scheduler jobs: pipeline every 15 min, outbox drain every minute, daily release and backup |
| `storage.tf` | Snapshots bucket (versioned, kept forever) and backups bucket (30-day expiry) |
| `artifact_registry.tf` | Docker repository that keeps the last 3 versions of each image |
| `wif.tf` | Workload Identity Federation for GitHub Actions (no JSON keys) |
| `budget.tf` | Budget of `budget_amount` in `budget_currency` (default USD 10), alerts at 50/90/100% |
| `envs/*.tfvars` | Per-environment IDs and URLs (nothing secret) |

Every resource that supports labels gets `app=zeitwide` and `env=<workspace>` through
the provider's `default_labels`. Service accounts, scheduler jobs, WIF pools and budgets
can't carry labels.

All names include the environment, so dev and prod can share one project. One project
per environment is still the better choice: IAM, quotas and the budget stay separate.
If they share a project, both budgets watch the same spend.

## What runs where

| Workload | Identity | Can do |
| --- | --- | --- |
| `zeitwide-api` | `zeitwide-api-<env>` | Read its secrets; read snapshots |
| `zeitwide-web` | `zeitwide-web-<env>` | Read its secrets |
| `keycloak` | `zeitwide-keycloak-<env>` | Read its secrets |
| `zeitwide-pipeline` job | `zeitwide-pipeline-<env>` | Read its secrets; create and read snapshots; create backups (no overwrite or delete) |
| Scheduler: pipeline runs | `zeitwide-sched-run-<env>` | Start the pipeline job, with argument overrides |
| Scheduler: outbox drain | `zeitwide-sched-drain-<env>` | Nothing in GCP; the API accepts its OIDC token |
| GitHub Actions | `zeitwide-deployer-<env>` | Push images, deploy new revisions of the 3 services and the job, deploy Firebase Hosting |

The deployer can't change IAM, secrets or infrastructure. You apply OpenTofu yourself,
with your own credentials.

### Database connections (Aiven free plan: 20)

| Client | Instances | Pool | Worst case |
| --- | --- | --- | --- |
| API (Hikari) | 2 | 4 | 8 |
| Keycloak | 1 | 3 | 3 |
| Pipeline, regular run | 1 | 2 | 2 |
| Pipeline, daily run overlapping a regular run | 1 | 2 + 1 for `pg_dump` | 3 |
| **Total** | | | **16** |

Raise `max_instances` or a pool size in `locals.tf` only after checking this sum.

## Before you start

- OpenTofu 1.8 or later (`winget install OpenTofu.Tofu`) and the gcloud CLI.
- A GCP project per environment, linked to a billing account.
- Your account needs Owner on the project (or the admin roles for Run, IAM, Secret
  Manager, Storage, Artifact Registry, Scheduler and Workload Identity Pools), plus
  **Billing Account Costs Manager** on the billing account for the budget.
- Set `budget_currency` to the billing account's currency (`gcloud billing accounts
  describe`), and `budget_amount` in that currency; the Budgets API rejects any other.
- If an organization policy restricts domain sharing (`iam.allowedPolicyMemberDomains`),
  allow `allUsers` on this project. The three services are public at the Cloud Run
  layer and do their own auth.

## Manual steps

### 1. Sign in

```bash
gcloud auth login
gcloud auth application-default login
```

### 2. Create the state bucket (once per project)

```bash
gcloud storage buckets create gs://PROJECT_ID-tofu-state --project=PROJECT_ID --location=us-central1 --uniform-bucket-level-access --public-access-prevention
```

```bash
gcloud storage buckets update gs://PROJECT_ID-tofu-state --versioning
```

### 3. Fill in `envs/<env>.tfvars`

- `project_id`, `billing_account` (`gcloud billing accounts list`).
- `google_client_id`, `microsoft_client_id`, and `github_client_id` for the OAuth apps
  used by Keycloak. The client secrets are entered in Secret Manager in step 6.
- `github_repository` and `github_repository_id`. Get the ID with:

  ```bash
  gh api repos/OWNER/zeitwide --jq .id
  ```

- `web_origin`: the Firebase Hosting site you'll create in step 10 (`https://<site-id>.web.app`)
  or a custom domain.

### 4. Initialise and pick the workspace

From `deploy/gcp/`:

```bash
tofu init -backend-config="bucket=PROJECT_ID-tofu-state" -backend-config="prefix=zeitwide"
```

```bash
tofu workspace new dev
```

Commit the `.terraform.lock.hcl` that `tofu init` writes. Later, switch with
`tofu workspace select dev` or `tofu workspace select prod`. The default workspace is
refused.

### 5. Create the Aiven database

1. In the [Aiven console](https://console.aiven.io), create a **PostgreSQL** service on
   the free plan, in the region closest to `us-central1` that the free plan offers.
   Check Aiven's current limits on free services per account. Dev and prod each need
   their own service, because the database and user names below are the same in both.
2. Create the databases `zeitwide` and `keycloak`.
3. Connect as `avnadmin` and create one login per client. Generate each password with
   `openssl rand -base64 32`:

   ```sql
   CREATE ROLE zeitwide_api LOGIN PASSWORD '...';
   CREATE ROLE zeitwide_pipeline LOGIN PASSWORD '...';
   CREATE ROLE zeitwide_backup LOGIN PASSWORD '...';
   CREATE ROLE keycloak LOGIN PASSWORD '...';

   ALTER DATABASE zeitwide OWNER TO zeitwide_api;   -- Flyway runs as the API
   ALTER DATABASE keycloak OWNER TO keycloak;
   ```

   Don't grant anything on `zeitwide` by hand. Each time the API starts, Flyway runs
   `apply_role_grants()` (migration `V9__role_grants.sql`):
   - The pipeline can write evidence, proposals, changes and releases.
   - It can change entries only through `publish_entry_version`.
   - It can't read accounts, keys or webhooks.
   - The backup login gets read-only access to every table.

   For the `keycloak` database, connect as `keycloak` and let the backup login read it:

   ```sql
   GRANT SELECT ON ALL TABLES IN SCHEMA public TO zeitwide_backup;
   GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO zeitwide_backup;
   ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO zeitwide_backup;
   ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO zeitwide_backup;
   ```
4. Download the service's **CA certificate** (Overview > Connection information).
5. Note the host and port. Every client connects with `sslmode=verify-full` against
   that CA, so it checks both the certificate chain and the hostname.

Cloud Run has no fixed egress IP without a NAT gateway (which isn't free), so leave
Aiven's IP allowlist open. TLS with hostname checks plus strong per-client passwords are
the protection.

### 6. Create the secrets, then add their values

Create the secret containers and their IAM first. The services can't start until every
secret has a value.

```bash
tofu apply -var-file=envs/dev.tfvars -target=google_secret_manager_secret_iam_member.readers
```

Add each value from a file or stdin, so it never lands in shell history:

```bash
gcloud secrets versions add zeitwide-dev-db-ca-cert --data-file=ca.pem
```

Use `--data-file=-` and paste, then press Ctrl+D (Ctrl+Z, Enter on Windows), for
the others. Delete local copies afterwards.

| Secret (`zeitwide-<env>-…`) | Value |
| --- | --- |
| `db-host` | Aiven host name |
| `db-port` | Aiven port |
| `db-ca-cert` | Aiven CA certificate (PEM) |
| `db-api-password` | Password of `zeitwide_api` |
| `db-pipeline-password` | Password of `zeitwide_pipeline` |
| `db-backup-password` | Password of `zeitwide_backup` |
| `db-keycloak-password` | Password of `keycloak` |
| `keycloak-admin-password` | `openssl rand -base64 32`; used only for Keycloak's first start (step 12) |
| `google-oauth-client-secret` | OAuth client secret for the Google identity provider |
| `microsoft-oauth-client-secret` | OAuth client secret for the Microsoft identity provider |
| `github-oauth-client-secret` | OAuth client secret for the GitHub identity provider |
| `webhook-encryption-key` | `openssl rand -base64 32`; AES-256-GCM key used to encrypt signing secrets at rest |
| `receipt-signing-key` | Ed25519 PKCS#8 private key PEM, loaded by the API at startup; never commit it or put it in tfvars |
| `web-auth-secret` | `openssl rand -base64 32` (Auth.js cookie encryption) |
| `otlp-headers` | `Authorization=Basic <base64 of instance-id:token>` from Grafana Cloud > OpenTelemetry |
| `github-release-token` | Fine-grained token for the public dataset repository only, with `contents: write` and `pull_requests: write` (the daily job opens a pull request per release) |

To rotate a secret, add a new version and deploy a new revision. Every service reads
`latest` when an instance starts.

Receipt configuration also needs the active key ID and public key ring in the environment's
tfvars. These values are public: `receipt_key_id` selects the signing key, and
`receipt_public_keys_json` is a JSON array with `key_id`, `public_key` (SPKI PEM),
`valid_from` and `valid_to` fields. Keep every retired public key in this ring for as long as
receipts made with it must verify; its `valid_to` limits the receipt `issued_at` values that
can use it. On rotation, add the new public key and validity interval, set the new active key
ID, add the matching private key as a new `receipt-signing-key` Secret Manager version, and
deploy. The API checks at startup that the active private key matches its public entry.

Webhook signing secrets are encrypted with AES-256-GCM before they are stored. Keep the
`webhook-encryption-key` Secret Manager value stable while ciphertext exists; changing it
requires re-encrypting every subscription secret before deploying the replacement key.
For local API runs, set `ZEITWIDE_WEBHOOK_ENCRYPTION_KEY` to the output of
`openssl rand -base64 32`.

For local API runs, set `ZEITWIDE_RECEIPT_KEY_ID`, `ZEITWIDE_RECEIPT_SIGNING_KEY` and
`ZEITWIDE_RECEIPT_PUBLIC_KEYS_JSON`. Generate a local pair with
`openssl genpkey -algorithm ed25519 -out receipt-private.pem` and
`openssl pkey -in receipt-private.pem -pubout -out receipt-public.pem`. Keep the private
file outside the repository. The public ring JSON has this shape:

```json
[{"key_id":"local-1","public_key":"-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----","valid_from":"2026-01-01T00:00:00Z","valid_to":null}]
```

Register each provider callback as `<auth URL>/realms/zeitwide/broker/<provider>/endpoint`;
for local development the auth URL is `http://localhost:8180`. The imported realm resolves
the `*_CLIENT_ID` and `*_CLIENT_SECRET` placeholders from Keycloak's environment.

### 7. Plan and apply everything

```bash
tofu plan -var-file=envs/dev.tfvars -out=dev.tfplan
```

```bash
tofu apply dev.tfplan
```

The first apply starts every service on Google's sample image. Real images come from CI
(step 9), and OpenTofu ignores image changes after that.

### 8. Connect GitHub Actions

Copy two outputs into the repository's variables (Settings > Secrets and variables >
Actions > Variables). They are identifiers, not secrets.

```bash
tofu output -raw github_workload_identity_provider
```

```bash
tofu output -raw github_deployer_service_account
```

In the deploy workflow, set `permissions: id-token: write` and authenticate with
`google-github-actions/auth`, passing `workload_identity_provider` and
`service_account`. Pin every action by commit SHA. Only workflows from the configured
repository ID, running on a ref in `github_deploy_refs`, can get a token.

### 9. Build and deploy images

Push to `tofu output -raw image_repository`, then deploy with
`gcloud run deploy <service> --image=…` and `gcloud run jobs deploy zeitwide-pipeline-<env> --image=…`.
When CI can't run (for example, the month's Actions minutes are used up), do the same
from your machine, from the repository root:

```bash
bash deploy/gcp/deploy-images.sh
```

It builds the API with Jib and the others from `pipeline/Dockerfile`, `web/Dockerfile`
and `deploy/keycloak/Dockerfile`, tags them with `git describe --dirty`, and rolls out
the API first so its Flyway migrations run before the pipeline uses the schema.
The images need:

- **Keycloak:** an image built `FROM quay.io/keycloak/keycloak` that runs
  `kc.sh build --db=postgres --health-enabled=true` and imports
  `deploy/keycloak/realm.json` (`--import-realm`). The image runs `start --optimized` and
  sets `KC_DB_POOL_MAX_SIZE=3`.
- **Pipeline:** the `pg_dump` client at the same major version as the Aiven server
  (`postgresql-client-18`: `pg_dump` refuses to dump a newer server, and Aiven runs PostgreSQL 18), and the CLI commands `run-due` (the default) and `daily`.

### 10. Set up Firebase Hosting

1. In the [Firebase console](https://console.firebase.google.com), add Firebase to the
   GCP project, then create a Hosting site (for example `zeitwide-dev`). The site ID
   gives `https://<site-id>.web.app`; make sure `web_origin` matches.
2. Add a `firebase.json` that sends every request to the web service:

   ```json
   {
     "hosting": {
       "site": "zeitwide-dev",
       "public": "public",
       "rewrites": [{ "source": "**", "run": { "serviceId": "zeitwide-web-dev", "region": "us-central1" } }]
     }
   }
   ```

3. Deploy it from CI with `firebase deploy --only hosting`. The deployer account has
   `roles/firebasehosting.admin`.

Firebase Hosting strips every cookie except one named `__session` before it forwards a
request to Cloud Run. The web app's session cookie must use that name, or sign-in won't
work behind Hosting.

### 11. DNS and custom domains

- **Web:** in Firebase Hosting, add the custom domain and create the TXT and A records it
  shows. Then set `web_origin` to the new origin.
- **API and Keycloak:** verify the domain once in Google Search Console, then map each
  subdomain:

  ```bash
  gcloud beta run domain-mappings create --service=zeitwide-api-dev --domain=api.dev.example.com --region=us-central1
  ```

  ```bash
  gcloud beta run domain-mappings create --service=keycloak-dev --domain=auth.dev.example.com --region=us-central1
  ```

  Create the CNAME records it prints (`ghs.googlehosted.com.`). Google issues the
  certificates, which can take up to an hour or so.
- Set `api_base_url` and `auth_base_url` in the tfvars, then plan and apply again.
  Keycloak always has its hostname pinned (`KC_HOSTNAME`): to the custom domain once
  `auth_base_url` is set, or to the service's deterministic `run.app` URL before then.

### 12. Finish Keycloak

1. Sign in to `<auth URL>/admin` as `bootstrap-admin` with `keycloak-admin-password`.
2. Create a permanent admin with MFA, then delete `bootstrap-admin`. The bootstrap
   variables only take effect when no admin exists.
3. The import configures `zeitwide-web` redirect, web origin and post-logout URI from
   `web_origin`, and adds the `zeitwide-api` audience mapper.
4. To register MCP clients dynamically, create an initial access token under **Clients >
   Initial access tokens**, set a short expiry and client limit, then provide it to the MCP
   client through a secret store. The registration endpoint is
   `/realms/zeitwide/clients-registrations/openid-connect`.

## What the application code must do

This configuration hands these settings to the apps. The apps must use them as follows:

- **API:** read the JDBC URL from `SPRING_DATASOURCE_URL`. Validate JWTs against
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and audience `zeitwide-api`.
  Allow only `ZEITWIDE_CORS_ALLOWED_ORIGINS`. Keep `/internal/**` out of the Keycloak
  filter chain and verify instead that the caller's Google-signed OIDC token has:
  - issuer `https://accounts.google.com`
  - audience `ZEITWIDE_OUTBOX_DRAIN_AUDIENCE`
  - `email` equal to `ZEITWIDE_OUTBOX_DRAIN_INVOKER`, with `email_verified` true

  The service is public, so that token check is the only thing protecting the drain
  endpoint. The MCP endpoint is `/mcp`; `ZEITWIDE_MCP_RESOURCE_URL` is its public URL,
  used by `/.well-known/oauth-protected-resource/mcp` and the OAuth challenge. MCP clients
  use either a Zeitwide API key or a Keycloak bearer token with audience `zeitwide-api`.
- **Pipeline:** connect with the standard `PG*` variables. Keep its pool at
  `ZEITWIDE_DB_POOL_MAX_SIZE`. Implement `run-due` and `daily`. In `daily`, run
  `pg_dump` for each database in `ZEITWIDE_BACKUP_DATABASES` as `ZEITWIDE_BACKUP_DB_USER`.
  Name each dump with a new object path, because the pipeline can't overwrite objects.
- **Web:** read `ZEITWIDE_API_URL` and the `AUTH_*` variables, and name the session
  cookie `__session` (step 10).

## Cost

Everything scales to zero. At MVP traffic the main costs are:

- **Cloud Run:** within the free tier.
- **Secret Manager:** 12 secrets per environment. Past the 6 free active versions per
  billing account, each costs $0.06 a month, so about $1 a month for both environments.
- **Cloud Scheduler:** 3 free jobs per billing account. Two environments make 6, so 3
  of them cost $0.10 a month each.
- **Cloud Storage:** within the 5 GB free tier until the snapshots grow.

A budget only sends alerts. It never stops spending.

## Trade-offs to know

- The services allow `allUsers` at the Cloud Run layer, because public answer pages and
  anonymous API calls need it. Auth happens in the apps.
- Aiven accepts connections from any IP (step 5).
- `db-backup-password` can read every table, including API key hashes and encrypted
  webhook secrets, and the pipeline account can read it. A separate backup job with its own account would remove that
  overlap, at the cost of a fourth scheduler job.
- Artifact Registry keeps only 3 versions per image, so you can roll back at most two
  deploys.
- Deletion protection is on for the prod services and job, and prod buckets can't be
  destroyed while they hold objects. Turn these off in code before you destroy prod.
