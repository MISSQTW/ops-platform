package com.lqbz.ops.inspection;

import com.lqbz.ops.domain.Asset;
import com.lqbz.ops.domain.AssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ops.inspection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.datasource.url=jdbc:h2:mem:windows_profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WindowsProfileApiTest {
  @Autowired MockMvc mvc;
  @Autowired AssetRepository assets;
  private String path(String endpoint) {
    long id=assets.save(new Asset("Windows主机","Windows验收","测试","SERVER",endpoint,"UNKNOWN")).getId();
    return "/api/assets/"+id+"/inspection-profile";
  }
  @Test void Windows配置无需SSH密钥且凭据加密读回不泄漏() throws Exception {
    String path=path("https://windows.test:5986/wsman");
    mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{\"osType\":\"WINDOWS\",\"username\":\"monitor\",\"driveLetters\":\"C:,D:\"},\"secrets\":{\"password\":\"private-value\"}}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.options.osType").value("WINDOWS"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-value"))));
    mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.configuredSecrets[0]").value("password"));
    mvc.perform(get(path)).andExpect(jsonPath("$.options.driveLetters").value("C:,D:"));
  }
  @Test void Windows拒绝明文传输缺失认证和非法盘符() throws Exception {
    String options="\"osType\":\"WINDOWS\",\"username\":\"monitor\"";
    mvc.perform(put(path("http://windows.test:5985/wsman")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{"+options+"},\"secrets\":{\"password\":\"x\"}}"))
        .andExpect(status().isBadRequest());
    String path=path("https://windows.test:5986/wsman");
    mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{"+options+"}}"))
        .andExpect(status().isBadRequest());
    mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{"+options+",\"driveLetters\":\"C:;whoami\"},\"secrets\":{\"password\":\"x\"}}"))
        .andExpect(status().isBadRequest());
  }
  @Test void 未选Windows仍要求原LinuxSSH指纹() throws Exception {
    mvc.perform(put(path("linux.test:22")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"options\":{\"username\":\"root\"},\"secrets\":{\"password\":\"x\"}}"))
        .andExpect(status().isBadRequest());
  }
}
