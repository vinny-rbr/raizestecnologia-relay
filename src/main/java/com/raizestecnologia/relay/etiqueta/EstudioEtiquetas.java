package com.raizestecnologia.relay.etiqueta;

import com.raizestecnologia.relay.AgentHub;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.loja.Recursos;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Estudio de Etiquetas (painel do master e da revenda): repassa pro agente da loja o layout das
 * etiquetas de gondola (config, previa, impressao) e a busca de produtos. So com o recurso ativo.
 */
@Service
public class EstudioEtiquetas {

    private static final Set<String> ACOES = Set.of("config", "previa", "imprimir", "regua", "avancar", "calibrar");

    private final AgentHub hub;
    private final LojaService lojas;

    public EstudioEtiquetas(AgentHub hub, LojaService lojas) {
        this.hub = hub;
        this.lojas = lojas;
    }

    /** acao = config | previa | imprimir | produtos (busca: ?q=). */
    public ResponseEntity<String> repassar(String cnpj, String method, String acao, String q, String body) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (!lojas.temRecurso(c, Recursos.ETIQUETAS)) {
            return json(403, "{\"success\":false,\"message\":\"Ative o Estúdio de Etiquetas nesta loja primeiro.\"}");
        }
        String path, query = "";
        if (acao.startsWith("cupom-")) { // cupom da cozinha (agente Link): editor que antes ficava so no PC da loja
            String a = acao.substring(6);
            if (!Set.of("config", "padrao", "previa", "pontos", "teste").contains(a)) {
                return json(404, "{\"success\":false,\"message\":\"Ação desconhecida\"}");
            }
            path = "/api/local/cupom/" + ("previa".equals(a) ? "previa64" : a);
            AgentHub.Resposta r = hub.ask(c, method, path, "", body);
            if (r.status() == 404) {
                return json(409, "{\"success\":false,\"message\":\"O cupom da cozinha só existe em loja Link com o agente atualizado.\"}");
            }
            return json(r.status(), r.body());
        }
        if ("produtos".equals(acao)) {
            path = "/api/produtos";
            query = "search=" + URLEncoder.encode(q == null ? "" : q.trim(), StandardCharsets.UTF_8);
        } else if (ACOES.contains(acao)) {
            path = "/api/etiquetas/" + acao;
        } else {
            return json(404, "{\"success\":false,\"message\":\"Ação desconhecida\"}");
        }
        AgentHub.Resposta r = hub.ask(c, method, path, query, body);
        if (r.status() == 404 && !"produtos".equals(acao)) {
            return json(409, "{\"success\":false,\"message\":\"O agente desta loja ainda não tem etiquetas: ele atualiza sozinho em até 1 hora.\"}");
        }
        return json(r.status(), r.body());
    }

    private static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
