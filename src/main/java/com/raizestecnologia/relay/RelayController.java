package com.raizestecnologia.relay;

import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API que o app consome. As rotas de auth/admin ficam nos controllers do pacote
 * {@code auth} (AuthController/AdminController). Aqui ficam health/empresas e o
 * repasse (catch-all) para o agente da loja selecionada (cabecalho X-Empresa = CNPJ).
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class RelayController {

    private final AgentHub hub;
    private final com.raizestecnologia.relay.audit.AuditoriaService auditoria;
    private final com.raizestecnologia.relay.loja.LojaService lojas;

    public RelayController(AgentHub hub, com.raizestecnologia.relay.audit.AuditoriaService auditoria,
                           com.raizestecnologia.relay.loja.LojaService lojas) {
        this.hub = hub;
        this.auditoria = auditoria;
        this.lojas = lojas;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return env(Map.of("status", "up", "service", "relay", "lojas", hub.empresas().size()));
    }

    /**
     * Lista as lojas (empresas) conectadas que o usuario logado pode ver.
     * DONO ve todas; OPERADOR ve apenas as vinculadas a ele (por CNPJ).
     */
    @GetMapping("/empresas")
    public Map<String, Object> empresas() {
        RelayPrincipal principal = CurrentUser.get();
        boolean dono = principal != null && "DONO".equalsIgnoreCase(principal.role());
        java.util.Set<String> visiveis = dono ? null : cnpjsVisiveis(principal);

        // Todas as lojas ja conhecidas pelo servidor (inclui as offline)...
        java.util.Map<String, String> conhecidas = lojas.conhecidas();
        // ...mais qualquer uma online agora que ainda nao tenha sido persistida.
        for (AgentHub.Empresa e : hub.empresas()) conhecidas.putIfAbsent(onlyDigits(e.cnpj()), e.nome());

        List<Map<String, Object>> lista = new java.util.ArrayList<>();
        for (var en : conhecidas.entrySet()) {
            String cnpj = en.getKey();
            if (!dono && !visiveis.contains(cnpj)) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("cnpj", cnpj);
            m.put("nome", en.getValue());
            m.put("online", hub.online(cnpj));
            boolean bloq = lojas.estaBloqueada(cnpj);
            m.put("bloqueada", bloq);
            m.put("motivo", bloq ? lojas.motivo(cnpj) : "");
            lista.add(m);
        }
        return env(lista);
    }

    /**
     * CNPJs que o usuario pode ver: os vinculos diretos (user_empresa) +, se for master de revenda,
     * todas as lojas daquela revenda (por revendaCodigo). DONO nao passa por aqui (ve tudo).
     */
    private java.util.Set<String> cnpjsVisiveis(RelayPrincipal p) {
        if (p == null) return java.util.Set.of();
        java.util.Set<String> out = new java.util.HashSet<>(p.cnpjs() == null ? java.util.Set.of() : p.cnpjs());
        if (p.isRevenda()) out.addAll(lojas.cnpjsDaRevenda(p.revendaCodigo()));
        return out;
    }

    /** Tudo o mais e repassado para o agente da loja (por CNPJ no cabecalho X-Empresa). */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> relay(HttpServletRequest request,
                                        @RequestHeader(value = "X-Empresa", required = false) String empresa,
                                        @RequestBody(required = false) String body) {
        String path = request.getRequestURI();          // ex.: /api/dashboard
        String query = request.getQueryString();          // ex.: periodo=atual

        if (empresa == null || empresa.isBlank()) {
            // Se so existe uma loja conectada, usa ela por padrao.
            List<AgentHub.Empresa> all = hub.empresas();
            if (all.size() == 1) empresa = all.get(0).cnpj();
        }
        if (empresa == null || empresa.isBlank()) {
            return json(400, "{\"success\":false,\"message\":\"Selecione uma empresa (X-Empresa)\"}");
        }

        // Autorizacao: o usuario logado so pode acessar as lojas vinculadas a ele.
        // DONO pode qualquer loja. (O SecurityConfig do R1 ja garante autenticado aqui.)
        RelayPrincipal principal = CurrentUser.get();
        if (principal != null && !"DONO".equalsIgnoreCase(principal.role())) {
            String cnpjDigits = onlyDigits(empresa);
            if (!cnpjsVisiveis(principal).contains(cnpjDigits)) {
                return json(403, "{\"success\":false,\"message\":\"Sem permissao para esta empresa\"}");
            }
            // Loja suspensa por pendencia de pagamento: bloqueia os usuarios dela
            // (o DONO/master continua acessando para poder administrar).
            if (lojas.estaBloqueada(cnpjDigits)) {
                String motivo = lojas.motivo(cnpjDigits);
                String msg = motivo.isBlank() ? "Acesso suspenso. Fale com o suporte." : motivo;
                return json(402, "{\"success\":false,\"bloqueada\":true,\"message\":\"" + escape(msg) + "\"}");
            }
        }

        // Autorizacao por MODULO: usuario restrito so acessa as telas liberadas pra ele.
        if (principal != null
                && !principal.podeModulo(com.raizestecnologia.relay.auth.Modulos.requeridos(request.getMethod(), path))) {
            return json(403, "{\"success\":false,\"message\":\"Sem permissao para esta funcao\"}");
        }

        // Recursos extras (salao / forca de vendas) so com a liberacao do master/revenda na loja.
        String recurso = com.raizestecnologia.relay.loja.Recursos.daRota(path);
        if (recurso != null && !lojas.temRecurso(onlyDigits(empresa), recurso)) {
            return json(403, "{\"success\":false,\"recursoDesativado\":true,\"message\":\""
                    + escape(com.raizestecnologia.relay.loja.Recursos.nome(recurso)) + " nao esta ativado nesta loja\"}");
        }

        AgentHub.Resposta r = hub.ask(empresa, request.getMethod(), path, query, body);

        // Auditoria da acao de escrita (contagem de estoque).
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.endsWith("/ajuste-estoque") && r.status() == 200) {
            auditoria.registrarAtual(onlyDigits(empresa), "ajuste_estoque",
                    "produto " + idDoAjuste(path) + " -> " + r.body());
        }

        return json(r.status(), r.body());
    }

    private static String onlyDigits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /** Escapa aspas/barra pra montar o JSON manual da mensagem de bloqueio. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Extrai o id do produto de /api/produtos/{id}/ajuste-estoque. */
    private static String idDoAjuste(String path) {
        try {
            String[] p = path.split("/");
            for (int i = 0; i < p.length - 1; i++) {
                if ("produtos".equals(p[i])) return p[i + 1];
            }
        } catch (Exception ignore) {}
        return "?";
    }

    private ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> env(Object data) {
        return Map.of("success", true, "data", data, "message", "", "timestamp", Instant.now().toString());
    }
}
