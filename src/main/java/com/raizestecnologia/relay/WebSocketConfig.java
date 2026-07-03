package com.raizestecnologia.relay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Endpoint /agent — onde os agentes das lojas conectam (conexao de saida deles).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentSocketHandler handler;

    public WebSocketConfig(AgentSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/agent").setAllowedOrigins("*");
    }

    /**
     * Buffer de mensagem grande: as respostas dos agentes (ex.: /caixa/fechamentos com muitos
     * caixas) passam de 20KB e estouravam o default de 8KB, derrubando a conexao. 2MB resolve.
     * Sem idle-timeout no servidor (o keepalive ja mantem a conexao viva).
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }
}
