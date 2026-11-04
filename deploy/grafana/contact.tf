resource "grafana_contact_point" "email" {
  name = "zeitwide-email"

  email {
    addresses               = [var.alert_email]
    single_email            = true
    disable_resolve_message = false
  }

  # The policy below refers to it, and Grafana refuses to delete a referenced contact point:
  # a rename creates the new one and repoints the policy first.
  lifecycle {
    create_before_destroy = true
  }
}

# The stack's one notification policy tree: everything goes to email. Owning it here means
# changes made in the UI are overwritten on the next apply.
resource "grafana_notification_policy" "root" {
  contact_point   = grafana_contact_point.email.name
  group_by        = ["alertname", "environment", "zeitwide_source"]
  group_wait      = "1m"
  group_interval  = "10m"
  repeat_interval = "12h"
}
