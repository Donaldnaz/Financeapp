resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.name}"
  retention_in_days = 30

  tags = var.tags
}

resource "aws_ecs_cluster" "this" {
  name = "${var.name}-cluster"

  tags = var.tags
}

module "iam" {
  source = "../iam"

  name                       = var.name
  execution_role_policy_arns = var.execution_role_policy_arns
  task_role_policy_arns      = var.task_role_policy_arns
  tags                       = var.tags
}

module "load_balancer" {
  source = "../load_balancer"

  name              = var.name
  vpc_id            = var.vpc_id
  public_subnet_ids = var.public_subnet_ids
  alb_sg_id         = var.alb_sg_id
  container_port    = var.container_port
  health_check_path = var.health_check_path
  tags              = var.tags
}

# Preserve existing state addresses when IAM/ALB were inlined in this module.
moved {
  from = aws_iam_role.execution
  to   = module.iam.aws_iam_role.execution
}

moved {
  from = aws_iam_role.task
  to   = module.iam.aws_iam_role.task
}

moved {
  from = aws_lb.this
  to   = module.load_balancer.aws_lb.this
}

moved {
  from = aws_lb_target_group.app
  to   = module.load_balancer.aws_lb_target_group.app
}

moved {
  from = aws_lb_listener.http
  to   = module.load_balancer.aws_lb_listener.http
}

resource "aws_ecs_task_definition" "this" {
  family                   = "${var.name}-task"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = module.iam.execution_role_arn
  task_role_arn            = module.iam.task_role_arn

  container_definitions = jsonencode([
    {
      name      = "${var.name}-container"
      image     = var.container_image
      essential = true
      portMappings = [
        {
          containerPort = var.container_port
          hostPort      = var.container_port
          protocol      = "tcp"
        }
      ]
      environment = [for key, value in var.environment : { name = key, value = value }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = var.region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])

  tags = var.tags
}

resource "aws_ecs_service" "this" {
  name            = "${var.name}-service"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    assign_public_ip = false
    security_groups  = [var.ecs_sg_id]
    subnets          = var.private_subnet_ids
  }

  load_balancer {
    target_group_arn = module.load_balancer.target_group_arn
    container_name   = "${var.name}-container"
    container_port   = var.container_port
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  depends_on = [module.load_balancer]

  tags = var.tags
}
