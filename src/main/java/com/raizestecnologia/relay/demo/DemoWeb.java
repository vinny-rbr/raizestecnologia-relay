package com.raizestecnologia.relay.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * App web de demonstração em /demo/ (build Flutter web em static/demo, mesmo endereço da API,
 * então o navegador não precisa de CORS). /demo e /demo/ abrem o index.html.
 */
@Configuration
public class DemoWeb implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/demo", "/demo/");
        registry.addViewController("/demo/").setViewName("forward:/demo/index.html");
    }
}
