import './assets/main.css'
import '@teeetime/teaz-pro-ui/style.css'

import { createApp } from 'vue'
import App from './App.vue'
import { useAuth } from './auth'

// Mount only once logged in; with Keycloak, init() may redirect to the login page instead.
useAuth()
  .init()
  .then(() => createApp(App).mount('#app'))
  .catch((error) => {
    console.error('Login failed', error)
    document.getElementById('app')!.textContent = 'Login failed. Please reload the page.'
  })
