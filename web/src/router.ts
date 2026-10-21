import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'

export type ViewKey = 'dashboard' | 'projects' | 'assets' | 'inspections' | 'alerts'

const pagePaths: Record<ViewKey, string> = {
  dashboard: '/',
  projects: '/projects',
  assets: '/assets',
  inspections: '/inspections',
  alerts: '/alerts',
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...Object.entries(pagePaths).map(([name, path]) => ({ name, path, component: App })),
    { name: 'inspection-records', path: '/inspections/records/:planId?', component: App },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(to => {
  const legacyView = to.query.view
  if (to.path === '/' && typeof legacyView === 'string' && Object.hasOwn(pagePaths, legacyView)) {
    const { view, ...query } = to.query
    return { path: pagePaths[legacyView as ViewKey], query, hash: to.hash, replace: true }
  }
})
