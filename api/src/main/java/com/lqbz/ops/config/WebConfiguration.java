package com.lqbz.ops.config;

import com.lqbz.ops.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;

  public WebConfiguration(AuthInterceptor authInterceptor) { this.authInterceptor = authInterceptor; }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login", "/api/auth/status");
  }
}
