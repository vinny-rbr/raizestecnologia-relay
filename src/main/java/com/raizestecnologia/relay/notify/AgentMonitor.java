package com.raizestecnologia.relay.notify;

import com.raizestecnologia.relay.AgentHub;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Vigia as conexoes dos agentes e avisa o MASTER quando uma loja fica REALMENTE offline.
 *
 * Debounce: uma queda so vira alerta se o agente continuar fora depois de {@code delaySeconds}.
 * Isso evita falso-alarme em quedinha que se reconecta sozinha (o proxy da Render derruba
 * conexao ociosa; o keepAlive normalmente reconecta em segundos).
 */
@Component
public class AgentMonitor {

    private static final Logger log = LoggerFactory.getLogger(AgentMonitor.class);

    private final AgentHub hub;
    private final NotificationService notifier;
    private final long delaySeconds;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-monitor");
                t.setDaemon(true);
                return t;
            });

    // cnpj -> tarefa agendada que confirmara a queda apos o debounce
    private final Map<String, ScheduledFuture<?>> pendentes = new ConcurrentHashMap<>();
    // cnpj -> nome fantasia conhecido (pra montar a mensagem)
    private final Map<String, String> nomes = new ConcurrentHashMap<>();
    // cnpjs para os quais JA avisamos que caiu (evita repetir e permite avisar quando voltar)
    private final Set<String> alertados = ConcurrentHashMap.newKeySet();

    public AgentMonitor(AgentHub hub,
                        NotificationService notifier,
                        @Value("${AGENT_DOWN_ALERT_DELAY_SECONDS:90}") long delaySeconds) {
        this.hub = hub;
        this.notifier = notifier;
        this.delaySeconds = delaySeconds;
    }

    @EventListener
    public void onConnect(AgentConnectedEvent ev) {
        String cnpj = ev.cnpj();
        if (ev.nome() != null && !ev.nome().isBlank()) nomes.put(cnpj, ev.nome());
        // cancela uma confirmacao de queda pendente (reconectou dentro do debounce)
        ScheduledFuture<?> f = pendentes.remove(cnpj);
        if (f != null) f.cancel(false);
        // se ja tinhamos avisado que caiu, avisa que voltou
        if (alertados.remove(cnpj)) {
            String nome = etiqueta(cnpj);
            log.info("[agent-monitor] {} voltou a ficar online.", nome);
            notifier.notifyMaster("Loja reconectada",
                    "O agente de \"" + nome + "\" voltou a ficar online.");
        }
    }

    @EventListener
    public void onDisconnect(AgentDisconnectedEvent ev) {
        String cnpj = ev.cnpj();
        if (ev.nome() != null && !ev.nome().isBlank()) nomes.put(cnpj, ev.nome());
        // agenda a confirmacao: so alerta se continuar offline apos o debounce
        ScheduledFuture<?> anterior = pendentes.put(cnpj,
                scheduler.schedule(() -> confirmarQueda(cnpj), delaySeconds, TimeUnit.SECONDS));
        if (anterior != null) anterior.cancel(false);
    }

    private void confirmarQueda(String cnpj) {
        pendentes.remove(cnpj);
        if (hub.online(cnpj)) return;          // reconectou nesse meio tempo
        if (!alertados.add(cnpj)) return;      // ja avisado
        String nome = etiqueta(cnpj);
        log.warn("[agent-monitor] {} esta OFFLINE ha {}s - precisa reiniciar o agente.", nome, delaySeconds);
        notifier.notifyMaster("Agente offline - reiniciar",
                "A loja \"" + nome + "\" esta offline ha mais de " + delaySeconds
                        + "s. Verifique o computador da loja e reinicie o agente (servico Meu Giro).");
    }

    private String etiqueta(String cnpj) {
        String nome = nomes.getOrDefault(cnpj, "");
        return nome.isBlank() ? cnpj : nome + " (" + cnpj + ")";
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
