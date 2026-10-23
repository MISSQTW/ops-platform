package com.lqbz.ops.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = { "ops.inspection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.datasource.url=jdbc:h2:mem:inspection_profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InspectionProfileApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AssetRepository assets;

    @Autowired
    ObjectMapper json;

    private long asset() {
        return assets.save(new Asset("深度Redis", "巡检测试", "测试", "REDIS", "localhost:6379", "UNKNOWN")).getId();
    }

    private String path(long id) {
        return "/api/assets/" + id + "/inspection-profile";
    }

    @Test
    void 配置只读不泄露凭据且未知选项不能绕过脱敏() throws Exception {
        long id = asset();
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{\"username\":\"monitor\",\"timeoutMillis\":\"1000\"},\"secrets\":{\"password\":\"secret-value\"}}\n")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.configuredSecrets[0]").value("password")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-value"))));
        mvc.perform(get(path(id))).andExpect(status().isOk()).andExpect(jsonPath("$.options.username").value("monitor")).andExpect(jsonPath("$.secrets").doesNotExist());
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{\"password\":\"bypass\"}}")).andExpect(status().isBadRequest());
    }

    @Test
    void 未提交密码时保留且显式空串清除() throws Exception {
        long id = asset();
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"secrets\":{\"password\":\"saved\"}}")).andExpect(status().isOk());
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.configuredSecrets[0]").value("password"));
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"secrets\":{\"password\":\"\"}}")).andExpect(status().isOk()).andExpect(jsonPath("$.configuredSecrets").isEmpty());
    }

    @Test
    void 超时和未知服务拒绝且未授权内部请求不执行探测() throws Exception {
        long id = asset();
        mvc.perform(put(path(id)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{\"timeoutMillis\":\"0\"}}")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/inspection/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get(path(9999999))).andExpect(status().isNotFound());
    }

    @Autowired
    InspectionProfileService profiles;

    @Autowired
    InspectionProfileRepository profileRows;

    @Autowired
    com.lqbz.ops.domain.InspectionPlanRepository plans;

    @Autowired
    com.lqbz.ops.domain.InspectionPlanBindingRepository bindings;

    @Test
    void 配置密文不可读且旧结果在配置和地址变化后丢弃() throws Exception {
        long id = asset();
        profiles.save(id, new InspectionProfileService.Update(true, com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map("password", "sensitive-password")));
        assertThat(profileRows.findById(id).orElseThrow(java.util.NoSuchElementException::new).getEncryptedSettings()).doesNotContain("sensitive-password");
        lombok.val snapshot = profiles.snapshot(id, false);
        lombok.val passed = com.lqbz.ops.compat.Java8.list(new ProbeCheck("REDIS", "PING", "测试", true, "PONG", 1));
        assertThat(profiles.finish(snapshot, passed, false, false)).isTrue();
        assertThat(profiles.view(id).checks()).hasSize(1);
        profiles.save(id, new InspectionProfileService.Update(true, null, com.lqbz.ops.compat.Java8.map("password", "new-password")));
        assertThat(profiles.finish(snapshot, passed, false, false)).isFalse();
        assertThat(profiles.view(id).checks()).isEmpty();
        lombok.val updated = profiles.snapshot(id, false);
        Asset asset = assets.findById(id).orElseThrow(java.util.NoSuchElementException::new);
        asset.update(asset.getName(), asset.getProjectName(), asset.getEnvironmentName(), "REDIS", "localhost:6380", "UNKNOWN");
        assets.save(asset);
        assertThat(profiles.finish(updated, passed, false, false)).isFalse();
    }

    @Test
    void 计划停启代次变化与归属转移后旧执行不能提交() {
        long id = asset();
        profiles.save(id, new InspectionProfileService.Update(true, com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()));
        com.lqbz.ops.domain.InspectionPlan plan = plans.save(com.lqbz.ops.domain.InspectionPlan.monitoring("版本测试", 1L, "巡检测试", 3600, 2, "WARNING"));
        lombok.val binding = bindings.save(com.lqbz.ops.domain.InspectionPlanBinding.managed(plan.getId(), id, plan.getDesiredVersion()));
        lombok.val snapshot = profiles.snapshot(id, true);
        plan.reconfigure(plan.getName(), 3600, 2, "WARNING", "DISABLED");
        plan = plans.save(plan);
        plan.reconfigure(plan.getName(), 3600, 2, "WARNING", "ENABLED");
        plans.save(plan);
        assertThat(profiles.finish(snapshot, com.lqbz.ops.compat.Java8.list(new ProbeCheck("REDIS", "PING", "测试", true, "PONG", 1)), false, true)).isFalse();
    }

    @Test
    void 内部凭据绑定配置版本且无启用计划不得返回成功() throws Exception {
        long id = asset();
        profiles.save(id, new InspectionProfileService.Update(true, com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()));
        lombok.val bridge = profiles.bridge(assets.findById(id).orElseThrow(java.util.NoSuchElementException::new));
        String auth = "Basic " + java.util.Base64.getEncoder().encodeToString((bridge.username() + ":" + bridge.password()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/internal/inspection/" + id).header("Authorization", auth)).andExpect(status().isServiceUnavailable());
        profiles.save(id, new InspectionProfileService.Update(true, null, com.lqbz.ops.compat.Java8.map()));
        mvc.perform(get("/internal/inspection/" + id).header("Authorization", auth)).andExpect(status().isUnauthorized());
    }

    @Test
    void 手动测试有启用计划的LOCAL服务只更新明细() {
        long id = asset();
        profiles.save(id, new InspectionProfileService.Update(true, com.lqbz.ops.compat.Java8.map(), com.lqbz.ops.compat.Java8.map()));
        lombok.val plan = plans.save(com.lqbz.ops.domain.InspectionPlan.monitoring("手测隔离", 1L, "巡检测试", 3600, 2, "WARNING"));
        bindings.save(com.lqbz.ops.domain.InspectionPlanBinding.managed(plan.getId(), id, plan.getDesiredVersion()));
        lombok.val checks = com.lqbz.ops.compat.Java8.list(new ProbeCheck("REDIS", "PING", "测试", false, "连接失败", 1));
        assertThat(profiles.finish(profiles.snapshot(id, false), checks, false, false)).isTrue();
        assertThat(assets.findById(id).orElseThrow(java.util.NoSuchElementException::new).getStatus()).isEqualTo("UNKNOWN");
        assertThat(profiles.view(id).checks()).hasSize(1);
        assertThat(profiles.finish(profiles.snapshot(id, true), checks, false, true)).isTrue();
        assertThat(assets.findById(id).orElseThrow(java.util.NoSuchElementException::new).getStatus()).isEqualTo("CRITICAL");
    }
}
