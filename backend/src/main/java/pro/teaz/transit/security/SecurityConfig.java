package pro.teaz.transit.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import pro.teaz.transit.TransitProperties;

/**
 * Production security: every request needs a valid Keycloak access token
 * (Bearer JWT) issued by the realm configured in application-prod.properties.
 * No role needed: every Teaz user may use Transit. {@code /api/board} is also
 * readable cross-origin from the apps in {@code transit.cors.allowed-origins}
 * (a tile in Portal), with the same rules.
 */
@Configuration
@Profile("prod")
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable());
		return http.build();
	}

	/** Picked up by {@code .cors()} above. Only the user's board is shared with other apps. */
	@Bean
	CorsConfigurationSource corsConfigurationSource(TransitProperties properties) {
		CorsConfiguration board = new CorsConfiguration();
		board.setAllowedOrigins(properties.cors().allowedOrigins());
		board.setAllowedMethods(List.of("GET"));
		board.setAllowedHeaders(List.of("Authorization"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/board", board);
		return source;
	}
}
