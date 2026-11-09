<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Reading, Setting, Collection, Clock, Folder, Memo, HomeFilled, Moon, Sunny, Cpu, Fold, Expand, DArrowLeft } from '@element-plus/icons-vue'
import FileUpload from '@/components/FileUpload.vue'
import TaskList from '@/components/TaskList.vue'
import TaskHistory from '@/components/TaskHistory.vue'
import PromptManager from '@/components/PromptManager.vue'
import DictionaryManager from '@/components/DictionaryManager.vue'
import CreationManager from '@/components/CreationManager.vue'
import CacheManager from '@/components/CacheManager.vue'
import LlmPoolManager from '@/components/LlmPoolManager.vue'
import { useStarborn } from '@/composables/useStarborn'
import { useTheme } from '@/composables/useTheme'
import { useSidebar } from '@/composables/useSidebar'
import { useFileDropGuard } from '@/composables/useFileDropGuard'
import type { FileUploadResponse } from '@/types'

const { isStarborn, activateStarborn, deactivateStarborn } = useStarborn()

const VALID_MENUS = ['home', 'translate', 'history', 'cache', 'prompt', 'dictionary', 'creations', 'llm-pool']

/** 星裔专属菜单，非星裔手动改 hash 也不该落进去，否则会看到一个空白页 */
const STARBORN_MENUS = ['llm-pool']

/** 从 hash 读取当前菜单 */
function getMenuFromHash(): string {
  var hash = location.hash.replace('#/', '').replace('#', '')
  if (!VALID_MENUS.includes(hash)) return 'home'
  if (STARBORN_MENUS.includes(hash) && !isStarborn.value) return 'home'
  return hash
}

const taskListRef = ref<InstanceType<typeof TaskList>>()
const activeMenu = ref(getMenuFromHash())

/** 翻译源模式（控制 FileUpload：ESM/ESP 文件 或 本地化 mod 的 Strings 文件夹） */
const uploadSourceMode = ref<'esm' | 'strings'>('esm')
const { isDark, toggleTheme } = useTheme()

/** 主题按钮只有图标 文案改由 title/aria-label 承载 保证可访问性 */
const themeToggleLabel = computed(() => (isDark.value ? '切换到亮色模式' : '切换到暗黑模式'))

const { sidebarMode, cycleSidebar } = useSidebar()

/** 各形态的侧栏宽度，仅图标宽度与 el-menu 折叠宽度（图标 24 + 两侧内边距 20×2）一致 */
const ASIDE_WIDTH = { expanded: '200px', icon: '64px', hidden: '0px' } as const
const asideWidth = computed(() => ASIDE_WIDTH[sidebarMode.value])

/** 折叠按钮的图标和提示描述的是「点击后会变成什么」 */
const sidebarToggle = computed(() => {
  if (sidebarMode.value === 'expanded') return { icon: Fold, label: '收起为图标' }
  if (sidebarMode.value === 'icon') return { icon: DArrowLeft, label: '完全收起导航栏' }
  return { icon: Expand, label: '展开导航栏' }
})

// 全站防拖偏：文件只能拖进上传区域
useFileDropGuard()

/** 菜单切换时同步 hash */
function handleMenuSelect(index: string) {
  activeMenu.value = index
  location.hash = '#/' + index
}

/** 监听浏览器前进后退 */
function onHashChange() {
  activeMenu.value = getMenuFromHash()
}

onMounted(() => window.addEventListener('hashchange', onHashChange))
onUnmounted(() => window.removeEventListener('hashchange', onHashChange))

/** Unity 彩蛋：连续点击 logo 切换星裔权限（激活 10 次，退出 3 次） */
const clickCount = ref(0)
let clickTimer: ReturnType<typeof setTimeout> | null = null

function handleLogoClick() {
  clickCount.value++
  if (clickTimer) clearTimeout(clickTimer)
  clickTimer = setTimeout(() => { clickCount.value = 0 }, 2000)
  const threshold = isStarborn.value ? 3 : 10
  if (clickCount.value >= threshold) {
    clickCount.value = 0
    if (isStarborn.value) {
      deactivateStarborn()
      ElMessageBox.alert('新的开始', 'Unity', {
        confirmButtonText: '确认',
        type: 'info',
      })
    } else {
      activateStarborn()
      ElMessageBox.alert('你已归一，星裔', 'Unity', {
        confirmButtonText: '确认',
        type: 'success',
      })
    }
  }
}

/** 文件上传成功回调 */
function handleUploadSuccess(payload: FileUploadResponse) {
  taskListRef.value?.addTask(payload.taskId)
}
</script>

