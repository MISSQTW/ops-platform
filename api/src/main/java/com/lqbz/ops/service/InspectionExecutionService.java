package com.lqbz.ops.service;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import com.lqbz.ops.domain.Inspection;
import com.lqbz.ops.domain.InspectionItem;
import com.lqbz.ops.domain.InspectionItemRepository;
import com.lqbz.ops.domain.InspectionPlan;
import com.lqbz.ops.domain.InspectionPlanBinding;
import com.lqbz.ops.domain.InspectionPlanBindingRepository;
import com.lqbz.ops.domain.InspectionPlanRepository;
import com.lqbz.ops.domain.InspectionRepository;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionExecutionService {
  private static final int CONNECT_TIMEOUT_MILLIS = 3000;
  private static final Pattern INTERVAL_PATTERN = Pattern.compile("每\\s*(\\d+)\\s*(秒|分钟|小时)");

  private final InspectionPlanRepository plans;
  private final AssetRepository assets;
  private final InspectionRepository inspections;
  private final InspectionItemRepository inspectionItems;
  private final InspectionPlanBindingRepository bindings;

  public InspectionExecutionService(InspectionPlanRepository plans, AssetRepository assets,
      InspectionRepository inspections, InspectionItemRepository inspectionItems,
      InspectionPlanBindingRepository bindings) {
    this.plans = plans;
    this.assets = assets;
    this.inspections = inspections;
    this.inspectionItems = inspectionItems;
    this.bindings = bindings;
  }

  @Transactional
  public Inspection execute(Long planId) {
    InspectionPlan plan = plans.findById(planId)
        .orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if ("PENDING_CONFIRMATION".equals(plan.getStatus())) throw new IllegalStateException("待确认迁移计划不能执行巡检");
    Inspection inspection = execute(plan, false);
    if (inspection == null) {
      if (assets.findByProjectNameOrderById(plan.getProjectName()).isEmpty()) {
        throw new IllegalStateException("计划所属项目没有可执行服务，请先添加服务");
      }
      if (legacyOwnsLocalExecution(plan) && bindings.findByPlanIdOrderById(plan.getId()).stream()
          .anyMatch(binding -> "OWNED".equals(binding.getOwnershipStatus())
              && !binding.isHertzBeatReady(plan.getDesiredVersion()))) {
        throw new IllegalStateException("服务仍由原有巡检计划执行，迁移同步完成后由 HertzBeat 接管");
      }
      throw new IllegalStateException("计划内服务已由 HertzBeat 执行周期巡检，请使用地址测试");
    }
    return inspection;
  }

  @Scheduled(initialDelayString = "${ops.inspection.scheduler-initial-delay-ms:10000}",
      fixedDelayString = "${ops.inspection.scheduler-delay-ms:10000}")
  @Transactional
  public void executeDuePlans() {
    LocalDateTime now = LocalDateTime.now();
    for (InspectionPlan plan : plans.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt("ENABLED", now)) {
      execute(plan, true);
    }
  }

  private Inspection execute(InspectionPlan plan, boolean scheduled) {
    long startedAt = System.nanoTime();
    List<Asset> projectAssets = localExecutionAssets(plan);
    if (projectAssets.isEmpty()) {
      if (scheduled) plan.scheduleNextRun(calculateNextRun(plan.getScheduleText(), LocalDateTime.now()));
      return null;
    }
    List<AddressTestResult> results = projectAssets.stream().map(this::testAddress).toList();
    int passedChecks = (int) results.stream().filter(AddressTestResult::passed).count();
    String status = !results.isEmpty() && passedChecks == results.size() ? "PASSED" : "FAILED";
    long durationSeconds = Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toSeconds());
    Inspection inspection = inspections.save(new Inspection(plan.getName(), plan.getProjectName(), status,
        passedChecks, results.size(), durationSeconds, LocalDateTime.now()));

    for (int index = 0; index < projectAssets.size(); index++) {
      Asset asset = projectAssets.get(index);
      AddressTestResult result = results.get(index);
      asset.applyInspectionStatus(result.passed() ? "HEALTHY" : "CRITICAL");
      inspectionItems.save(new InspectionItem(inspection.getId(), asset.getType(), asset.getName(),
          checkName(asset), result.passed() ? "PASSED" : "FAILED", result.message(), result.durationMillis()));
    }

    if (scheduled) plan.scheduleNextRun(calculateNextRun(plan.getScheduleText(), LocalDateTime.now()));
    return inspection;
  }

  private List<Asset> localExecutionAssets(InspectionPlan plan) {
    List<Asset> projectAssets = assets.findByProjectNameOrderById(plan.getProjectName());
    if (legacyOwnsLocalExecution(plan)) return List.of();
    if (!plan.isMonitoringPolicy()) {
      return projectAssets.stream().filter(asset -> !bindings.findByActiveOwnerKey(asset.getId())
          .flatMap(binding -> plans.findById(binding.getPlanId())
              .map(owner -> binding.isHertzBeatReady(owner.getDesiredVersion())))
          .orElse(false)).toList();
    }
    Map<Long, InspectionPlanBinding> planBindings = bindings.findByPlanIdOrderById(plan.getId()).stream()
        .filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus()))
        .collect(Collectors.toMap(InspectionPlanBinding::getAssetId, Function.identity(), (left, right) -> right));
    return projectAssets.stream()
        .filter(asset -> {
          InspectionPlanBinding binding = planBindings.get(asset.getId());
          return binding != null && !binding.isHertzBeatReady(plan.getDesiredVersion());
        })
        .toList();
  }

  private boolean legacyOwnsLocalExecution(InspectionPlan plan) {
    return plan.isMonitoringPolicy() && plans.findAll().stream()
        .anyMatch(legacy -> !legacy.isMonitoringPolicy() && "ENABLED".equals(legacy.getStatus())
            && plan.getProjectName().equals(legacy.getProjectName()));
  }

  public AddressTestResult testAddress(Asset asset) {
    long startedAt = System.nanoTime();
    try {
      String message = "HTTP".equalsIgnoreCase(asset.getType()) ? checkHttp(asset.getEndpoint()) : checkTcp(asset);
      return new AddressTestResult(true, message, elapsedMillis(startedAt));
    } catch (Exception exception) {
      String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      return new AddressTestResult(false, checkName(asset) + "失败：" + detail, elapsedMillis(startedAt));
    }
  }

  @Transactional(readOnly = true)
  public AddressTestResult testAddress(Long assetId) {
    Asset asset = assets.findById(assetId)
        .orElseThrow(() -> new NoSuchElementException("服务不存在"));
    return testAddress(asset);
  }

  private String checkHttp(String endpoint) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    connection.setReadTimeout(CONNECT_TIMEOUT_MILLIS);
    int statusCode = connection.getResponseCode();
    connection.disconnect();
    if (statusCode < 200 || statusCode >= 400) throw new IllegalStateException("HTTP " + statusCode);
    return "HTTP 健康接口返回 " + statusCode;
  }

  private String checkTcp(Asset asset) throws Exception {
    HostPort target = parseTarget(asset);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(target.host(), target.port()), CONNECT_TIMEOUT_MILLIS);
    }
    return asset.getType() + " 端口连接正常（" + target.host() + ":" + target.port() + "）";
  }

  private HostPort parseTarget(Asset asset) {
    String endpoint = asset.getEndpoint().replaceFirst("^[a-zA-Z]+://", "");
    int pathIndex = endpoint.indexOf('/');
    if (pathIndex >= 0) endpoint = endpoint.substring(0, pathIndex);
    int separator = endpoint.lastIndexOf(':');
    if (separator > 0) return new HostPort(endpoint.substring(0, separator), Integer.parseInt(endpoint.substring(separator + 1)));
    return new HostPort(endpoint, defaultPort(asset.getType()));
  }

  private int defaultPort(String type) {
    return switch (type.toUpperCase()) {
      case "SERVER" -> 22;
      case "MYSQL" -> 3306;
      case "POSTGRESQL" -> 5432;
      case "REDIS" -> 6379;
      case "KAFKA" -> 9092;
      case "RABBITMQ" -> 5672;
      case "ROCKETMQ" -> 9876;
      case "MINIO" -> 9000;
      default -> throw new IllegalArgumentException("无法识别资产端口");
    };
  }

  private String checkName(Asset asset) {
    return "HTTP".equalsIgnoreCase(asset.getType()) ? "健康接口" : asset.getType() + " 连接";
  }

  private long elapsedMillis(long startedAt) {
    return Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
  }

  LocalDateTime calculateNextRun(String scheduleText, LocalDateTime now) {
    Matcher interval = INTERVAL_PATTERN.matcher(scheduleText);
    if (interval.matches()) {
      long value = Long.parseLong(interval.group(1));
      return switch (interval.group(2)) {
        case "秒" -> now.plusSeconds(value);
        case "分钟" -> now.plusMinutes(value);
        default -> now.plusHours(value);
      };
    }
    if ("每小时整点".equals(scheduleText)) return now.plusHours(1).truncatedTo(ChronoUnit.HOURS);
    if (scheduleText.startsWith("每天 ")) {
      LocalTime time = LocalTime.parse(scheduleText.substring(3));
      LocalDateTime candidate = now.toLocalDate().atTime(time);
      return candidate.isAfter(now) ? candidate : candidate.plusDays(1);
    }
    if (scheduleText.startsWith("Cron: ")) {
      LocalDateTime next = CronExpression.parse(scheduleText.substring(6)).next(now);
      if (next != null) return next;
    }
    return now.plusHours(1);
  }

  public record AddressTestResult(boolean passed, String message, long durationMillis) {}
  private record HostPort(String host, int port) {}
}
