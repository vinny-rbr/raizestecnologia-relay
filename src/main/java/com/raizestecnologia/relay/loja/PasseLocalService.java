package com.raizestecnologia.relay.loja;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.UserEmpresa;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Passe do modo LOCAL (garçom/caixa na rede da loja, sem internet). O relay assina com Ed25519 um
 * passe curto (usuário, loja, permissões, usuário do sistema, validade) e o agente confere com a
 * CHAVE PÚBLICA, offline. Nenhuma senha sai do relay. O par de chaves é gerado uma vez e guardado
 * no banco (relay_chave), pra valer entre deploys.
 */
@Service
public class PasseLocalService {

    public static final long VALIDADE_DIAS = 7;
    private static final String PRIV = "passe_local_privada";
    private static final String PUB = "passe_local_publica";

    private final RelayChaveRepository chaves;
    private final UserEmpresaRepository vinculos;
    private final LojaService lojas;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile PrivateKey privada;
    private volatile String publica;

    public PasseLocalService(RelayChaveRepository chaves, UserEmpresaRepository vinculos, LojaService lojas) {
        this.chaves = chaves;
        this.vinculos = vinculos;
        this.lojas = lojas;
    }

    /** Chave pública (X.509, base64) que o agente usa pra conferir os passes. */
    public String chavePublica() {
        carregar();
        return publica;
    }

    /** Passe assinado: base64url(json) + "." + base64url(assinatura). */
    public String emitir(AppUser u, String chaveLoja, Long usuarioSistemaId) {
        carregar();
        try {
            Instant agora = Instant.now();
            ObjectNode p = mapper.createObjectNode();
            p.put("uid", u.getId());
            p.put("nome", u.getNome() == null || u.getNome().isBlank() ? u.getEmail() : u.getNome());
            p.put("loja", chaveLoja);
            p.put("role", u.getRole());
            p.putPOJO("perms", u.permissoesList());
            if (usuarioSistemaId != null) p.put("us", usuarioSistemaId);
            p.put("iat", agora.getEpochSecond());
            p.put("exp", agora.plus(VALIDADE_DIAS, ChronoUnit.DAYS).getEpochSecond());
            String corpo = b64(mapper.writeValueAsBytes(p));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privada);
            s.update(corpo.getBytes(StandardCharsets.US_ASCII));
            return corpo + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível emitir o passe local", e);
        }
    }

    /**
     * Mensagem pro agente (no register e quando ele pede): chave pública, a loja dele, se o salão
     * está liberado e quem não vale mais (desativado ou trocou a senha depois de emitir o passe).
     */
    @Transactional(readOnly = true)
    public String mensagemAgente(String chaveLoja) {
        ObjectNode m = mapper.createObjectNode();
        m.put("type", "passes");
        m.put("publicKey", chavePublica());
        m.put("loja", chaveLoja);
        m.put("salao", lojas.temRecurso(chaveLoja, Recursos.SALAO));
        m.put("bloqueada", lojas.estaBloqueada(chaveLoja));
        var inativos = m.putArray("inativos");
        ObjectNode desde = m.putObject("validosDesde");
        for (UserEmpresa v : vinculos.findByCnpj(chaveLoja)) {
            AppUser u = v.getUser();
            if (u == null) continue;
            if (!u.isAtivo()) inativos.add(u.getId());
            if (u.getTokensValidosDesde() != null) desde.put(String.valueOf(u.getId()), u.getTokensValidosDesde().getEpochSecond());
        }
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"type\":\"passes\"}";
        }
    }

    private synchronized void carregar() {
        if (privada != null) return;
        try {
            var priv = chaves.findById(PRIV).orElse(null);
            var pub = chaves.findById(PUB).orElse(null);
            if (priv == null || pub == null) {
                KeyPair par = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                priv = chaves.save(new RelayChave(PRIV, Base64.getEncoder().encodeToString(par.getPrivate().getEncoded())));
                pub = chaves.save(new RelayChave(PUB, Base64.getEncoder().encodeToString(par.getPublic().getEncoded())));
            }
            privada = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv.getValor())));
            publica = pub.getValor();
        } catch (Exception e) {
            throw new IllegalStateException("Chaves do passe local indisponíveis", e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
