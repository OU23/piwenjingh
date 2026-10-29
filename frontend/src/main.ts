import { createApp, watch } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';

import App from './App.vue';
import router from './router';
import { setupRouterGuards } from './router/guards';
import { configureRequest } from './api/request';
import { ws } from './api/ws';
import { useUserStore } from './stores/user';
import { applyTheme, readStoredTheme } from './stores/theme';
import { handleSessionKicked } from './utils/sessionKicked';
import './styles/tokens.css';
// 主题样式：自动载入 styles/themes/*.css —— 新增主题只需放入一个 CSS 文件并在 stores/theme.ts 登记一行。
// 各主题用 html[data-theme="<key>"] 选择器隔离，故加载顺序无关。
import.meta.glob('./styles/themes/*.css', { eager: true });
import './styles/index.css';

// 首屏即应用已保存主题，避免闪烁（index.html 内联脚本已先行设置，属双保险）
applyTheme(readStoredTheme());

const app = createApp(App);

app.use(createPinia());
app.use(ElementPlus, { locale: zhCn });

// 守卫必须在 app.use(router) 之前注册：安装 router 时会触发首次导航
setupRouterGuards(router);

app.use(router);

// 请求层与登录态解耦（见 api/request.ts）：令牌获取与 401 处理在此注入
configureRequest({
  getToken: () => useUserStore().token,
  onUnauthorized: () => {
    // 401（含"会话已被替代"）：先断连，避免刷新后 WS 拿旧令牌反复重连
    ws.disconnect();
    useUserStore().clear();
    void router.push({ name: 'login' });
  },
});

// 会话终结（M-18 被踢 / M-21 权限变更）：服务端经 WebSocket 推送消息，或连接以 4002 / 4004 关闭
ws.onForceLogout((reason) => {
  void handleSessionKicked(router, reason);
});

// 会话建立与销毁：令牌出现即建连、清空即断开；未登录时零 WS 连接
watch(
  () => useUserStore().token,
  (token) => {
    if (token) {
      ws.connect(token);
    } else {
      ws.disconnect();
    }
  },
  { immediate: true },
);

app.mount('#app');
