package com.raizestecnologia.relay.auth;

import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Esqueci a senha" do app: o usuario informa o e-mail e a senha nova; o pedido fica
 * pendente ate o revendedor (ou o DONO) aprovar na aba Solicitacoes do painel.
 *   POST /api/auth/solicitar-senha {email, senha}   (publico, antes do login)
 * A aprovacao/recusa fica no RevendaController e no AdminController (usam os helpers daqui).
 */
@RestController
@RequestMapping("/api/auth")
public class SenhaResetController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;

    public SenhaResetController(AppUserRepository users, PasswordEncoder encoder, LoginThrottle throttle) {
        this.users = users;
        this.encoder = encoder;
        this.throttle = throttle;
    }

    @PostMapping("/solicitar-senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> solicitar(@RequestBody(required = false) Map<String, String> b) {
        String email = b == null || b.get("email") == null ? "" : b.get("email").trim();
        String senha = b == null || b.get("senha") == null ? "" : b.get("senha").trim();
        if (email.isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe o e-mail"));
        }
        if (senha.length() < 4) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("A senha precisa de ao menos 4 caracteres"));
        }
        String chave = "pedido-senha:" + email.toLowerCase();
        if (throttle.bloqueado(chave)) {
            return ResponseEntity.status(429).body(ApiEnvelope.fail("Muitas tentativas. Tente de novo em alguns minutos."));
        }
        throttle.falhou(chave); // conta cada pedido (no maximo 6 a cada 15 min)

        AppUser u = users.findByEmailIgnoreCase(email).orElse(null);
        if (u == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado"));
        }
        if (!u.isAtivo()) {
            return ResponseEntity.status(403).body(ApiEnvelope.fail("Usuário inativo. Fale com o administrador."));
        }
        u.setSenhaPendenteHash(encoder.encode(senha));
        u.setSenhaPendenteEm(Instant.now());
        users.save(u);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    // ---- helpers usados pelo painel (revenda e DONO) ----

    public static boolean temPedido(AppUser u) {
        return u.getSenhaPendenteHash() != null && !u.getSenhaPendenteHash().isBlank();
    }

    /** Aprova: a senha pedida vira a senha do usuario (ja e definitiva, ele mesmo escolheu). */
    public static void aprovar(AppUser u) {
        u.setSenhaHash(u.getSenhaPendenteHash());
        u.setSenhaProvisoria(false);
        recusar(u);
    }

    public static void recusar(AppUser u) {
        u.setSenhaPendenteHash(null);
        u.setSenhaPendenteEm(null);
    }

    /** Linha da aba Solicitacoes. [lojas] = nomes das lojas do usuario. */
    public static Map<String, Object> json(AppUser u, List<String> lojas) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("tipo", "senha");
        m.put("nome", u.getNome() == null ? "" : u.getNome());
        m.put("email", u.getEmail());
        m.put("lojas", lojas);
        m.put("pedidoEm", u.getSenhaPendenteEm() == null ? null : u.getSenhaPendenteEm().toString());
        return m;
    }
}
