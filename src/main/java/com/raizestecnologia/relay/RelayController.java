package com.raizestecnologia.relay;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API que o app consome. Rotas de auth/empresas sao locais; o resto e repassado
 * para o agente da loja selecionada (cabecalho X-Empresa = CNPJ).
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

    /** Lista as lojas (empresas) atualmente conectadas. */
    @GetMapping("/empresas")
    public Map<String, Object> empresas() {
        List<Map<String, String>> lista = hub.empresas().stream()
                .map(e -> Map.of("cnpj", e.cnpj(), "nome", e.nome()))
                .toList();
        return env(lista);
    }

    /** Login simplificado (o controle fino fica pra depois). Aceita e emite um token. */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        String email = body == null ? "" : String.valueOf(body.getOrDefault("email", ""));
        return env(Map.of(
                "id", UUID.randomUUID().toString(),
                "name", "Usuário",
                "store", "",
                "email", email,
                "token", "relay-" + UUID.randomUUID()));
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody(required = false) Map<String, Object> body) {
        return login(body);
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

        AgentHub.Resposta r = hub.ask(empresa, request.getMethod(), path, query, body);
        return json(r.status(), r.body());
    }

    private ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> env(Object data) {
        return Map.of("success", true, "data", data, "message", "", "timestamp", Instant.now().toString());
    }
}
