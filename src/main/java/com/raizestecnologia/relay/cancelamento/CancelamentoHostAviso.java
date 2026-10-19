package com.raizestecnologia.relay.cancelamento;

import com.fasterxml.jackson.databind.JsonNode;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import com.raizestecnologia.relay.loja.LojaService;
import com.raizestecnologia.relay.push.DeviceToken;
import com.raizestecnologia.relay.push.DeviceTokenRepository;
import com.raizestecnologia.relay.push.FcmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agente Host avisou: venda cancelada com liberação de outro usuário (o caixa pediu, o gerente liberou).
 * Manda push pros usuários do app ligados à loja.
 */
@Service
public class CancelamentoHostAviso {

    private static final Logger log = LoggerFactory.getLogger(CancelamentoHostAviso.class);

    private final UserEmpresaRepository vinculos;
    private final DeviceTokenRepository tokens;
    private final FcmSender fcm;
    private final LojaService lojas;

    public CancelamentoHostAviso(UserEmpresaRepository vinculos, DeviceTokenRepository tokens, FcmSender fcm, LojaService lojas) {
        this.vinculos = vinculos;
        this.tokens = tokens;
        this.fcm = fcm;
        this.lojas = lojas;
    }

    public void avisar(String loja, JsonNode n) {
        try {
            List<Long> ids = new ArrayList<>();
            for (Object[] v : vinculos.resumoPorCnpj(loja)) {
                if (Boolean.TRUE.equals(v[3]) && !ids.contains((Long) v[0])) ids.add((Long) v[0]);
            }
            if (ids.isEmpty()) return;
            List<String> toks = tokens.findByUserIdIn(ids).stream().map(DeviceToken::getToken).toList();
            String nome = lojas.conhecidas().getOrDefault(loja, "");
            String cupom = n.path("cupom").asText(""), valor = n.path("valor").asText("");
            String pediu = n.path("pediu").asText("?"), liberou = n.path("liberou").asText("?");
            fcm.sendToTokens(toks, "Venda cancelada" + (nome.isBlank() ? "" : " · " + nome),
                    "Cupom " + cupom + (valor.isBlank() ? "" : " (" + valor + ")") + ": " + pediu + " pediu, " + liberou + " liberou.",
                    Map.of("tipo", "cancelamento_host", "cupom", cupom));
            log.info("[cancelamento-host] loja {} cupom {}: {} pediu, {} liberou (push pra {} aparelho(s))",
                    loja, cupom, pediu, liberou, toks.size());
        } catch (Exception e) {
            log.warn("[cancelamento-host] aviso da loja {} falhou: {}", loja, e.getMessage());
        }
    }
}
