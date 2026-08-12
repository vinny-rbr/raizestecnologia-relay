package com.raizestecnologia.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raizestecnologia.relay.notify.AgentConnectedEvent;
import com.raizestecnologia.relay.notify.AgentDisconnectedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Registro dos agentes conectados (por CNPJ) e correlacao de requisicoes/respostas.
 */
@Component
public class AgentHub {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher publisher;

    public AgentHub(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    // cnpj -> sessao do agente da loja
    private final Map<String, WebSocketSession> agents = new ConcurrentHashMap<>();
    // cnpj -> nome fantasia da loja
    private final Map<String, String> names = new ConcurrentHashMap<>();
    // requestId -> future aguardando a resposta do agente
    private final Map<String, CompletableFuture<Resposta>> pending = new ConcurrentHashMap<>();

    public record Empresa(String cnpj, String nome) {}
    public record Resposta(int status, String body) {}

    public void register(String cnpj, String nome, WebSocketSession session) {
        agents.put(cnpj, session);
        names.put(cnpj, nome == null ? "" : nome);
        publisher.publishEvent(new AgentConnectedEvent(cnpj, names.get(cnpj)));
    }

    public void remove(WebSocketSession session) {
        String cnpj = null;
        for (Map.Entry<String, WebSocketSession> e : agents.entrySet()) {
            if (e.getValue().getId().equals(session.getId())) {
                cnpj = e.getKey();
                break;
            }
        }
        if (cnpj != null) {
            agents.remove(cnpj);
            publisher.publishEvent(new AgentDisconnectedEvent(cnpj, names.getOrDefault(cnpj, "")));
        }
    }

    public List<Empresa> empresas() {
        return agents.keySet().stream()
                .map(cnpj -> new Empresa(cnpj, names.getOrDefault(cnpj, "")))
                .toList();
    }

    public boolean online(String cnpj) {
        WebSocketSession s = agents.get(cnpj);
        return s != null && s.isOpen();
    }

    /** Recebe a resposta vinda do agente e completa o future correspondente. */
    public void completeResponse(String id, int status, String body) {
        CompletableFuture<Resposta> f = pending.remove(id);
        if (f != null) f.complete(new Resposta(status, body));
    }

    /** Envia uma requisicao ao agente da loja e aguarda a resposta (com timeout). */
    public Resposta ask(String cnpj, String method, String path, String query, String body) {
        WebSocketSession s = agents.get(cnpj);
        if (s == null || !s.isOpen()) {
            return new Resposta(503, "{\"success\":false,\"message\":\"Loja offline (agente nao conectado)\"}");
        }
        String id = UUID.randomUUID().toString();
        CompletableFuture<Resposta> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("type", "req");
            req.put("id", id);
            req.put("method", method);
            req.put("path", path);
            req.put("query", query == null ? "" : query);
            if (body != null) req.put("body", body);
            synchronized (s) {
                s.sendMessage(new TextMessage(mapper.writeValueAsString(req)));
            }
            return future.get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return new Resposta(504, "{\"success\":false,\"message\":\"Sem resposta da loja: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Keepalive: envia um "ping" a cada agente a cada 8s. Sem isso, o proxy da nuvem (Render)
     * fecha a conexao ociosa quando a loja nao esta sendo consultada pelo app — e a loja "some".
     * Como o trafego parte do servidor para o agente, mantem a conexao viva nos dois sentidos.
     */
    @Scheduled(fixedDelay = 8000)
    public void keepAlive() {
        if (agents.isEmpty()) return;
        TextMessage ping = new TextMessage("{\"type\":\"ping\"}");
        for (WebSocketSession s : agents.values()) {
            try {
                if (s != null && s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(ping);
                    }
                }
            } catch (IOException ignore) {
                // conexao morta; sera limpa no afterConnectionClosed do handler
            }
        }
    }

    public JsonNode parse(String text) throws IOException {
        return mapper.readTree(text);
    }
}
