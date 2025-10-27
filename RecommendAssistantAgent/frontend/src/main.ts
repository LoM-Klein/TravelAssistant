import { createApp } from "vue";
import Antd from "ant-design-vue";
import App from "./App.vue";
import "ant-design-vue/dist/reset.css";
import "highlight.js/styles/monokai.css";
import Markdown from "vue3-markdown-it";

const app = createApp(App);

app.use(Antd).use(Markdown).mount("#app");

