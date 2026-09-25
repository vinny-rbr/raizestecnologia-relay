package com.raizestecnologia.relay.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Emissao e validacao de JWT (HS256), expiracao 24h.
 * Secret via env JWT_SECRET (default dev base64 usado pelos agentes).
 */
@Service
public class JwtService {

    private static final long EXPIRACAO_MS = 24L * 60 * 60 * 1000; // 24h

    private final SecretKey key;

    public JwtService(
            @Value("${JWT_SECRET:cmFpemVzLXRlY25vbG9naWEtc3VwZXItc2VjcmV0LWtleS0yMDI2LWRldi1vbmx5LTEyMzQ1Ng==}")
            String secret) {
        this.key = Keys.hmacShaKeyFor(resolveKeyBytes(secret));
    }

    private static byte[] resolveKeyBytes(String secret) {
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret);
        } catch (Exception e) {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        // HS256 exige pelo menos 256 bits (32 bytes).
        if (bytes.length < 32) {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    /** Gera um JWT HS256 com subject = userId e claims email/role. */
    public String generate(Long userId, String email, String role) {
        return generate(userId, email, role, null);
    }

    /**
     * Gera um JWT com um id de sessao ({@code sid}) opcional. Quando o usuario tem
     * sessao unica, o filtro so aceita o token cujo sid bate com o sessaoId atual.
     */
    public String generate(Long userId, String email, String role, String sid) {
        return generate(userId, email, role, sid, null);
    }

    /**
     * Como acima, mas com claims extras (ex.: {@code revId} = id da revenda a que um
     * usuario-master pertence). Valores null sao ignorados.
     */
    public String generate(Long userId, String email, String role, String sid,
                           java.util.Map<String, Object> extra) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + EXPIRACAO_MS);
        var b = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(exp);
        if (sid != null && !sid.isBlank()) b.claim("sid", sid);
        if (extra != null) for (var e : extra.entrySet())
            if (e.getValue() != null) b.claim(e.getKey(), e.getValue());
        return b.signWith(key, Jwts.SIG.HS256).compact();
    }

    /** Valida a assinatura/expiracao e retorna as claims (lanca excecao se invalido). */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
