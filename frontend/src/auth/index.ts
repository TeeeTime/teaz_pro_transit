import { createKeycloakAuth } from './keycloakAuth'
import { createMockAuth } from './mockAuth'
import type { AuthService } from './types'

export type { AuthService, AuthUser } from './types'

/*
 * Picks the auth implementation at build time: Keycloak when
 * VITE_KEYCLOAK_URL is set (see .env.production), otherwise the mock.
 * Plain `npm run dev` therefore stays offline and login-free.
 */
const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL

const auth: AuthService = keycloakUrl
  ? createKeycloakAuth({
      url: keycloakUrl,
      realm: import.meta.env.VITE_KEYCLOAK_REALM,
      clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
    })
  : createMockAuth()

export function useAuth(): AuthService {
  return auth
}
