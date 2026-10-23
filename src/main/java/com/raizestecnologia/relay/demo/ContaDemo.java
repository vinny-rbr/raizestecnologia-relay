package com.raizestecnologia.relay.demo;

import com.raizestecnologia.relay.auth.AppUser;
import com.raizestecnologia.relay.auth.AppUserRepository;
import com.raizestecnologia.relay.auth.RelayPrincipal;
import com.raizestecnologia.relay.auth.UserEmpresa;
import com.raizestecnologia.relay.auth.UserEmpresaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Conta de demonstração (apresentação pra clientes de outros estados, pelo app web em /demo).
 *  - login "demo" (senha em DEMO_SENHA, padrão "demo"), criada/ajustada sozinha ao subir;
 *  - vê só as lojas de demonstração (Host e Link da máquina do Lucas), com nome trocado;
 *  - só visualização: o relay recusa qualquer gravação dessa conta;
 *  - passa pelo bloqueio de loja suspensa (só ela, as lojas continuam como estão pros outros).
 */
@Component
public class ContaDemo {

    private static final Logger log = LoggerFactory.getLogger(ContaDemo.class);
    public static final String LOGIN = "demo";
    public static final String EMAIL = "demo@meugiro.com.br";

    /** CNPJ da loja de demonstração -> nome que a conta demo vê. */
    public static final Map<String, String> LOJAS = Map.of(
            "21698630000151", "Loja Demonstração (Host)",   // Big Construção da máquina do Lucas
            "1054348700010205", "Loja Demonstração (Link)"); // rt12 (InkDB de teste)

    private final AppUserRepository users;
    private final UserEmpresaRepository vinculos;
    private final PasswordEncoder encoder;
    private final String senha;

    public ContaDemo(AppUserRepository users, UserEmpresaRepository vinculos, PasswordEncoder encoder,
                     @Value("${DEMO_SENHA:demo}") String senha) {
        this.users = users;
        this.vinculos = vinculos;
        this.encoder = encoder;
        this.senha = senha;
    }

    public static boolean eh(RelayPrincipal p) {
        return p != null && p.email() != null
                && (EMAIL.equalsIgnoreCase(p.email()) || LOGIN.equalsIgnoreCase(p.email()));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void garantir() {
        try {
            AppUser u = users.findByEmailIgnoreCase(EMAIL).orElseGet(AppUser::new);
            boolean nova = u.getId() == null;
            u.setEmail(EMAIL);
            u.setLogin(LOGIN);
            u.setNome("Demonstração");
            u.setRole("OPERADOR");
            u.setPermissoes(null);   // todas as telas (só leitura)
            u.setAtivo(true);
            u.setSenhaProvisoria(false);
            if (nova || !encoder.matches(senha, u.getSenhaHash())) u.setSenhaHash(encoder.encode(senha));
            u = users.save(u);
            for (String cnpj : LOJAS.keySet()) {
                if (!vinculos.existsByUserIdAndCnpj(u.getId(), cnpj)) vinculos.save(new UserEmpresa(u, cnpj));
            }
            log.info("[demo] conta de demonstração pronta ({} lojas)", LOJAS.size());
        } catch (Exception e) {
            log.warn("[demo] não deu pra preparar a conta de demonstração: {}", e.getMessage());
        }
    }
}
