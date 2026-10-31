# Firebase Hosting for the web app

Firebase Hosting is the CDN and TLS in front of the `zeitwide-web-<env>` Cloud Run service.
Every request is rewritten to the service; `public/` stays empty.

## What goes through the CDN, and what doesn't

Firebase Hosting forwards no cookie except `__session` to Cloud Run, so it can only serve
pages that need no session:

| Host | Serves | How |
| --- | --- | --- |
| `web_origin` (for example `zeitwide.com`) | Answer pages, calendar pages, docs, sitemap | Firebase Hosting, cached at the edge |
| `web_app_origin` (for example `app.zeitwide.com`) | `/app`, `/admin`, `/signin`, `/api/auth`, `/api/zw` | Straight to Cloud Run (domain mapping or its run.app URL) |

The web app redirects signed-in routes that arrive on the public host to the app host
(`src/proxy.ts`), and marks everything on the app host `noindex`.

Answer pages are prerendered and revalidated every five minutes (`cacheLife("calendar")` in
`web/next.config.ts`), so Next.js sends `Cache-Control: s-maxage=300, stale-while-revalidate`
and the CDN keeps them for that long. Signed-in pages render per request and send
`Cache-Control: private, no-store`.

## Deploy

1. Map the targets to your Hosting sites once:

   ```bash
   firebase target:apply hosting dev zeit-dev-6eaae4e0 --project project-6eaae4e0-3164-4e31-bc4
   ```

2. Build the web image with the API it prerenders from and the public origin (the build
   fetches every calendar's current year and next 90 days):

   ```bash
   ZEITWIDE_API_URL=https://<api> SITE_URL=https://zeit-dev-6eaae4e0.web.app \
     bash deploy/gcp/deploy-images.sh web
   ```

3. Deploy Hosting:

   ```bash
   firebase deploy --only hosting:dev --project project-6eaae4e0-3164-4e31-bc4 --config deploy/firebase/firebase.json
   ```

## Refreshing answer pages when a calendar changes

Pages refresh on their own within five minutes of a new calendar revision. To refresh
them at once, subscribe the app to its own webhook:

1. Create a webhook for `https://<web_app_origin>/api/revalidate` with every change type
   (`POST /v1/account/webhooks`).
2. Store the signing secret it returns as the web service's `ZEITWIDE_WEBHOOK_SECRET`.

An API key for the build (`--secret id=zeitwide_api_key`) lifts the anonymous rate limit;
the build makes about ten calls.
