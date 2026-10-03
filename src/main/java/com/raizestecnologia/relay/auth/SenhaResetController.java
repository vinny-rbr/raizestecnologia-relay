package com.raizestecnologia.relay.auth;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * "Esqueci a senha" do app: manda um codigo de 6 digitos pro e-mail do usuario e,
 * com o codigo certo, troca a senha. Publico (usado antes do login).
 *   POST /api/auth/esqueci-senha    {email}
 *   POST /api/auth/redefinir-senha  {email, codigo, senha}
 */
@RestController
@RequestMapping("/api/auth")
public class SenhaResetController {

    private static final Logger log = LoggerFactory.getLogger(SenhaResetController.class);
    private static final Duration VALIDADE = Duration.ofMinutes(15);
    private static final int MAX_TENTATIVAS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final String from;

    public SenhaResetController(AppUserRepository users, PasswordEncoder encoder, LoginThrottle throttle,
                                ObjectProvider<JavaMailSender> mailProvider,
                                @Value("${spring.mail.username:}") String from) {
        this.users = users;
        this.encoder = encoder;
        this.throttle = throttle;
        this.mailProvider = mailProvider;
        this.from = from == null ? "" : from.trim();
    }

    @PostMapping("/esqueci-senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> esqueci(@RequestBody(required = false) Map<String, String> b) {
        String email = b == null ? "" : str(b.get("email"));
        if (email.isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe o e-mail"));
        }
        JavaMailSender mail = mailProvider.getIfAvailable();
        if (mail == null || from.isBlank()) {
            log.error("[esqueci-senha] e-mail nao configurado (MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD)");
            return ResponseEntity.status(503).body(ApiEnvelope.fail(
                    "Recuperação por e-mail indisponível no momento. Fale com o seu fornecedor."));
        }
        String chave = "reset:" + email.toLowerCase();
        if (throttle.bloqueado(chave)) {
            return ResponseEntity.status(429).body(ApiEnvelope.fail("Muitas tentativas. Tente de novo em alguns minutos."));
        }
        throttle.falhou(chave); // conta cada pedido de codigo (no maximo 6 a cada 15 min)

        AppUser u = users.findByEmailIgnoreCase(email).orElse(null);
        if (u == null) {
            return ResponseEntity.status(404).body(ApiEnvelope.fail("Usuário não encontrado"));
        }
        if (!u.isAtivo()) {
            return ResponseEntity.status(403).body(ApiEnvelope.fail("Usuário inativo. Fale com o administrador."));
        }

        String codigo = String.format("%06d", RANDOM.nextInt(1_000_000));
        u.setResetCodigoHash(encoder.encode(codigo));
        u.setResetExpira(Instant.now().plus(VALIDADE));
        u.setResetTentativas(0);
        users.save(u);

        try {
            String nome = u.getNome() == null || u.getNome().isBlank() ? "" : ", " + u.getNome();
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(u.getEmail());
            msg.setSubject("[Meu Giro] Código para redefinir a senha");
            msg.setText("Olá" + nome + "!\n\n"
                    + "Seu código para redefinir a senha do Meu Giro é: " + codigo + "\n\n"
                    + "Ele vale por 15 minutos. Se não foi você que pediu, é só ignorar este e-mail.");
            mail.send(msg);
        } catch (Exception e) {
            log.error("[esqueci-senha] falha ao enviar e-mail para {}: {}", u.getEmail(), e.getMessage());
            return ResponseEntity.status(503).body(ApiEnvelope.fail("Não foi possível enviar o e-mail. Tente de novo."));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    @PostMapping("/redefinir-senha")
    @Transactional
    public ResponseEntity<Map<String, Object>> redefinir(@RequestBody(required = false) Map<String, String> b) {
        String email = b == null ? "" : str(b.get("email"));
        String codigo = b == null ? "" : str(b.get("codigo")).replaceAll("\\D", "");
        String senha = b == null ? "" : str(b.get("senha"));
        if (email.isBlank() || codigo.isBlank()) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Informe o e-mail e o código"));
        }
        if (senha.length() < 4) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("A senha precisa de ao menos 4 caracteres"));
        }
        AppUser u = users.findByEmailIgnoreCase(email).orElse(null);
        if (u == null || u.getResetCodigoHash() == null || u.getResetExpira() == null) {
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Código inválido. Peça um novo."));
        }
        if (Instant.now().isAfter(u.getResetExpira()) || u.getResetTentativas() >= MAX_TENTATIVAS) {
            limpar(u);
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Código expirado. Peça um novo."));
        }
        if (!encoder.matches(codigo, u.getResetCodigoHash())) {
            u.setResetTentativas(u.getResetTentativas() + 1);
            users.save(u);
            return ResponseEntity.status(400).body(ApiEnvelope.fail("Código incorreto"));
        }
        u.setSenhaHash(encoder.encode(senha));
        u.setSenhaProvisoria(false);
        limpar(u);
        throttle.ok(email); // libera o login caso tenha travado por senha errada
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("ok", true)));
    }

    private void limpar(AppUser u) {
        u.setResetCodigoHash(null);
        u.setResetExpira(null);
        u.setResetTentativas(0);
        users.save(u);
    }

    private static String str(String s) {
        return s == null ? "" : s.trim();
    }
}
