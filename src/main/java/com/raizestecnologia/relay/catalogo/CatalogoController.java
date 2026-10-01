package com.raizestecnologia.relay.catalogo;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de produtos por codigo de barras.
 *  - GET  /api/catalogo/{ean}         (autenticado) -> dados do produto para preencher o cadastro.
 *  - POST /api/admin/catalogo/import  (DONO)        -> carga em lote do catalogo.
 * A autorizacao e feita pelo SecurityConfig (/api/admin/** = DONO; /api/** = autenticado).
 */
@RestController
public class CatalogoController {

    private final CatalogoProdutoRepository repo;

    public CatalogoController(CatalogoProdutoRepository repo) {
        this.repo = repo;
    }

    /** Busca um produto do catalogo pelo codigo de barras. 404 se nao achar. */
    @GetMapping("/api/catalogo/{ean}")
    public ResponseEntity<Map<String, Object>> porEan(@PathVariable String ean) {
        String e = ean == null ? "" : ean.replaceAll("\\D", "");
        if (e.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe o código de barras"));
        CatalogoProduto c = repo.findById(e).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(ApiEnvelope.fail("Produto não encontrado no catálogo"));
        return ResponseEntity.ok(ApiEnvelope.ok(json(c)));
    }

    /** GET /api/admin/catalogo?q=&limit= (DONO) — pesquisa o catalogo por nome/barras + total. */
    @GetMapping("/api/admin/catalogo")
    public ResponseEntity<Map<String, Object>> buscar(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) Integer limit) {
        int lim = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        List<Map<String, Object>> itens = new ArrayList<>();
        String termo = q == null ? "" : q.trim();
        if (!termo.isBlank()) {
            var page = org.springframework.data.domain.PageRequest.of(0, lim);
            for (CatalogoProduto c : repo.buscar(termo, page)) itens.add(json(c));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", repo.count());
        out.put("itens", itens);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    /** Carga em lote: {itens:[{barras,nome,ncm,cest,unidade,marca}, ...]}. Faz upsert por barras. */
    @PostMapping("/api/admin/catalogo/import")
    public ResponseEntity<Map<String, Object>> importar(@RequestBody Map<String, Object> body) {
        Object itensObj = body == null ? null : body.get("itens");
        if (!(itensObj instanceof List<?> itens) || itens.isEmpty()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Envie itens[]"));
        }
        List<CatalogoProduto> lote = new ArrayList<>();
        for (Object o : itens) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String barras = digits(str(m.get("barras")));
            if (barras.isEmpty()) continue;
            lote.add(new CatalogoProduto(
                    barras,
                    cut(str(m.get("nome")), 200),
                    cut(digits(str(m.get("ncm"))), 10),
                    cut(digits(str(m.get("cest"))), 10),
                    cut(str(m.get("unidade")), 10),
                    cut(str(m.get("marca")), 80)));
        }
        repo.saveAll(lote);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gravados", lote.size());
        out.put("totalCatalogo", repo.count());
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }

    private Map<String, Object> json(CatalogoProduto c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("barras", c.getBarras());
        m.put("nome", c.getNome() == null ? "" : c.getNome());
        m.put("ncm", c.getNcm() == null ? "" : c.getNcm());
        m.put("cest", c.getCest() == null ? "" : c.getCest());
        m.put("unidade", c.getUnidade() == null ? "" : c.getUnidade());
        m.put("marca", c.getMarca() == null ? "" : c.getMarca());
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String digits(String s) { return s == null ? "" : s.replaceAll("\\D", ""); }
    private static String cut(String s, int n) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }
}
