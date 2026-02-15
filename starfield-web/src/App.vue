<script setup lang="ts">
import { ref } from 'vue'
import { Reading, Setting, Collection, Clock, Folder } from '@element-plus/icons-vue'
import FileUpload from '@/components/FileUpload.vue'
import TaskList from '@/components/TaskList.vue'
import TaskHistory from '@/components/TaskHistory.vue'
import PromptEditor from '@/components/PromptEditor.vue'
import DictionaryManager from '@/components/DictionaryManager.vue'
import CreationManager from '@/components/CreationManager.vue'
import type { FileUploadResponse } from '@/types'

const taskListRef = ref<InstanceType<typeof TaskList>>()
const activeMenu = ref('translate')

/** 文件上传成功回调 */
function handleUploadSuccess(payload: FileUploadResponse) {
  taskListRef.value?.addTask(payload.taskId)
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside width="200px" class="app-aside">
      <div class="logo">
        <img
          src="/logo.png"
          alt="Starfield Logo"
          class="logo-img"
        />
        <p class="logo-sub">Mod 翻译工具</p>
      </div>
      <el-menu
        :default-active="activeMenu"
        @select="(index: string) => activeMenu = index"
        class="side-menu"
      >
        <el-menu-item index="translate">
          <el-icon><Reading /></el-icon>
          <span>翻译</span>
        </el-menu-item>
        <el-menu-item index="history">
          <el-icon><Clock /></el-icon>
          <span>翻译历史</span>
        </el-menu-item>
        <el-menu-item index="prompt">
          <el-icon><Setting /></el-icon>
          <span>Prompt 设置</span>
        </el-menu-item>
        <el-menu-item index="dictionary">
          <el-icon><Collection /></el-icon>
          <span>固定词典</span>
        </el-menu-item>
        <el-menu-item index="creations">
          <el-icon><Folder /></el-icon>
          <span>Creations</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-main class="app-main">
        <div v-show="activeMenu === 'translate'" class="page-content">
          <h2 class="page-title">翻译任务</h2>
          <FileUpload @upload-success="handleUploadSuccess" />
          <TaskList ref="taskListRef" :limit="3" />
        </div>

        <div v-show="activeMenu === 'history'" class="page-content">
          <h2 class="page-title">翻译历史</h2>
          <TaskHistory />
        </div>

        <div v-show="activeMenu === 'prompt'" class="page-content">
          <h2 class="page-title">Prompt 设置</h2>
          <PromptEditor />
        </div>

        <div v-show="activeMenu === 'dictionary'" class="page-content">
          <h2 class="page-title">固定词典</h2>
          <DictionaryManager />
        </div>

        <div v-show="activeMenu === 'creations'" class="page-content" style="max-width: none">
          <h2 class="page-title">Creations</h2>
          <CreationManager />
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
}

.logo {
  padding: 20px 16px 12px;
  text-align: center;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.logo-img {
  width: 140px;
  height: auto;
  display: block;
  margin: 0 auto;
}

.logo-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.side-menu {
  border-right: none;
}

.app-main {
  max-width: 960px;
  padding: 24px 32px;
  background: var(--el-bg-color-page);
}

.page-title {
  margin: 0 0 20px;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.page-content {
  max-width: 800px;
}
</style>
