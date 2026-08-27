package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Recebe as conexoes de SAIDA dos agentes das lojas.
 * Protocolo: agente envia {type:register,cnpj,nome,key} e depois {type:res,id,status,body}.
 * Autorizacao: se AGENT_KEY estiver setado, so agentes com essa chave sao "confiaveis".
 * Em transicao (AGENT_AUTH_ENFORCE=false) aceita sem chave mas avisa no log; com enforce=true rejeita.
 */
@Component
public class AgentSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentSocketHandler.class);

    private final AgentHub hub;
    private final String agentKey; // chave esperada dos agentes (env AGENT_KEY); vazio = auth desligada
    private final boolean enforce; // true = rejeita agente sem chave; false = so avisa (transicao)

    public AgentSocketHandler(AgentHub hub,
                              @Value("${AGENT_KEY:}") String agentKey,
                              @Value("${AGENT_AUTH_ENFORCE:false}") boolean enforce) {
        this.hub = hub;
        this.agentKey = agentKey == null ? "" : agentKey;
        this.enforce = enforce;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = hub.parse(message.getPayload());
        String type = node.path("type").asText("");
        switch (type) {
            case "register" -> {
                String cnpj = node.path("cnpj").asText("");
                String nome = node.path("nome").asText("");
                String key = node.path("key").asText("");
                String revenda = node.path("revenda").asText("");
                boolean authOn = !agentKey.isBlank();
                boolean chaveInvalida = authOn && !key.equals(agentKey);
                if (cnpj.isBlank()) {
                    /* sem cnpj, ignora */
                } else if (chaveInvalida && enforce) {
                    log.warn("[agent-auth] REJEITADO: agente sem chave valida (cnpj {}).", cnpj);
                    session.close(CloseStatus.NOT_ACCEPTABLE);
                } else {
                    if (chaveInvalida) {
                        log.warn("[agent-auth] TRANSICAO: agente sem chave valida (cnpj {}) - aceito por enquanto.", cnpj);
                    }
                    hub.register(cnpj, nome, revenda, session);
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
