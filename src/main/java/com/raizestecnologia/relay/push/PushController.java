package com.raizestecnologia.relay.push;

import com.raizestecnologia.relay.auth.ApiEnvelope;
import com.raizestecnologia.relay.auth.CurrentUser;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Recebe o token FCM do aparelho e o associa ao usuario logado. */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final DeviceTokenRepository tokens;

    public PushController(DeviceTokenRepository tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/token")
    @Transactional
    public ResponseEntity<?> registrar(@RequestBody Map<String, String> body) {
        RelayPrincipal user = CurrentUser.get();
        if (user == null) {
            return ResponseEntity.status(401).body(ApiEnvelope.fail("Nao autenticado"));
        }
        String token = body.getOrDefault("token", "").trim();
        if (token.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiEnvelope.fail("token vazio"));
        }
        Long userId = parseId(user.userId());
        String platform = body.getOrDefault("platform", "");

        // upsert por token: se ja existe, so reassocia ao usuario/plataforma atuais
        DeviceToken dt = tokens.findByToken(token).orElseGet(() -> new DeviceToken(token, userId, platform));
        dt.setUserId(userId);
        dt.setPlatform(platform);
        dt.setAtualizadoEm(Instant.now());
        tokens.save(dt);
        return ResponseEntity.ok(ApiEnvelope.ok(Map.of("registrado", true)));
    }

    private static Long parseId(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }
}
