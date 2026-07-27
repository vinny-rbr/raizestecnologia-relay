package com.raizestecnologia.relay.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Na subida, se app_user estiver vazio, cria um DONO a partir de
 * ADMIN_EMAIL/ADMIN_SENHA para permitir o primeiro login.
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminSenha;

    public UserBootstrap(AppUserRepository users,
                         PasswordEncoder encoder,
                         @Value("${ADMIN_EMAIL:admin@raizes.com}") String adminEmail,
                         @Value("${ADMIN_SENHA:raizes123}") String adminSenha) {
        this.users = users;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminSenha = adminSenha;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Garante um DONO de acesso a partir de ADMIN_EMAIL/ADMIN_SENHA: se JA existe
        // um usuario com esse email, nao mexe; senao cria (mesmo que a tabela ja tenha
        // outros usuarios). Assim da pra recuperar acesso admin sem tocar no banco.
        if (users.findByEmailIgnoreCase(adminEmail.trim()).isPresent()) {
            return;
        }
        AppUser dono = new AppUser();
        dono.setNome("Administrador");
        dono.setEmail(adminEmail.trim());
        dono.setSenhaHash(encoder.encode(adminSenha));
        dono.setRole("DONO");
        dono.setAtivo(true);
        users.save(dono);
        log.info("=================================================================");
        log.info(" Usuario DONO criado (tabela app_user estava vazia)");
        log.info("   email: {}", adminEmail);
        log.info("   senha: definida via ADMIN_SENHA (nao exibida por seguranca)");
        log.info("   >> Faca login e crie os demais usuarios na area de admin.");
        log.info("=================================================================");
    }
}
