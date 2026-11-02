import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 宋体只用 700 一个字重；字体按字符范围切成小文件，浏览器只下载页面上用到的部分
import '@fontsource/noto-serif-sc/700.css'
// 顺序有关：设计变量和 Element Plus 的映射要在 Element Plus 的样式之后
import './styles/tokens.css'
import './styles/base.css'
import './styles/element.css'
import App from './App.vue'

createApp(App).use(ElementPlus, { locale: zhCn }).mount('#app')
