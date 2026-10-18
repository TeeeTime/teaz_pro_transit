import type { Ref } from 'vue'

export interface AuthUser {
  name: string
  username: string
  email?: string
  /** Keycloak realm roles (realm_access.roles). For showing/hiding UI only; backends enforce them. */
  roles: readonly string[]
}

/*
 * The contract every auth implementation (mock or Keycloak) fulfils, so
 * components only ever talk to `useAuth()` and never to a concrete client.
 */
export interface AuthService {
  user: Readonly<Ref<AuthUser | null>>
  /** Resolves once a user is logged in. May redirect to the login page instead. */
  init(): Promise<void>
  logout(): Promise<void>
  /** A valid access token for backend calls, refreshed if needed. */
  getToken(): Promise<string | undefined>
}
