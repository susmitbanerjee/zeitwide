# Deploy

Local and deployment configuration.

| Path | What |
| --- | --- |
| `../docker-compose.yml` | Local stack: Postgres 18 and a fake GCS server |
| `branding/` | Zeitwide logo for the Google and Microsoft OAuth consent screens |
| `fake-gcs/` | Buckets pre-created in the fake GCS server (one folder per bucket) |
| `gcp/` | OpenTofu for the dev and prod GCP environments; its README lists every manual step |
| `firebase/` | Firebase Hosting config (kept for the `*.web.app` site; the apex domain goes straight to Cloud Run) |
| `cloudflare/` | Cloudflare Free plan in front of the apex and `api.`: DNS, SSL strict, and the `edge` Worker (Markdown negotiation, bot labels, telemetry, AI referral tags); its README has the free-plan budget note |
| `grafana/` | Grafana Cloud dashboards and alert rules |

## Sign-in apps

Zeitwide has no identity server. The web app signs people in with Google, Microsoft or
GitHub through Auth.js, so each provider needs an OAuth app whose callback URL is the web
app's: `<site>/api/auth/callback/<provider>`, where `<site>` is `https://zeitwide.com` (or
`http://localhost:3000` locally) and `<provider>` is `google`, `microsoft` or `github`. A
button appears only for a provider whose client ID is set.

- **Google:** Google Cloud console > APIs & Services > OAuth consent screen (External,
  scopes `openid`, `email`, `profile`), then Credentials > Create credentials > OAuth
  client ID > Web application. Authorized redirect URI
  `https://zeitwide.com/api/auth/callback/google`.
- **Microsoft:** Microsoft Entra admin center > App registrations > New registration.
  Supported account types: any organizational directory and personal Microsoft accounts.
  Platform Web, redirect URI `https://zeitwide.com/api/auth/callback/microsoft`. Copy the
  Application (client) ID, then Certificates & secrets > New client secret and copy its
  **Value** (not the Secret ID). Microsoft doesn't verify email addresses, so a Microsoft
  sign-in can never be a reviewer.
- **GitHub:** Settings > Developer settings > OAuth Apps > New OAuth App. Authorization
  callback URL `https://zeitwide.com/api/auth/callback/github`.

For local work add `http://localhost:3000/api/auth/callback/<provider>` as a second
redirect URI, and put the IDs and secrets in `web/.env.local` (see `web/.env.example`).
For dev and prod, the client IDs go in `deploy/gcp/envs/*.tfvars` and the secrets in
Secret Manager (see `gcp/README.md`).

Google and Microsoft show the name and logo set on the OAuth app itself, so set them in
each console. The logos are in `branding/`:

- **Google:** Google Auth Platform > Branding. App name `Zeitwide`, app logo
  `zeitwide-logo-120.png`, support email, and the home, privacy policy and terms links.
  Add `zeitwide.com` under authorized domains. Google shows the logo only after it
  verifies the brand (Verification centre), so until then the screen shows the name alone.
  Keep the app in Testing with listed test users until it is verified.
- **Microsoft:** the app registration > Branding & properties. Name `Zeitwide`, logo
  `zeitwide-logo-215.png` (215 x 215, under 100 KB), home page and terms and privacy
  links. Microsoft labels the app "unverified" until you complete publisher verification
  (a verified Microsoft AI Cloud Partner Program ID) and set a verified publisher domain.