<template>
  <el-container class="app-layout">
    <!-- 完全折叠时侧栏宽度为 0 但仍在 DOM 里，用 inert 让里面的菜单不能被 Tab 聚焦 -->
    <el-aside
      :width="asideWidth"
      class="app-aside"
      :class="'is-' + sidebarMode"
      :inert="sidebarMode === 'hidden'"
    >
      <div class="logo">
        <img
          src="/logo.png"
          alt="Starfield Logo"
          class="logo-img"
          @click="handleLogoClick"
          style="cursor: pointer; user-select: none;"
        />
        <p class="logo-sub">Mod 翻译工具</p>
      </div>
      <!-- 菜单名必须放在 title 插槽里，折叠成图标时 Element Plus 才会在悬停时显示菜单名 -->
      <el-menu
        :default-active="activeMenu"
        :collapse="sidebarMode === 'icon'"
        :collapse-transition="false"
        @select="handleMenuSelect"
        class="side-menu"
      >
        <el-menu-item index="home">
          <el-icon><HomeFilled /></el-icon>
          <template #title>首页</template>
        </el-menu-item>
        <el-menu-item index="translate">
          <el-icon><Reading /></el-icon>
          <template #title>翻译</template>
        </el-menu-item>
        <el-menu-item index="history">
          <el-icon><Clock /></el-icon>
          <template #title>翻译历史</template>
        </el-menu-item>
        <el-menu-item index="cache">
          <el-icon><Memo /></el-icon>
          <template #title>翻译缓存</template>
        </el-menu-item>
        <el-menu-item index="prompt">
          <el-icon><Setting /></el-icon>
          <template #title>Prompt 管理</template>
        </el-menu-item>
        <el-menu-item index="dictionary">
          <el-icon><Collection /></el-icon>
          <template #title>词典</template>
        </el-menu-item>
        <el-menu-item index="creations">
          <el-icon><Folder /></el-icon>
          <template #title>Creations</template>
        </el-menu-item>
        <el-menu-item v-if="isStarborn" index="llm-pool">
          <el-icon><Cpu /></el-icon>
          <template #title>模型池</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="app-body">
      <!-- 侧栏折叠：定位在内容区左上角（侧栏完全折叠后只能放这里），单按钮三态循环 -->
      <el-button
        text
        circle
        class="sidebar-toggle-btn"
        :title="sidebarToggle.label"
        :aria-label="sidebarToggle.label"
        @click="cycleSidebar"
      >
        <el-icon :size="18">
          <component :is="sidebarToggle.icon" />
        </el-icon>
      </el-button>

      <!-- 主题切换：定位在内容区右上角 各页面通用 -->
      <el-button
        text
        circle
        class="theme-toggle-btn"
        :title="themeToggleLabel"
        :aria-label="themeToggleLabel"
        @click="toggleTheme"
      >
        <el-icon :size="18">
          <Sunny v-if="isDark" />
          <Moon v-else />
        </el-icon>
      </el-button>

      <el-main class="app-main">
        <div v-if="activeMenu === 'home'" class="page-content home-page">
          <h1 class="welcome-text">Welcome, Captain!</h1>
        </div>

        <div v-if="activeMenu === 'translate'" class="page-content">
          <div class="page-header">
            <h2 class="page-title">翻译任务</h2>
            <el-radio-group v-model="uploadSourceMode" size="small">
              <el-radio-button value="esm">ESM / ESP 文件</el-radio-button>
              <el-radio-button value="strings">本地化 mod</el-radio-button>
            </el-radio-group>
          </div>
          <FileUpload :source-mode="uploadSourceMode" @upload-success="handleUploadSuccess" />
          <TaskList ref="taskListRef" :limit="3" />
        </div>

        <div v-if="activeMenu === 'history'" class="page-content">
          <h2 class="page-title">翻译历史</h2>
          <TaskHistory />
        </div>

        <div v-if="activeMenu === 'prompt'" class="page-content">
          <h2 class="page-title">Prompt 管理</h2>
          <PromptManager />
        </div>

        <div v-if="activeMenu === 'dictionary'" class="page-content">
          <h2 class="page-title">词典</h2>
          <DictionaryManager />
        </div>

        <div v-if="activeMenu === 'creations'" class="page-content">
          <h2 class="page-title">Creations</h2>
          <CreationManager :is-starborn="isStarborn" />
        </div>

        <div v-if="activeMenu === 'cache'" class="page-content">
          <h2 class="page-title">翻译缓存</h2>
          <CacheManager :is-starborn="isStarborn" />
        </div>

        <div v-if="activeMenu === 'llm-pool' && isStarborn" class="page-content">
          <h2 class="page-title">模型池</h2>
          <LlmPoolManager :is-starborn="isStarborn" />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-aside {
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-lighter);
  overflow-y: auto;
  /* 宽度过渡期间菜单文字不换行，横向裁掉即可 */
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
}

/* 完全折叠时去掉右边框，否则会残留一条 1px 竖线 */
.app-aside.is-hidden {
  border-right: none;
}

.logo {
  padding: 20px 16px 12px;
  text-align: center;
  border-bottom: 1px solid var(--el-border-color-lighter);
  transition: padding 0.25s ease;
}

.logo-img {
  width: 140px;
  height: auto;
  display: block;
  margin: 0 auto;
  transition: width 0.25s ease;
}

.logo-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

/* 仅图标：logo 缩成小圆标，仍可点击触发彩蛋 */
.app-aside.is-icon .logo {
  padding: 16px 8px 12px;
}

.app-aside.is-icon .logo-img {
  width: 36px;
}

.app-aside.is-icon .logo-sub {
  display: none;
}

.side-menu {
  border-right: none;
}

/* 内容区作为主题按钮、折叠按钮的定位参照 */
.app-body {
  position: relative;
}

.theme-toggle-btn,
.sidebar-toggle-btn {
  position: absolute;
  top: 16px;
  z-index: 10;
  color: var(--el-text-color-regular);
}

.theme-toggle-btn {
  right: 24px;
}

.sidebar-toggle-btn {
  left: 16px;
}

.app-main {
  padding: 24px 32px;
  background: var(--el-bg-color-page);
}

.page-title {
  margin: 0 0 20px;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.page-header .page-title {
  margin: 0;
}

.page-content {
  max-width: 80%;
  margin: 0 auto;
}

.home-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  max-width: none;
}

.welcome-text {
  font-size: 36px;
  font-weight: 300;
  color: var(--el-text-color-primary);
  letter-spacing: 2px;
}
</style>
