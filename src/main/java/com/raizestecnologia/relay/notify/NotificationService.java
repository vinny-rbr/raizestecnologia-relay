package com.raizestecnologia.relay.notify;

/**
 * Camada de notificacao do relay. Os transportes reais (email, push FCM) implementam
 * ou decoram esta interface. Por enquanto {@link LoggingNotificationService} apenas loga,
 * mantendo o pipeline funcional ate as credenciais (SMTP / Firebase) serem configuradas.
 *
 * O publico-alvo padrao e o(s) usuario(s) MASTER/DONO do sistema.
 */
public interface NotificationService {

    /** Notifica o(s) usuario(s) MASTER/DONO (email + push, conforme transporte disponivel). */
    void notifyMaster(String titulo, String corpo);
}
