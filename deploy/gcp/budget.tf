# Alerts go to the billing account's admins and users by email. A budget only
# alerts; it never stops spending.
resource "google_billing_budget" "this" {
  billing_account = var.billing_account
  display_name    = local.prefix

  budget_filter {
    projects = ["projects/${data.google_project.this.number}"]
  }

  amount {
    specified_amount {
      currency_code = var.budget_currency
      units         = tostring(var.budget_amount)
    }
  }

  dynamic "threshold_rules" {
    for_each = [0.5, 0.9, 1.0]
    content {
      threshold_percent = threshold_rules.value
    }
  }

  depends_on = [google_project_service.this]
}
