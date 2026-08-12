package com.raizestecnologia.relay.notify;

import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Notificador do MASTER: sempre registra no log e, de forma independente, envia
 * EMAIL (se MAIL_* configurado) e PUSH (se FIREBASE_CREDENTIALS configurado) para os
 * usuarios DONO ativos (+ MASTER_ALERT_EMAIL opcional no email). Sem transporte, degrada pra so-log.
 */
@Service
public class MasterNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(MasterNotifier.class);

    private final AppUserRepository users;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;
    private final ObjectProvider<JavaMailSender> mailProvider;
    private final String from;    // remetente (= MAIL_USERNAME); vazio = email desligado
    private final String extraTo; // destinatarios extras (CSV), via MASTER_ALERT_EMAIL

    public MasterNotifier(AppUserRepository users,
                          DeviceTokenRepository tokens,
                          FcmSender fcm,
                          ObjectProvider<JavaMailSender> mailProvider,
                          @Value("${spring.mail.username:}") String from,
                          @Value("${MASTER_ALERT_EMAIL:}") String extraTo) {
        this.users = users;
        this.tokens = tokens;
        this.fcm = fcm;
        this.mailProvider = mailProvider;
        this.from = from == null ? "" : from.trim();
        this.extraTo = extraTo == null ? "" : extraTo;
    }

    @Override
    public void notifyMaster(String titulo, String corpo) {
        log.warn("[notify-master] {} | {}", titulo, corpo);
        enviarEmail(titulo, corpo);
        enviarPush(titulo, corpo);
    }

    private void enviarEmail(String titulo, String corpo) {
        JavaMailSender mail = mailProvider.getIfAvailable();
        if (mail == null || from.isBlank()) return; // email nao configurado
        List<String> to = emailRecipients();
        if (to.isEmpty()) {
            log.warn("[notify-master] nenhum destinatario de email (DONO/MASTER_ALERT_EMAIL).");
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to.toArray(new String[0]));
            msg.setSubject("[Meu Giro] " + titulo);
            msg.setText(corpo);
            mail.send(msg);
            log.info("[notify-master] email enviado para {}", to);
        } catch (Exception e) {
            log.error("[notify-master] falha ao enviar email: {}", e.getMessage());
        }
    }

    private void enviarPush(String titulo, String corpo) {
        if (!fcm.enabled()) return; // push nao configurado
        try {
            List<Long> donoIds = users.findByRoleIgnoreCaseAndAtivoTrue("DONO").stream()
                    .map(AppUser::getId).toList();
            if (donoIds.isEmpty()) return;
            List<String> toks = tokens.findByUserIdIn(donoIds).stream()
                    .map(DeviceToken::getToken).toList();
            fcm.sendToTokens(toks, titulo, corpo);
        } catch (Exception e) {
            log.error("[notify-master] falha ao enviar push: {}", e.getMessage());
        }
    }

    /** DONOs ativos + emails extras (MASTER_ALERT_EMAIL), sem duplicar. */
    private List<String> emailRecipients() {
        Set<String> set = new LinkedHashSet<>();
        for (AppUser u : users.findByRoleIgnoreCaseAndAtivoTrue("DONO")) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) set.add(u.getEmail().trim());
        }
        for (String e : extraTo.split(",")) {
            if (!e.isBlank()) set.add(e.trim());
        }
        return new ArrayList<>(set);
    }
}
