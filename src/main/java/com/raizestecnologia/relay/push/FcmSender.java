package com.raizestecnologia.relay.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Envio de push via Firebase Cloud Messaging (Admin SDK).
 *
 * Credenciais (conta de servico do projeto Firebase) via env — desabilitado se ausente:
 *   FIREBASE_CREDENTIALS      = conteudo JSON da chave de servico (recomendado no Render)
 *   FIREBASE_CREDENTIALS_PATH = caminho de um arquivo JSON (alternativa local)
 */
@Service
public class FcmSender {

    private static final Logger log = LoggerFactory.getLogger(FcmSender.class);

    private final String credsJson;
    private final String credsPath;
    private volatile FirebaseMessaging messaging; // null = push desabilitado

    public FcmSender(@Value("${FIREBASE_CREDENTIALS:}") String credsJson,
                     @Value("${FIREBASE_CREDENTIALS_PATH:}") String credsPath) {
        this.credsJson = credsJson == null ? "" : credsJson;
        this.credsPath = credsPath == null ? "" : credsPath;
    }

    @PostConstruct
    void init() {
        try {
            InputStream in = null;
            if (!credsJson.isBlank()) {
                in = new ByteArrayInputStream(credsJson.getBytes(StandardCharsets.UTF_8));
            } else if (!credsPath.isBlank()) {
                in = new FileInputStream(credsPath);
            }
            if (in == null) {
                log.info("[fcm] sem credenciais (FIREBASE_CREDENTIALS) — push desabilitado.");
                return;
            }
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in)).build())
                    : FirebaseApp.getInstance();
            messaging = FirebaseMessaging.getInstance(app);
            log.info("[fcm] push habilitado.");
        } catch (Exception e) {
            log.error("[fcm] falha ao inicializar: {}", e.getMessage());
        }
    }

    public boolean enabled() {
        return messaging != null;
    }

    /** Envia uma notificacao para uma lista de tokens (ate 500). No-op se desabilitado/vazio. */
    public void sendToTokens(List<String> toks, String titulo, String corpo) {
        if (messaging == null || toks == null || toks.isEmpty()) return;
        try {
            MulticastMessage msg = MulticastMessage.builder()
                    .addAllTokens(toks)
                    .setNotification(Notification.builder().setTitle(titulo).setBody(corpo).build())
                    .build();
            BatchResponse resp = messaging.sendEachForMulticast(msg);
            log.info("[fcm] push enviado {}/{}", resp.getSuccessCount(), toks.size());
        } catch (Exception e) {
            log.error("[fcm] falha ao enviar push: {}", e.getMessage());
        }
    }
}
