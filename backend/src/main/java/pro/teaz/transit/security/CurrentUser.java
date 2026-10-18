package pro.teaz.transit.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Whose station a request is about: the Keycloak user id (JWT "sub") in prod.
 * Without the prod profile there's no login (MockSecurityConfig), so there's
 * one fixed local user.
 */
@Component
public class CurrentUser {

	public static final String LOCAL_USER = "local-dev-user";

	public String id() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth instanceof JwtAuthenticationToken jwt) return jwt.getToken().getSubject();
		return LOCAL_USER;
	}
}
