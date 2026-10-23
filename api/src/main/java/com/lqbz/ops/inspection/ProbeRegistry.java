package com.lqbz.ops.inspection;

import com.lqbz.ops.domain.Asset;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProbeRegistry {

    private final List<ProtocolProbe> probes;

    public ProbeRegistry(List<ProtocolProbe> probes) {
        this.probes = probes;
    }

    public List<ProbeCheck> check(Asset asset, ProbeSettings settings) {
        try {
            ProtocolProbe probe = probes.stream().filter(p -> p.supports(asset.getType())).findFirst().orElseThrow(() -> new IllegalStateException("服务类型不支持深度检查"));
            List<ProbeCheck> checks = probe.check(asset, settings);
            if (checks == null || checks.isEmpty())
                return failure(asset, "协议检查未返回有效结果");
            return checks;
        } catch (Exception exception) {
            // 第三方异常可能携带密码、URL或请求内容，禁止直接暴露。
            return failure(asset, "协议检查失败，请检查认证、连接配置和服务权限");
        }
    }

    public static List<ProbeCheck> failure(Asset asset, String message) {
        return com.lqbz.ops.compat.Java8.list(new ProbeCheck(asset.getType(), "深度协议检查", asset.getName(), false, message, 0));
    }
}
