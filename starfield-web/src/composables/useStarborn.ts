import { ref } from 'vue'

const STORAGE_KEY = 'starborn_unity'

/** 星裔（管理员）权限状态 */
const isStarborn = ref(localStorage.getItem(STORAGE_KEY) === 'true')

/** 激活星裔权限 */
function activateStarborn() {
  isStarborn.value = true
  localStorage.setItem(STORAGE_KEY, 'true')
}

/** 重置星裔权限 */
function deactivateStarborn() {
  isStarborn.value = false
  localStorage.removeItem(STORAGE_KEY)
}

export function useStarborn() {
  return { isStarborn, activateStarborn, deactivateStarborn }
}
