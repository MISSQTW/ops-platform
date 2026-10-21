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

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.lqbz.ops.inspection.InspectionProfileService inspectionProfiles;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.lqbz.ops.inspection.ProbeRunner probeRunner;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  private final java.util.concurrent.ExecutorService checkExecutor = java.util.concurrent.Executors.newFixedThreadPool(16, Thread.ofVirtual().name("inspection-check-", 0).factory());
  private final java.util.concurrent.ExecutorService planExecutor = java.util.concurrent.Executors.newFixedThreadPool(4, Thread.ofVirtual().name("inspection-plan-", 0).factory());
  private final java.util.Set<Long> executingPlans = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.Semaphore planCapacity = new java.util.concurrent.Semaphore(4);
  @org.springframework.beans.factory.annotation.Value("${ops.inspection.async-scheduling:true}")
  private boolean asynchronousScheduling;

  @jakarta.annotation.PreDestroy
  public void closeExecutors() { checkExecutor.shutdownNow(); planExecutor.shutdownNow(); }

  public Inspection execute(Long planId) {
    InspectionPlan plan = plans.findById(planId)
        .orElseThrow(() -> new NoSuchElementException("巡检计划不存在"));
    if ("DELETED".equals(plan.getLifecycleStatus())) throw new NoSuchElementException("巡检计划不存在");
    if (!"ENABLED".equals(plan.getStatus())) throw new IllegalStateException("请先启用巡检计划");
    Inspection inspection = execute(plan, false);
    if (inspection == null) {
      if (assets.findByProjectNameOrderById(plan.getProjectName()).isEmpty()) {
        throw new IllegalStateException("计划所属项目没有可执行服务，请先添加服务");
      }
      throw new IllegalStateException("计划内没有可执行服务，请先配置巡检服务");
    }
    return inspection;
  }

  @Scheduled(initialDelayString = "${ops.inspection.scheduler-initial-delay-ms:10000}",
      fixedDelayString = "${ops.inspection.scheduler-delay-ms:10000}")
  public void executeDuePlans() {
    LocalDateTime now = LocalDateTime.now();
    for (InspectionPlan plan : plans.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAt("ENABLED", now)) {
      if (!asynchronousScheduling) { execute(plan, true); continue; }
      if (!executingPlans.add(plan.getId())) continue;
      if (!planCapacity.tryAcquire()) { executingPlans.remove(plan.getId()); continue; }
      planExecutor.submit(() -> {
        try { execute(plan, true); }
        catch (RuntimeException ignored) {
          // 单个计划发生配置竞争或数据库异常时，下轮重试，不中断其他计划。
        } finally { executingPlans.remove(plan.getId()); planCapacity.release(); }
      });
    }
  }

  private Inspection execute(InspectionPlan plan, boolean scheduled) {
    long startedAt = System.nanoTime();
    List<Asset> projectAssets = executionAssets(plan, scheduled);
    if (projectAssets.isEmpty()) {
      if (scheduled) { plan.scheduleNextRun(calculateNextRun(plan.getScheduleText(), LocalDateTime.now())); plans.save(plan); }
      return null;
    }
    List<java.util.concurrent.CompletableFuture<AssetChecks>> pending = projectAssets.stream().map(asset ->
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
          boolean deep = inspectionProfiles != null && inspectionProfiles.enabled(asset.getId());
          try {
            if (deep) return new AssetChecks(asset, true, probeRunner.run(asset.getId(), true, false));
            AddressTestResult result = testAddress(asset);
            return new AssetChecks(asset, false, List.of(new com.lqbz.ops.inspection.ProbeCheck(asset.getType(),
                checkName(asset), asset.getName(), result.passed(), result.message(), result.durationMillis())));
          } catch (RuntimeException exception) {
            return new AssetChecks(asset, deep, com.lqbz.ops.inspection.ProbeRegistry.failure(asset, "检查配置或计划归属已变化，本项未通过"));
          }
        }, checkExecutor)).toList();
    List<AssetChecks> grouped = pending.stream().map(java.util.concurrent.CompletableFuture::join).toList();
    List<com.lqbz.ops.inspection.ProbeCheck> results = grouped.stream().flatMap(group -> group.checks().stream()).toList();
    java.util.function.Supplier<Inspection> persist = () -> {
      int passedChecks = (int) results.stream().filter(com.lqbz.ops.inspection.ProbeCheck::passed).count();
      String status = !results.isEmpty() && passedChecks == results.size() ? "PASSED" : "FAILED";
      long durationSeconds = Math.max(1, Duration.ofNanos(System.nanoTime() - startedAt).toSeconds());
      Inspection inspection = inspections.save(new Inspection(plan.getName(), plan.getProjectName(), status,
          passedChecks, results.size(), durationSeconds, LocalDateTime.now()));
      for (com.lqbz.ops.inspection.ProbeCheck result : results) {
        inspectionItems.save(new InspectionItem(inspection.getId(), result.category(), result.target(), result.name(),
            result.passed() ? "PASSED" : "FAILED", result.message(), result.durationMillis()));
      }
      for (AssetChecks group : grouped) {
        // 用服务ID和开始执行时的检查模式关联结果，名称和配置变更不能改变身份。
        if (group.deep()) continue;
        Asset original = group.asset();
        Asset current = assets.findById(original.getId()).orElse(original);
        if (!current.getType().equals(original.getType()) || !current.getEndpoint().equals(original.getEndpoint())) continue;
        boolean passed = !group.checks().isEmpty() && group.checks().stream().allMatch(com.lqbz.ops.inspection.ProbeCheck::passed);
        current.applyInspectionStatus(passed ? "HEALTHY" : "CRITICAL");
        assets.save(current);
      }
      if (scheduled) {
        InspectionPlan current = plans.findById(plan.getId()).orElse(plan);
        current.scheduleNextRun(calculateNextRun(current.getScheduleText(), LocalDateTime.now()));
        plans.save(current);
      }
      return inspection;
    };
    return transactionTemplate == null ? persist.get() : transactionTemplate.execute(status -> persist.get());
  }

  private List<Asset> executionAssets(InspectionPlan plan, boolean scheduled) {
    List<Asset> projectAssets = assets.findByProjectNameOrderById(plan.getProjectName());
    Map<Long, InspectionPlanBinding> planBindings = bindings.findByPlanIdOrderById(plan.getId()).stream()
        .filter(binding -> !"RELEASED".equals(binding.getOwnershipStatus()))
        .collect(Collectors.toMap(InspectionPlanBinding::getAssetId, Function.identity(), (left, right) -> right));
    return projectAssets.stream()
        .filter(asset -> {
          InspectionPlanBinding binding = planBindings.get(asset.getId());
          return binding != null && "OWNED".equals(binding.getOwnershipStatus())
              && "ENABLED".equals(binding.getMonitorDesiredState())
              && (!scheduled || !binding.isHertzBeatReady(plan.getDesiredVersion()));
        })
        .toList();
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
      default -> throw new IllegalArgumentException("无法识别服务端口");
    };
  }

  private String checkName(Asset asset) {
    return "HTTP".equalsIgnoreCase(asset.getType()) ? "基础HTTP可用性" : asset.getType() + " 基础端口连接";
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

  private record AssetChecks(Asset asset, boolean deep, List<com.lqbz.ops.inspection.ProbeCheck> checks) {}

  public record AddressTestResult(boolean passed, String message, long durationMillis) {}
  private record HostPort(String host, int port) {}
}
