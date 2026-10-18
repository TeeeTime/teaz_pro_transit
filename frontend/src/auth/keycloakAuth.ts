import Keycloak from 'keycloak-js'
import { readonly, ref } from 'vue'
import type { AuthService, AuthUser } from './types'

export interface KeycloakSettings {
  url: string
  realm: string
  clientId: string
}

// Refresh the access token when it has less than this many seconds left.
const MIN_TOKEN_VALIDITY = 30

/*
 * Real login via Keycloak (OIDC authorization code flow + PKCE, public
 * client). The whole app requires a login, so init() redirects to the
 * Keycloak login page if there is no session yet.
 */
export function createKeycloakAuth(settings: KeycloakSettings): AuthService {
  const keycloak = new Keycloak(settings)
  const user = ref<AuthUser | null>(null)

  function readUser(): AuthUser | null {
    const token = keycloak.tokenParsed
    if (!token) return null
    const username = (token.preferred_username as string | undefined) ?? token.sub ?? ''
    return {
      name: (token.name as string | undefined) ?? username,
      username,
      email: token.email as string | undefined,
      roles: token.realm_access?.roles ?? [],
    }
  }

  keycloak.onTokenExpired = () => {
    keycloak.updateToken(MIN_TOKEN_VALIDITY).catch(() => keycloak.login())
  }
  keycloak.onAuthRefreshSuccess = () => {
    user.value = readUser()
  }

  return {
    user: readonly(user),
    async init() {
      await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256' })
      user.value = readUser()
    },
    async logout() {
      await keycloak.logout({ redirectUri: window.location.origin })
    },
    async getToken() {
      try {
        await keycloak.updateToken(MIN_TOKEN_VALIDITY)
      } catch {
        await keycloak.login()
        return undefined
      }
      return keycloak.token
    },
  }
}
