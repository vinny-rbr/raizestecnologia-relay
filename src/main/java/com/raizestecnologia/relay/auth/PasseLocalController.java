package com.raizestecnologia.relay.auth;

import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.loja.PasseLocalService;
import com.raizestecnologia.relay.loja.Recursos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/auth/passe-local (logado, X-Empresa = loja) — passe assinado pro modo LOCAL do garçom
 * (tela de mesas na rede da loja falando direto com o agente, sem internet). Só pra loja que o
 * usuário enxerga, com o Salão liberado e não bloqueada.
 */
@RestController
@RequestMapping("/api/auth")
public class PasseLocalController {

    private final PasseLocalService passes;
    private final AppUserRepository users;
    private final UserEmpresaRepository vinculos;
    private final LojaService lojas;

    public PasseLocalController(PasseLocalService passes, AppUserRepository users, UserEmpresaRepository vinculos, LojaService lojas) {
        this.passes = passes;
        this.users = users;
        this.vinculos = vinculos;
        this.lojas = lojas;
    }

    @PostMapping("/passe-local")
    public ResponseEntity<Map<String, Object>> passe(@RequestHeader(value = "X-Empresa", required = false) String empresa) {
        RelayPrincipal p = CurrentUser.get();
        if (p == null) return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        String loja = empresa == null ? "" : empresa.replaceAll("\\D", "");
        if (loja.isBlank()) return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe a loja (X-Empresa)"));
        if (!p.isDono()) {
            Set<String> visiveis = new java.util.HashSet<>(p.cnpjs() == null ? Set.of() : p.cnpjs());
            if (p.isRevenda()) visiveis.addAll(lojas.cnpjsDaRevenda(p.revendaCodigo()));
            if (!visiveis.contains(loja)) return ResponseEntity.status(403).body(ApiEnvelope.fail("Sem permissao para esta empresa"));
            if (lojas.estaBloqueada(loja)) return ResponseEntity.status(402).body(ApiEnvelope.fail("Acesso suspenso. Fale com o suporte."));
        }
        if (!lojas.temRecurso(loja, Recursos.SALAO))
            return ResponseEntity.status(403).body(ApiEnvelope.fail("Salao nao esta ativado nesta loja"));
        AppUser u = users.findById(Long.valueOf(p.userId())).orElse(null);
        if (u == null || !u.isAtivo()) return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        Long usuarioSistema = vinculos.findFirstByUserIdAndCnpj(u.getId(), loja)
                .map(UserEmpresa::getUsuarioSistemaId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("passe", passes.emitir(u, loja, usuarioSistema));
        out.put("validadeDias", PasseLocalService.VALIDADE_DIAS);
        return ResponseEntity.ok(ApiEnvelope.ok(out));
    }
}
