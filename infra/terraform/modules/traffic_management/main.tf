resource "aws_route53_health_check" "region_a" {
  fqdn              = var.region_a_alb_dns_name
  port              = 80
  type              = "HTTP"
  resource_path     = var.health_check_path
  failure_threshold = 3
  request_interval  = 30

  tags = merge(var.tags, {
    Name = "${var.name}-region-a-health"
  })
}

resource "aws_route53_health_check" "region_b" {
  fqdn              = var.region_b_alb_dns_name
  port              = 80
  type              = "HTTP"
  resource_path     = var.health_check_path
  failure_threshold = 3
  request_interval  = 30

  tags = merge(var.tags, {
    Name = "${var.name}-region-b-health"
  })
}

resource "aws_route53_record" "region_a" {
  zone_id = var.hosted_zone_id
  name    = var.record_name
  type    = "A"

  set_identifier = "${var.name}-region-a"

  dynamic "latency_routing_policy" {
    for_each = var.routing_policy == "latency" ? [1] : []
    content {
      region = var.region_a_aws_region
    }
  }

  dynamic "weighted_routing_policy" {
    for_each = var.routing_policy == "weighted" ? [1] : []
    content {
      weight = var.region_a_weight
    }
  }

  health_check_id = aws_route53_health_check.region_a.id

  alias {
    name                   = var.region_a_alb_dns_name
    zone_id                = var.region_a_alb_zone_id
    evaluate_target_health = true
  }
}

resource "aws_route53_record" "region_b" {
  zone_id = var.hosted_zone_id
  name    = var.record_name
  type    = "A"

  set_identifier = "${var.name}-region-b"

  dynamic "latency_routing_policy" {
    for_each = var.routing_policy == "latency" ? [1] : []
    content {
      region = var.region_b_aws_region
    }
  }

  dynamic "weighted_routing_policy" {
    for_each = var.routing_policy == "weighted" ? [1] : []
    content {
      weight = var.region_b_weight
    }
  }

  health_check_id = aws_route53_health_check.region_b.id

  alias {
    name                   = var.region_b_alb_dns_name
    zone_id                = var.region_b_alb_zone_id
    evaluate_target_health = true
  }
}
