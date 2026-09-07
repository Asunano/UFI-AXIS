import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import './styles/main.css';

const app = createApp(App);

app.use(createPinia());
app.use(router);

app.mount('#app');
// FOUC 防闪：挂载完成后显示
document.getElementById('app')?.classList.add('mounted');
