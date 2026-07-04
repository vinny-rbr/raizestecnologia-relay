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

    public RelayController(AgentHub hub) {
        this.hub = hub;
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
        List<Map<String, String>> lista = hub.empresas().stream()
                .filter(e -> dono
                        || (principal != null && principal.cnpjs().contains(onlyDigits(e.cnpj()))))
                .map(e -> Map.of("cnpj", e.cnpj(), "nome", e.nome()))
                .toList();
        return env(lista);
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
            if (!principal.cnpjs().contains(cnpjDigits)) {
                return json(403, "{\"success\":false,\"message\":\"Sem permissao para esta empresa\"}");
            }
        }

        AgentHub.Resposta r = hub.ask(empresa, request.getMethod(), path, query, body);
        return json(r.status(), r.body());
    }

    private static String onlyDigits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> env(Object data) {
        return Map.of("success", true, "data", data, "message", "", "timestamp", Instant.now().toString());
    }
}
