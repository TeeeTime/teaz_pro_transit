/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Keycloak base URL, e.g. https://auth.teaz.pro. Unset = mock auth. */
  readonly VITE_KEYCLOAK_URL?: string
  readonly VITE_KEYCLOAK_REALM: string
  readonly VITE_KEYCLOAK_CLIENT_ID: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
