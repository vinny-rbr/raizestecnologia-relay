package com.raizestecnologia.relay.saude;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/admin/saude — histórico de eventos de rede das lojas (só o master/DONO: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/saude")
public class SaudeController {

    private final EventoLojaRepository repo;

    public SaudeController(EventoLojaRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> lista(@RequestParam(required = false) String loja,
                                                     @RequestParam(defaultValue = "150") int limite) {
        PageRequest p = PageRequest.of(0, Math.max(1, Math.min(500, limite)));
        List<EventoLoja> ev = loja == null || loja.isBlank() ? repo.findByOrderByCriadoEmDesc(p)
                : repo.findByLojaOrderByCriadoEmDesc(loja.replaceAll("\\D", ""), p);
        List<Map<String, Object>> out = ev.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("loja", e.getLoja());
            m.put("lojaNome", e.getLojaNome());
            m.put("tipo", e.getTipo());
            m.put("nivel", e.getNivel());
            m.put("titulo", e.getTitulo());
            m.put("detalhe", e.getDetalhe());
            m.put("avisou", e.isPush());
            m.put("criadoEm", e.getCriadoEm().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }
}
