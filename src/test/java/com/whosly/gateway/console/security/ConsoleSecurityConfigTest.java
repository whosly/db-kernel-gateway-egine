package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleSecurityConfigTest {

    @Test
    void resolvedModeAutoOpenWhenNoTokens() {
        ConsoleAuthProperties props = new ConsoleAuthProperties();
        assertThat(props.resolvedMode("", "")).isEqualTo(AuthMode.OPEN);
        assertThat(props.resolvedMode("secret", "")).isEqualTo(AuthMode.TOKEN);
    }

    @Test
    void resolvedModeHonorsExplicitFormAndOidc() {
        ConsoleAuthProperties props = new ConsoleAuthProperties();
        props.setMode("form");
        assertThat(props.resolvedMode("secret", "")).isEqualTo(AuthMode.FORM);
        props.setMode("oidc");
        assertThat(props.resolvedMode("", "")).isEqualTo(AuthMode.OIDC);
    }

    @Test
    void bindsUsersFromYamlStyleMap() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "gateway.console.auth.mode", "form",
                "gateway.console.auth.users[0].username", "admin",
                "gateway.console.auth.users[0].password", "admin",
                "gateway.console.auth.users[0].roles", "CONSOLE_ADMIN"
        ));
        ConsoleAuthProperties props = new Binder(source)
                .bind("gateway.console.auth", Bindable.of(ConsoleAuthProperties.class))
                .get();
        assertThat(props.getMode()).isEqualTo("form");
        assertThat(props.getUsers()).hasSize(1);
        assertThat(props.getUsers().get(0).getUsername()).isEqualTo("admin");
        assertThat(props.getUsers().get(0).getRoles()).contains("CONSOLE_ADMIN");
    }

    @Test
    void formModeUserDetailsServiceHasAdminAndViewerDefaults() {
        ConsoleAuthProperties props = new ConsoleAuthProperties();
        props.setMode("form");
        ConsoleSecurityConfig cfg = new ConsoleSecurityConfig(props, "", "");
        assertThat(cfg.consoleAuthMode()).isEqualTo(AuthMode.FORM);
        PasswordEncoder encoder = cfg.consolePasswordEncoder();
        UserDetailsService uds = cfg.consoleUserDetailsService(encoder);
        assertThat(uds.loadUserByUsername("admin").getAuthorities())
                .anyMatch(a -> a.getAuthority().contains("CONSOLE_ADMIN"));
        assertThat(uds.loadUserByUsername("viewer").getAuthorities())
                .anyMatch(a -> a.getAuthority().contains("CONSOLE_VIEWER"));
        assertThat(encoder.matches("admin", uds.loadUserByUsername("admin").getPassword())).isTrue();
    }
}
