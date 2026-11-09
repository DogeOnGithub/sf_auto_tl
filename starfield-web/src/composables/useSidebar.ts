import { ref } from 'vue'

const STORAGE_KEY = 'sidebar-mode'

/** 侧栏形态：expanded 完整展开，icon 仅保留图标，hidden 完全折叠 */
export type SidebarMode = 'expanded' | 'icon' | 'hidden'

/** 单按钮循环切换的顺序 */
const CYCLE: SidebarMode[] = ['expanded', 'icon', 'hidden']

/** 读取上次保存的形态，没有或被篡改时回落到展开 */
function getInitialMode(): SidebarMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  return CYCLE.includes(saved as SidebarMode) ? (saved as SidebarMode) : 'expanded'
}

/** 当前侧栏形态 */
const sidebarMode = ref<SidebarMode>(getInitialMode())

/** 按 展开 → 仅图标 → 完全折叠 → 展开 循环切换并持久化 */
function cycleSidebar() {
  const next = CYCLE[(CYCLE.indexOf(sidebarMode.value) + 1) % CYCLE.length]
  sidebarMode.value = next
  localStorage.setItem(STORAGE_KEY, next)
}

export function useSidebar() {
  return { sidebarMode, cycleSidebar }
}
