package br.com.claricejoias_ws.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfigurations {

    @Value("${api.url.front}")
    private String urlFront;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Rotas Públicas (Catálogo e Imagens)
                        .requestMatchers(HttpMethod.GET, "/api/produtos/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categorias/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/catalogo-publico/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/visitantes/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/visitantes/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/cadastro", "/api/auth/recuperar-senha").permitAll()

                        // CORREÇÃO AQUI: Adicionado a barra '/' no início das rotas
                        .requestMatchers("/api/arquivos/view/**").permitAll()
                        .requestMatchers("/api/carrinho/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/banners/ativos").permitAll()

                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()

                        // A SOLUÇÃO ESTÁ AQUI: Libera o POST (Cadastro) de Leads para os visitantes
                        .requestMatchers(HttpMethod.GET, "/api/leads/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/leads/**").permitAll()

                        // Checkout do e-commerce: precisa ser público porque um visitante
                        // anônimo (sem token) também finaliza pedido — a validação de preço/
                        // estoque acontece toda no servidor, nunca confiando no que o cliente manda.
                        .requestMatchers(HttpMethod.POST, "/api/pedidos/checkout").permitAll()

                        // Webhook do Mercado Pago: chamado direto por eles, sem token nosso.
                        .requestMatchers(HttpMethod.POST, "/api/pagamentos/mercadopago/webhook").permitAll()

                        // Opcional: Se você usa "OPTIONS" por conta do CORS do navegador, libere também:
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Rotas de Gestão de Produtos (Protegidas)
                        // hasAnyRole permite que tanto Operadores quanto Admins façam a ação
                        .requestMatchers(HttpMethod.POST, "/api/produtos/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/produtos/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/produtos/**").hasRole("ADMIN") // Geralmente, só Admin deleta

                        // Rotas de Gestão de Categorias (Adicionadas para segurança)
                        .requestMatchers(HttpMethod.POST, "/api/categorias/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/categorias/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/categorias/**").hasRole("ADMIN")

                        // Rotas de Gestão de Subcategorias (renomear/excluir — criar já cai em /api/categorias/**)
                        .requestMatchers(HttpMethod.PUT, "/api/subcategorias/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/subcategorias/**").hasRole("ADMIN")

                        // Rotas de Gestão de Banners (Protegidas)
                        .requestMatchers(HttpMethod.POST, "/api/banners/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/banners/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/banners/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/banners/**").hasAnyRole("OPERADOR", "ADMIN")

                        // Rotas de Gestão de Revendedores (Protegidas)
                        .requestMatchers(HttpMethod.POST, "/api/revendedores").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/revendedores/cadastrar").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/revendedores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/revendedores").hasAnyRole("OPERADOR", "ADMIN")

                        // Listagem geral de instâncias do WhatsApp: só ADMIN (cada revendedora
                        // já vê a própria pelas rotas /my-instance/*, que são self-scoped pelo JWT)
                        .requestMatchers(HttpMethod.GET, "/api/whatsapp/instances").hasRole("ADMIN")

                        // Rotas de Gestão de Arquivos (Protegidas, exceto /view/** já liberado acima)
                        .requestMatchers(HttpMethod.POST, "/api/arquivos/upload").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/arquivos/**").hasAnyRole("OPERADOR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/arquivos/url/**", "/api/arquivos/download/**").hasAnyRole("OPERADOR", "ADMIN")

                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(urlFront.split(",")));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // Aplica para todas as rotas
        return source;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null || realmAccess.isEmpty()) return java.util.Collections.emptyList();

            Collection<String> roles = (Collection<String>) realmAccess.get("roles");
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());
        });
        return converter;
    }
}