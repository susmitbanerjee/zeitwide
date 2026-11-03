# Next.js standalone server for Cloud Run. Base images are pinned by digest.
FROM node:22.23.4-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Answer pages are prerendered from the live API at build time, and their canonical URLs use
# the public origin. The API needs a key: the web app's own, passed as a BuildKit secret so it
# never lands in a layer. A missing secret fails the build here, not with confusing 401s later.
ARG ZEITWIDE_API_URL
ARG SITE_URL
RUN --mount=type=secret,id=zeitwide_api_key,required=true \
    ZEITWIDE_API_KEY="$(cat /run/secrets/zeitwide_api_key)" npm run build

FROM node:22.23.4-bookworm-slim@sha256:4b150b48b4116e2a22f2c39cc27025fbf4f153e2078fd3b14d159fdb5c7279da
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=8080
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public
USER node
EXPOSE 8080
CMD ["node", "server.js"]
