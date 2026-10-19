package com.lqbz.ops.config;

import java.time.LocalDateTime;
import com.lqbz.ops.domain.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ops.demo-data.enabled", havingValue = "true")
public class DemoDataConfiguration {
  @Bean
  CommandLineRunner loadDemoData(ProjectRepository projects, AssetRepository assets,
      InspectionRepository inspections, AlertRepository alerts, InspectionPlanRepository plans,
      InspectionItemRepository inspectionItems) {
    return args -> {
      if (projects.count() == 0) {
        projects.save(new Project("统一支付平台", "张明", "支付、清结算与渠道路由", "RUNNING"));
        projects.save(new Project("客户运营中心", "李然", "会员、营销和客户画像", "RUNNING"));
        projects.save(new Project("数据交换平台", "王宁", "内部数据同步与消息分发", "MAINTENANCE"));
        assets.save(new Asset("pay-prod-app-01", "统一支付平台", "生产", "SERVER", "10.20.1.11", "HEALTHY"));
        assets.save(new Asset("pay-prod-mysql", "统一支付平台", "生产", "MYSQL", "10.20.2.15:3306", "HEALTHY"));
        assets.save(new Asset("pay-prod-redis", "统一支付平台", "生产", "REDIS", "10.20.2.18:6379", "WARNING"));
        assets.save(new Asset("customer-api", "客户运营中心", "生产", "HTTP", "https://customer.example/health", "HEALTHY"));
        assets.save(new Asset("exchange-kafka", "数据交换平台", "生产", "KAFKA", "10.20.3.20:9092", "CRITICAL"));
        assets.save(new Asset("archive-minio", "数据交换平台", "生产", "MINIO", "10.20.3.30:9000", "HEALTHY"));
        inspections.save(new Inspection("生产环境每日巡检", "统一支付平台", "PASSED", 18, 18, 42, LocalDateTime.now().minusHours(2)));
        inspections.save(new Inspection("中间件可用性巡检", "数据交换平台", "FAILED", 7, 9, 31, LocalDateTime.now().minusHours(1)));
        inspections.save(new Inspection("核心接口健康巡检", "客户运营中心", "PASSED", 12, 12, 18, LocalDateTime.now().minusMinutes(35)));
        alerts.save(new Alert("CRITICAL", "数据交换平台", "exchange-kafka", "Kafka 消费积压超过阈值", "OPEN", LocalDateTime.now().minusMinutes(22)));
        alerts.save(new Alert("WARNING", "统一支付平台", "pay-prod-redis", "Redis 内存使用率达到 82%", "OPEN", LocalDateTime.now().minusMinutes(48)));
        alerts.save(new Alert("INFO", "客户运营中心", "customer-api", "健康接口短暂超时，现已恢复", "RESOLVED", LocalDateTime.now().minusHours(3)));
      }

      if (plans.count() > 0) return;
      plans.save(new InspectionPlan("生产环境每日巡检", "统一支付平台", "服务器、磁盘、MySQL、Redis", "每天 09:00", "ENABLED", LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)));
      plans.save(new InspectionPlan("中间件可用性巡检", "数据交换平台", "Kafka、MinIO、消息积压", "每小时整点", "ENABLED", LocalDateTime.now().plusHours(1).withMinute(0)));
      plans.save(new InspectionPlan("核心接口健康巡检", "客户运营中心", "6 个 HTTP 健康接口", "每 5 分钟", "ENABLED", LocalDateTime.now().plusMinutes(5)));

      Inspection daily = inspections.findFirstByName("生产环境每日巡检").orElseThrow();
      Inspection middleware = inspections.findFirstByName("中间件可用性巡检").orElseThrow();
      Inspection api = inspections.findFirstByName("核心接口健康巡检").orElseThrow();
      inspectionItems.save(new InspectionItem(daily.getId(), "SERVER", "pay-prod-app-01", "SSH 连接", "PASSED", "服务器 SSH 连接正常", 38L));
      inspectionItems.save(new InspectionItem(daily.getId(), "DISK", "pay-prod-app-01:/data", "磁盘使用率", "PASSED", "/data 使用率 61%", 12L));
      inspectionItems.save(new InspectionItem(daily.getId(), "MYSQL", "pay-prod-mysql", "数据库连接", "PASSED", "MySQL 连接正常，耗时 21ms", 21L));
      inspectionItems.save(new InspectionItem(daily.getId(), "REDIS", "pay-prod-redis", "缓存连接", "PASSED", "Redis PING 响应正常", 8L));

      inspectionItems.save(new InspectionItem(middleware.getId(), "KAFKA", "exchange-kafka", "Broker 连接", "PASSED", "Kafka Broker 连接正常", 24L));
      inspectionItems.save(new InspectionItem(middleware.getId(), "KAFKA", "exchange-kafka", "消费积压", "WARNING", "消费积压 12,350，超过警告阈值 10,000", 16L));
      inspectionItems.save(new InspectionItem(middleware.getId(), "MINIO", "archive-minio", "服务健康", "PASSED", "MinIO 健康接口返回 200", 45L));
      inspectionItems.save(new InspectionItem(middleware.getId(), "REDIS", "exchange-redis", "缓存连接", "FAILED", "Redis 连接失败：连接超时", 3000L));

      inspectionItems.save(new InspectionItem(api.getId(), "HTTP", "customer-api", "健康接口", "PASSED", "接口返回 200，响应耗时 136ms", 136L));
    };
  }
}
