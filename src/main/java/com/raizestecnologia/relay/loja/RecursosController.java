package com.raizestecnologia.relay.loja;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /api/recursos (X-Empresa) — o que o master/revenda liberou nesta loja; o app mostra os atalhos por aqui. */
@RestController
@CrossOrigin(origins = "*")
public class RecursosController {

    private final LojaService lojas;

    public RecursosController(LojaService lojas) {
        this.lojas = lojas;
    }

    @GetMapping("/api/recursos")
    public ResponseEntity<Map<String, Object>> recursos(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("salao", lojas.temRecurso(empresa, Recursos.SALAO));
        out.put("painelTv", lojas.temRecurso(empresa, Recursos.PAINEL_TV));
        out.put("forcaVendas", lojas.temRecurso(empresa, Recursos.FORCA_VENDAS));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }
}
