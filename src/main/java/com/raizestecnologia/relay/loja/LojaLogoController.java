package com.raizestecnologia.relay.loja;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Logo da loja guardado na central (enviado pelo painel). O app usa nos PDFs; sem ele, cai no logo do sistema da loja.
 *  GET  /api/loja-logo/{cnpj}                usuário do app que vê a loja  -> {logo: base64 | null}
 *  POST /api/admin/lojas/{cnpj}/logo         master                        {logo: base64 | ""}  ("" = remove)
 *  (a revenda envia por /api/revenda/lojas/{cnpj}/logo, no RevendaController)
 */
@RestController
public class LojaLogoController {

    /** Até 600 KB (o painel já reduz a imagem antes de mandar). */
    static final int MAX_BYTES = 600 * 1024;

    private final LojaLogoRepository repo;
    private final LojaService lojas;

    public LojaLogoController(LojaLogoRepository repo, LojaService lojas) {
        this.repo = repo;
        this.lojas = lojas;
    }

    @GetMapping("/api/loja-logo/{cnpj}")
    public ResponseEntity<Map<String, Object>> ver(@PathVariable String cnpj) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        RelayPrincipal p = CurrentUser.get();
        boolean pode = p != null && (p.isDono() || p.podeVer(c)
                || (p.isRevenda() && lojas.cnpjsDaRevenda(p.revendaCodigo()).contains(c)));
        if (!pode) return ResponseEntity.status(403).body(ApiEnvelope.fail("Sem permissão para esta loja"));
        Map<String, Object> out = new HashMap<>();
        out.put("logo", repo.findById(c).map(l -> Base64.getEncoder().encodeToString(l.getImagem())).orElse(null));
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    @PostMapping("/api/admin/lojas/{cnpj}/logo")
    public ResponseEntity<Map<String, Object>> salvarMaster(@PathVariable String cnpj, @RequestBody(required = false) Map<String, String> body) {
        return salvar(repo, cnpj, body);
    }

    /** Grava (ou remove, com logo vazio) — usado pelo master e pela revenda. */
    public static ResponseEntity<Map<String, Object>> salvar(LojaLogoRepository repo, String cnpj, Map<String, String> body) {
        String c = cnpj == null ? "" : cnpj.replaceAll("\\D", "");
        if (c.isEmpty()) return ResponseEntity.status(400).body(ApiEnvelope.fail("cnpj invalido"));
        String b64 = body == null ? null : body.get("logo");
        if (b64 == null || b64.isBlank()) {
            repo.deleteById(c);
            return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "logo", false)));
        }
        if (b64.contains(",")) b64 = b64.substring(b64.indexOf(',') + 1); // aceita "data:image/png;base64,..."
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Imagem inválida"));
        }
        if (bytes.length > MAX_BYTES) return ResponseEntity.status(400).body(ApiEnvelope.fail("Imagem grande demais (máx. 600 KB)"));
        LojaLogo l = repo.findById(c).orElseGet(LojaLogo::new);
        l.setCnpj(c);
        l.setImagem(bytes);
        l.setAtualizadoEm(java.time.Instant.now());
        repo.save(l);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("cnpj", c, "logo", true)));
    }
}
