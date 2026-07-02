import { ref } from 'vue'

const STORAGE_KEY = 'theme'

type ThemeMode = 'light' | 'dark'

/** 读取初始主题：优先本地存储，否则跟随系统偏好 */
function getInitialMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches
  return prefersDark ? 'dark' : 'light'
}

/** 是否暗色模式 */
const isDark = ref(getInitialMode() === 'dark')

/** 把主题应用到 <html> 上（Element Plus 依赖 html.dark class） */
function applyTheme(dark: boolean) {
  document.documentElement.classList.toggle('dark', dark)
}

// 模块加载即应用，避免挂载后闪烁
applyTheme(isDark.value)

/** 切换明暗模式并持久化 */
function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem(STORAGE_KEY, isDark.value ? 'dark' : 'light')
  applyTheme(isDark.value)
}

export function useTheme() {
  return { isDark, toggleTheme }
}
