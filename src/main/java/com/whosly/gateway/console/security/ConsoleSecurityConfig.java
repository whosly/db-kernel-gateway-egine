package com.whosly.gateway.console.security;

import com.whosly.gateway.console.security.ConsoleAuthProperties.AuthMode;
import com.whosly.gateway.console.security.ConsoleAuthProperties.UserAccount;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Console SecurityFilterChain for auth modes open | token | form | oidc.
 * Static SPA under /console/** remains anonymously readable; API is mode-gated.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(ConsoleAuthProperties.class)
public class ConsoleSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSecurityConfig.class);

    public static final String ROLE_ADMIN = "CONSOLE_ADMIN";
    public static final String ROLE_VIEWER = "CONSOLE_VIEWER";

    private final ConsoleAuthProperties authProperties;
    private final String apiToken;
    private final String readToken;

    public ConsoleSecurityConfig(
            ConsoleAuthProperties authProperties,
            @Value("${gateway.console.api-token:}") String apiToken,
            @Value("${gateway.console.read-token:}") String readToken) {
        this.authProperties = authProperties;
        this.apiToken = apiToken != null ? apiToken.trim() : "";
        this.readToken = readToken != null ? readToken.trim() : "";
    }

    @Bean
    public AuthMode consoleAuthMode() {
        AuthMode mode = authProperties.resolvedMode(apiToken, readToken);
        log.info("Console auth mode resolved to {} (configured='{}')", mode,
                authProperties.getMode() == null || authProperties.getMode().isBlank()
                        ? "(auto)" : authProperties.getMode());
        if (mode == AuthMode.OIDC && !authProperties.getOidc().isConfigured()) {
            log.warn("auth.mode=oidc but issuer-uri/client-id not set — OAuth2 login will not activate; "
                    + "set gateway.console.auth.oidc.issuer-uri and client-id (see docs)");
        }
        return mode;
    }

    @Bean
    public PasswordEncoder consolePasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService consoleUserDetailsService(PasswordEncoder consolePasswordEncoder) {
        InMemoryUserDetailsManager mgr = new InMemoryUserDetailsManager();
        List<UserAccount> users = authProperties.getUsers();
        if (users == null || users.isEmpty()) {
            mgr.createUser(User.withUsername("admin")
                    .password(consolePasswordEncoder.encode("admin"))
                    .roles(ROLE_ADMIN)
                    .build());
            mgr.createUser(User.withUsername("viewer")
                    .password(consolePasswordEncoder.encode("viewer"))
                    .roles(ROLE_VIEWER)
                    .build());
            return mgr;
        }
        for (UserAccount u : users) {
            if (u.getUsername() == null || u.getUsername().isBlank()) {
                continue;
            }
            String raw = u.getPassword() != null ? u.getPassword() : "";
            String encoded = raw.startsWith("$2a$") || raw.startsWith("$2b$") || raw.startsWith("$2y$")
                    ? raw
                    : consolePasswordEncoder.encode(raw);
            String[] roles = parseRoles(u.getRoles());
            mgr.createUser(User.withUsername(u.getUsername().trim())
                    .password(encoded)
                    .roles(roles)
                    .build());
        }
        return mgr;
    }

    @Bean
    public AuthenticationManager consoleAuthenticationManager(
            UserDetailsService consoleUserDetailsService,
            PasswordEncoder consolePasswordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(consoleUserDetailsService);
        provider.setPasswordEncoder(consolePasswordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain consoleSecurityFilterChain(
            HttpSecurity http,
            AuthMode consoleAuthMode,
            ObjectProvider<ConsoleOidcRoleMapper> oidcRoleMapper,
            ObjectProvider<ConsoleAuditService> auditService)
            throws Exception {
        http.securityMatcher("/console/**", "/login/oauth2/**", "/oauth2/**");

        switch (consoleAuthMode) {
            case OPEN, TOKEN -> http.csrf(csrf -> csrf.disable())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            case FORM -> configureForm(http, auditService.getIfAvailable());
            case OIDC -> {
                if (authProperties.getOidc().isConfigured()) {
                    configureOidc(http, oidcRoleMapper.getIfAvailable(), auditService.getIfAvailable());
                } else {
                    log.warn("OIDC not fully configured — applying form-style chain as fallback");
                    configureForm(http, auditService.getIfAvailable());
                }
            }
        }

        AuthMode mode = consoleAuthMode;
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeJson(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "{\"ok\":false,\"message\":\"Unauthorized\",\"authMode\":\""
                                + mode.name().toLowerCase() + "\"}"))
                .accessDeniedHandler((req, res, e) -> writeJson(res, HttpServletResponse.SC_FORBIDDEN,
                        "{\"ok\":false,\"message\":\"Forbidden\"}")));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultPermitFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private void configureForm(HttpSecurity http, ConsoleAuditService audit) throws Exception {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http.csrf(csrf -> csrf
                        .csrfTokenRepository(repo)
                        .csrfTokenRequestHandler(requestHandler)
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/console/api/auth/login", "POST")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/console/api/auth/login", "/console/api/auth/me",
                                "/console/api/auth/mode", "/console/api/auth/status")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/console/api/**")
                        .hasAnyRole(ROLE_ADMIN, ROLE_VIEWER)
                        .requestMatchers("/console/api/**")
                        .hasRole(ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/console", "/console/", "/console/**")
                        .permitAll()
                        .anyRequest().permitAll())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout
                        .logoutUrl("/console/api/auth/logout")
                        .logoutSuccessHandler(jsonLogoutSuccess(audit)));
    }

    private void configureOidc(HttpSecurity http,
                               ConsoleOidcRoleMapper roleMapper,
                               ConsoleAuditService audit) throws Exception {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService = oidcUserService(roleMapper);
        AuthenticationSuccessHandler success = (req, res, authentication) -> {
            if (audit != null && authentication != null) {
                audit.record("auth.login", null,
                        ConsoleAuditService.detail("username", authentication.getName(),
                                "mode", "oidc", "ok", true));
            }
            res.sendRedirect("/console/");
        };

        http.csrf(csrf -> csrf.csrfTokenRepository(repo).csrfTokenRequestHandler(requestHandler))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/console/api/auth/mode", "/console/api/auth/status",
                                "/console/api/auth/me", "/oauth2/**", "/login/oauth2/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/console", "/console/", "/console/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/console/api/**")
                        .hasAnyRole(ROLE_ADMIN, ROLE_VIEWER)
                        .requestMatchers("/console/api/**")
                        .hasRole(ROLE_ADMIN)
                        .anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .successHandler(success)
                        .failureHandler((req, res, ex) -> {
                            log.warn("OIDC login failed: {}", ex.getMessage());
                            if (audit != null) {
                                audit.record("auth.login.failure", null,
                                        ConsoleAuditService.detail("mode", "oidc", "ok", false,
                                                "message", ex.getMessage()));
                            }
                            res.sendRedirect("/console/login?error=oidc");
                        }))
                .logout(logout -> logout
                        .logoutUrl("/console/api/auth/logout")
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(jsonLogoutSuccess(audit)));
    }

    static OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(ConsoleOidcRoleMapper roleMapper) {
        OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            OidcUser user = delegate.loadUser(userRequest);
            if (roleMapper == null) {
                return user;
            }
            Collection<? extends org.springframework.security.core.GrantedAuthority> mapped =
                    roleMapper.mapAuthorities(user.getClaims());
            String userNameAttr = userRequest.getClientRegistration().getProviderDetails()
                    .getUserInfoEndpoint().getUserNameAttributeName();
            if (userNameAttr == null || userNameAttr.isBlank()) {
                userNameAttr = "sub";
            }
            return new DefaultOidcUser(mapped, user.getIdToken(), user.getUserInfo(), userNameAttr);
        };
    }

    private static LogoutSuccessHandler jsonLogoutSuccess(ConsoleAuditService audit) {
        return (req, res, authentication) -> {
            if (audit != null) {
                String user = authentication != null ? authentication.getName() : null;
                audit.record("auth.logout", null,
                        ConsoleAuditService.detail("username", user, "ok", true));
            }
            writeJson(res, HttpServletResponse.SC_OK, "{\"ok\":true,\"message\":\"logged out\"}");
        };
    }

    private static void writeJson(HttpServletResponse res, int status, String json) throws java.io.IOException {
        res.setStatus(status);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(json);
    }

    private static String[] parseRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return new String[]{ROLE_VIEWER};
        }
        List<String> out = new ArrayList<>();
        for (String part : roles.split("[,\\s]+")) {
            String r = part.trim();
            if (r.isEmpty()) {
                continue;
            }
            if (r.startsWith("ROLE_")) {
                r = r.substring(5);
            }
            if ("ADMIN".equalsIgnoreCase(r) || "CONSOLE_ADMIN".equalsIgnoreCase(r)) {
                r = ROLE_ADMIN;
            } else if ("VIEWER".equalsIgnoreCase(r) || "CONSOLE_VIEWER".equalsIgnoreCase(r)
                    || "READ".equalsIgnoreCase(r)) {
                r = ROLE_VIEWER;
            }
            out.add(r);
        }
        return out.isEmpty() ? new String[]{ROLE_VIEWER} : out.toArray(String[]::new);
    }
}
