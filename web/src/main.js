import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import { RouterView } from 'vue-router';
import './styles.css';
import { router } from './router';
const app = createApp(RouterView).use(ElementPlus).use(router);
router.isReady().then(() => app.mount('#app'));
