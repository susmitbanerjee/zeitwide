# Not secret: IDs and public DNS only. The API token is CLOUDFLARE_API_TOKEN; the Worker's two
# secrets are set with wrangler (README).
account_id  = "00000000000000000000000000000000" # set to your Cloudflare account ID (README, step 1)
zone_name   = "zeitwide.com"
environment = "dev"

# deploy/gcp maps zeitwide.com straight to the web service (Firebase Hosting would strip the
# sign-in cookies), so the apex points at the Cloud Run domain mapping's addresses:
# gcloud beta run domain-mappings describe --domain zeitwide.com --region us-central1
web_origin_ipv4 = ["216.239.32.21", "216.239.34.21", "216.239.36.21", "216.239.38.21"]
web_origin_ipv6 = ["2001:4860:4802:32::15", "2001:4860:4802:34::15", "2001:4860:4802:36::15", "2001:4860:4802:38::15"]

# Everything else GoDaddy served on 2026-11-04. There is no MX record.
other_records = {
  google_site_verification = {
    type    = "TXT"
    name    = "zeitwide.com"
    content = "\"google-site-verification=mmdIjCpl38KaCCsZt7T8XzERW_2eOG-6p3s2lwSSc7s\""
  }
  firebase_hosting_site = {
    type    = "TXT"
    name    = "zeitwide.com"
    content = "\"hosting-site=zeit-dev-6eaae4e0\""
  }
  dmarc = {
    type    = "TXT"
    name    = "_dmarc.zeitwide.com"
    content = "\"v=DMARC1; p=quarantine; adkim=r; aspf=r; rua=mailto:dmarc_rua@onsecureserver.net;\""
  }
}

# Grafana Cloud > your stack > Loki > Details (README, step 5).
loki_push_url = ""
loki_user     = ""
