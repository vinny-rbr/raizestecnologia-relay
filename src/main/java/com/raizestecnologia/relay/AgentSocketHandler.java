package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Recebe as conexoes de SAIDA dos agentes das lojas.
 * Protocolo: agente envia {type:register,cnpj,nome} e depois {type:res,id,status,body}.
 */
@Component
public class AgentSocketHandler extends TextWebSocketHandler {

    private final AgentHub hub;

    public AgentSocketHandler(AgentHub hub) {
        this.hub = hub;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = hub.parse(message.getPayload());
        String type = node.path("type").asText("");
        switch (type) {
            case "register" -> {
                String cnpj = node.path("cnpj").asText("");
                String nome = node.path("nome").asText("");
                if (!cnpj.isBlank()) {
                    hub.register(cnpj, nome, session);
                    session.sendMessage(new TextMessage("{\"type\":\"registered\"}"));
                }
            }
            case "res" -> hub.completeResponse(
                    node.path("id").asText(""),
                    node.path("status").asInt(200),
                    node.path("body").asText("{}"));
            case "ping" -> session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            default -> { /* ignora */ }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session);
    }
}
