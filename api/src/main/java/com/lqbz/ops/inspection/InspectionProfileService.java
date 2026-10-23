package com.lqbz.ops.inspection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.*;
import com.lqbz.ops.service.MonitoringTopologyLock;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionProfileService {

    private static final Set<String> OPTIONS = com.lqbz.ops.compat.Java8.set("timeoutMillis", "username", "hostKey", "diskThreshold", "mountPoints", "excludedMountPoints", "database", "tls", "httpMethod", "expectedStatus", "responseContains", "bucket", "vhost", "securityProtocol", "saslMechanism", "osType", "winrmAuth", "domain", "driveLetters", "excludedDriveLetters");

    private static final Set<String> SECRETS = com.lqbz.ops.compat.Java8.set("password", "privateKey", "privateKeyPassphrase", "accessKey", "secretKey", "headers", "body");

    private final AssetRepository assets;

    private final InspectionProfileRepository profiles;

    private final InspectionPlanBindingRepository bindings;

    private final InspectionPlanRepository plans;

    private final InspectionRepository inspections;

    private final InspectionItemRepository items;

    private final MonitoringTopologyLock topology;

    private final SecretCipher cipher;

    private final ObjectMapper json;

    private final String bridgeBaseUrl;

    public InspectionProfileService(AssetRepository assets, InspectionProfileRepository profiles, InspectionPlanBindingRepository bindings, InspectionPlanRepository plans, InspectionRepository inspections, InspectionItemRepository items, MonitoringTopologyLock topology, SecretCipher cipher, ObjectMapper json, @Value("${ops.inspection.bridge-base-url:http://api:8080}") String bridgeBaseUrl) {
        this.assets = assets;
        this.profiles = profiles;
        this.bindings = bindings;
        this.plans = plans;
        this.inspections = inspections;
        this.items = items;
        this.topology = topology;
        this.cipher = cipher;
        this.json = json;
        this.bridgeBaseUrl = bridgeBaseUrl.replaceAll("/+$", "");
    }

    private Asset asset(long id) {
        return assets.findById(id).orElseThrow(() -> new NoSuchElementException("服务不存在"));
    }

    @Transactional(readOnly = true)
    public View view(long id) {
        Asset asset = asset(id);
        InspectionProfile profile = profiles.findById(id).orElse(null);
        if (profile == null)
            return new View(id, false, 0, com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.list(), com.lqbz.ops.compat.Java8.list(), null, cipher.available());
        ProbeSettings settings = settings(profile);
        boolean current = Objects.equals(asset.getType(), profile.getServiceType()) && Objects.equals(asset.getEndpoint(), profile.getCheckedEndpoint());
        return new View(id, profile.isEnabled(), profile.getRevision(), settings.options(), settings.secrets().keySet().stream().sorted().collect(java.util.stream.Collectors.toList()), current ? checks(profile) : com.lqbz.ops.compat.Java8.list(), current ? profile.getCheckedAt() : null, cipher.available());
    }

    @Transactional
    public View save(long id, Update request) {
        topology.acquireServices(com.lqbz.ops.compat.Java8.list(id));
        Asset asset = asset(id);
        InspectionProfile profile = profiles.findById(id).orElseGet(() -> new InspectionProfile(id));
        ProbeSettings old = profile.getEncryptedSettings() == null ? new ProbeSettings(com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()) : settings(profile);
        Map<String, String> options = request.options() == null ? old.options() : request.options();
        Map<String, String> secrets = new HashMap<>(old.secrets());
        if (request.secrets() != null)
            request.secrets().forEach((key, value) -> {
                if (value == null)
                    throw new IllegalArgumentException("敏感字段不能为null");
                if (value.isEmpty())
                    secrets.remove(key);
                else
                    secrets.put(key, value);
            });
        validate(asset, new ProbeSettings(options, secrets), request.enabled());
        profile.configure(request.enabled(), asset.getType(), cipher.encrypt(write(new ProbeSettings(options, secrets))));
        profiles.saveAndFlush(profile);
        return view(id);
    }

    @Transactional(readOnly = true)
    public boolean enabled(long id) {
        return profiles.findById(id).map(InspectionProfile::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(long id, boolean requirePlan) {
        Asset asset = asset(id);
        InspectionProfile profile = profiles.findById(id).filter(InspectionProfile::isEnabled).orElseThrow(() -> new IllegalStateException("请先启用深度巡检配置"));
        if (!asset.getType().equals(profile.getServiceType()))
            throw new IllegalStateException("服务类型已变化，请重新保存配置");
        InspectionPlanBinding binding = bindings.findByActiveOwnerKey(id).orElse(null);
        InspectionPlan plan = binding == null ? null : plans.findById(binding.getPlanId()).orElse(null);
        if (plan != null && (!"ENABLED".equals(plan.getStatus()) || !"ACTIVE".equals(plan.getLifecycleStatus()) || !"OWNED".equals(binding.getOwnershipStatus()) || !"ENABLED".equals(binding.getMonitorDesiredState()))) {
            plan = null;
            binding = null;
        }
        if (requirePlan && plan == null)
            throw new IllegalStateException("服务没有启用的巡检计划");
        return new Snapshot(asset, profile.getRevision(), settings(profile), plan == null ? null : plan.getId(), binding == null ? null : binding.getId(), plan == null ? null : plan.getDesiredVersion());
    }

    @Transactional
    public boolean finish(Snapshot snapshot, List<ProbeCheck> checks, boolean recordInspection, boolean updateStatus) {
        // 与计划配置操作保持同样的锁顺序：先计划，再服务。
        InspectionPlan plan = snapshot.planId() == null ? null : plans.findByIdForUpdate(snapshot.planId()).orElse(null);
        topology.acquireServices(com.lqbz.ops.compat.Java8.list(snapshot.asset().getId()));
        Asset current = asset(snapshot.asset().getId());
        InspectionProfile profile = profiles.findById(current.getId()).orElse(null);
        if (profile == null || !profile.isEnabled() || profile.getRevision() != snapshot.revision() || !current.getType().equals(snapshot.asset().getType()) || !current.getEndpoint().equals(snapshot.asset().getEndpoint()))
            return false;
        InspectionPlanBinding owner = bindings.findByActiveOwnerKey(current.getId()).orElse(null);
        if (snapshot.planId() != null) {
            if (plan == null || !"ENABLED".equals(plan.getStatus()) || !"ACTIVE".equals(plan.getLifecycleStatus()) || !Objects.equals(plan.getDesiredVersion(), snapshot.planVersion()) || owner == null || !Objects.equals(owner.getId(), snapshot.bindingId()) || !"OWNED".equals(owner.getOwnershipStatus()) || !"ENABLED".equals(owner.getMonitorDesiredState()))
                return false;
        } else if (owner != null)
            return false;
        profile.record(write(checks), current.getEndpoint());
        if (updateStatus && snapshot.planId() != null)
            current.applyInspectionStatus(checks.stream().allMatch(ProbeCheck::passed) ? "HEALTHY" : "CRITICAL");
        if (recordInspection && plan != null) {
            int passed = (int) checks.stream().filter(ProbeCheck::passed).count();
            Inspection inspection = inspections.save(new Inspection(plan.getName(), current.getProjectName(), passed == checks.size() ? "PASSED" : "FAILED", passed, checks.size(), Math.max(1, checks.stream().mapToLong(ProbeCheck::durationMillis).sum() / 1000), LocalDateTime.now()));
            for (ProbeCheck check : checks) items.save(new InspectionItem(inspection.getId(), check.category(), check.target(), check.name(), check.passed() ? "PASSED" : "FAILED", check.message(), check.durationMillis()));
        }
        return true;
    }

    @Transactional(readOnly = true)
    public Bridge bridge(Asset asset) {
        InspectionProfile profile = profiles.findById(asset.getId()).filter(InspectionProfile::isEnabled).orElse(null);
        if (profile == null)
            return null;
        String password = cipher.credential(asset.getId(), profile.getRevision());
        return new Bridge(URI.create(bridgeBaseUrl + "/internal/inspection/" + asset.getId()), "inspection-" + asset.getId() + "-r" + profile.getRevision(), password);
    }

    @Transactional(readOnly = true)
    public boolean authorized(long id, String authorization) {
        return authorizedRevision(id, authorization) >= 0;
    }

    @Transactional(readOnly = true)
    public long authorizedRevision(long id, String authorization) {
        InspectionProfile profile = profiles.findById(id).filter(InspectionProfile::isEnabled).orElse(null);
        if (profile == null || !cipher.available() || authorization == null)
            return -1;
        String raw = "inspection-" + id + "-r" + profile.getRevision() + ":" + cipher.credential(id, profile.getRevision());
        byte[] expected = ("Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8)) ? profile.getRevision() : -1;
    }

    private ProbeSettings settings(InspectionProfile profile) {
        try {
            return json.readValue(cipher.decrypt(profile.getEncryptedSettings()), ProbeSettings.class);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("巡检配置读取失败");
        }
    }

    private List<ProbeCheck> checks(InspectionProfile profile) {
        try {
            return profile.getChecksJson() == null ? com.lqbz.ops.compat.Java8.list() : json.readValue(profile.getChecksJson(), new TypeReference<List<ProbeCheck>>() {
            });
        } catch (Exception exception) {
            return com.lqbz.ops.compat.Java8.list();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("巡检配置序列化失败");
        }
    }

    private void validate(Asset asset, ProbeSettings settings, boolean enabled) {
        if (!OPTIONS.containsAll(settings.options().keySet()) || !SECRETS.containsAll(settings.secrets().keySet()))
            throw new IllegalArgumentException("巡检配置含未知字段，敏感信息必须放入敏感字段");
        if (settings.options().values().stream().anyMatch(v -> v == null || v.length() > 4096) || settings.secrets().values().stream().anyMatch(v -> v.length() > 65536))
            throw new IllegalArgumentException("巡检配置字段过长");
        try {
            if (settings.timeoutMillis() < 1000 || settings.timeoutMillis() > 10000)
                throw new IllegalArgumentException();
            int threshold = settings.number("diskThreshold", 80);
            if (threshold < 1 || threshold > 100)
                throw new IllegalArgumentException();
            int code = settings.number("expectedStatus", 200);
            if (code < 100 || code > 599)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("超时须为1000至10000毫秒，磁盘阈值须为1至100，状态码须为100至599");
        }
        MonitoringServiceContract.validate(asset.getType(), asset.getEndpoint());
        if ("SERVER".equals(asset.getType())) {
            String os = settings.text("osType", "LINUX");
            if (!"LINUX".equals(os) && !"WINDOWS".equals(os)) throw new IllegalArgumentException("服务器系统必须为 LINUX 或 WINDOWS");
            if ("WINDOWS".equals(os)) com.lqbz.ops.inspection.probes.WindowsProtocolProbe.validateSettings(asset.getEndpoint(), settings, enabled);
        }
        if (!enabled)
            return;
        switch(asset.getType()) {
            case "SERVER":
                {
                    if ("WINDOWS".equals(settings.text("osType", "LINUX"))) break;
                    required(settings.text("username", ""), "SSH用户名");
                    required(settings.text("hostKey", ""), "SSH主机SHA256指纹");
                    if (!settings.text("hostKey", "").matches("SHA256:[A-Za-z0-9+/]{43}=?"))
                        throw new IllegalArgumentException("SSH主机指纹格式应为SHA256:后接Base64公钥摘要");
                    if (com.lqbz.ops.compat.Java8.isBlank(settings.secret("password")) && com.lqbz.ops.compat.Java8.isBlank(settings.secret("privateKey")))
                        throw new IllegalArgumentException("请配置SSH密码或私钥");
                }
                break;
            case "MYSQL":
            case "POSTGRESQL":
                {
                    required(settings.text("username", ""), "数据库用户名");
                    required(settings.text("database", ""), "数据库名");
                }
                break;
            case "MINIO":
                {
                    required(settings.text("bucket", ""), "MinIO桶名称");
                    required(settings.secret("accessKey"), "MinIO AccessKey");
                    required(settings.secret("secretKey"), "MinIO SecretKey");
                }
                break;
            case "HTTP":
                {
                    if (!com.lqbz.ops.compat.Java8.set("GET", "HEAD", "POST", "PUT").contains(settings.text("httpMethod", "GET")))
                        throw new IllegalArgumentException("不支持的HTTP方法");
                    if (!com.lqbz.ops.compat.Java8.isBlank(settings.secret("headers"))) {
                        try {
                            Map<String, String> headers = json.readValue(settings.secret("headers"), new TypeReference<Map<String, String>>() {
                            });
                            if (headers == null || headers.size() > 32 || headers.entrySet().stream().anyMatch(e -> e.getValue() == null || e.getKey().contains("\n") || e.getValue().contains("\n") || e.getValue().contains("\r")))
                                throw new IllegalArgumentException();
                        } catch (Exception exception) {
                            throw new IllegalArgumentException("HTTP请求头必须是有效的JSON字符串映射");
                        }
                    }
                }
                break;
            default:
                {
                }
                break;
        }
    }

    private void required(String value, String field) {
        if (value == null || com.lqbz.ops.compat.Java8.isBlank(value))
            throw new IllegalArgumentException(field + "不能为空");
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class Update {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public Update(@com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled, @com.fasterxml.jackson.annotation.JsonProperty("options") Map<String, String> options, @com.fasterxml.jackson.annotation.JsonProperty("secrets") Map<String, String> secrets) {
            this.enabled = enabled;
            this.options = options;
            this.secrets = secrets;
        }

        private final boolean enabled;

        public boolean enabled() {
            return this.enabled;
        }

        private final Map<String, String> options;

        public Map<String, String> options() {
            return this.options;
        }

        private final Map<String, String> secrets;

        public Map<String, String> secrets() {
            return this.secrets;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class View {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public View(@com.fasterxml.jackson.annotation.JsonProperty("assetId") long assetId, @com.fasterxml.jackson.annotation.JsonProperty("enabled") boolean enabled, @com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("options") Map<String, String> options, @com.fasterxml.jackson.annotation.JsonProperty("configuredSecrets") List<String> configuredSecrets, @com.fasterxml.jackson.annotation.JsonProperty("checks") List<ProbeCheck> checks, @com.fasterxml.jackson.annotation.JsonProperty("checkedAt") LocalDateTime checkedAt, @com.fasterxml.jackson.annotation.JsonProperty("keyConfigured") boolean keyConfigured) {
            this.assetId = assetId;
            this.enabled = enabled;
            this.revision = revision;
            this.options = options;
            this.configuredSecrets = configuredSecrets;
            this.checks = checks;
            this.checkedAt = checkedAt;
            this.keyConfigured = keyConfigured;
        }

        private final long assetId;

        public long assetId() {
            return this.assetId;
        }

        private final boolean enabled;

        public boolean enabled() {
            return this.enabled;
        }

        private final long revision;

        public long revision() {
            return this.revision;
        }

        private final Map<String, String> options;

        public Map<String, String> options() {
            return this.options;
        }

        private final List<String> configuredSecrets;

        public List<String> configuredSecrets() {
            return this.configuredSecrets;
        }

        private final List<ProbeCheck> checks;

        public List<ProbeCheck> checks() {
            return this.checks;
        }

        private final LocalDateTime checkedAt;

        public LocalDateTime checkedAt() {
            return this.checkedAt;
        }

        private final boolean keyConfigured;

        public boolean keyConfigured() {
            return this.keyConfigured;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class Snapshot {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public Snapshot(@com.fasterxml.jackson.annotation.JsonProperty("asset") Asset asset, @com.fasterxml.jackson.annotation.JsonProperty("revision") long revision, @com.fasterxml.jackson.annotation.JsonProperty("settings") ProbeSettings settings, @com.fasterxml.jackson.annotation.JsonProperty("planId") Long planId, @com.fasterxml.jackson.annotation.JsonProperty("bindingId") Long bindingId, @com.fasterxml.jackson.annotation.JsonProperty("planVersion") Long planVersion) {
            this.asset = asset;
            this.revision = revision;
            this.settings = settings;
            this.planId = planId;
            this.bindingId = bindingId;
            this.planVersion = planVersion;
        }

        private final Asset asset;

        public Asset asset() {
            return this.asset;
        }

        private final long revision;

        public long revision() {
            return this.revision;
        }

        private final ProbeSettings settings;

        public ProbeSettings settings() {
            return this.settings;
        }

        private final Long planId;

        public Long planId() {
            return this.planId;
        }

        private final Long bindingId;

        public Long bindingId() {
            return this.bindingId;
        }

        private final Long planVersion;

        public Long planVersion() {
            return this.planVersion;
        }
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class Bridge {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public Bridge(@com.fasterxml.jackson.annotation.JsonProperty("uri") URI uri, @com.fasterxml.jackson.annotation.JsonProperty("username") String username, @com.fasterxml.jackson.annotation.JsonProperty("password") String password) {
            this.uri = uri;
            this.username = username;
            this.password = password;
        }

        private final URI uri;

        public URI uri() {
            return this.uri;
        }

        private final String username;

        public String username() {
            return this.username;
        }

        private final String password;

        public String password() {
            return this.password;
        }
    }
}
