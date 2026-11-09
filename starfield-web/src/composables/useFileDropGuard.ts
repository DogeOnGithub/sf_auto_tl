import { onMounted, onUnmounted } from 'vue'

/**
 * 允许接收文件的区域：Element Plus 的拖拽上传框，以及手动打了 data-file-drop-zone 标记的区域
 * 新增自定义拖拽区时必须带上这个属性，否则会被全局拦截
 */
const DROP_ZONE_SELECTOR = '.el-upload-dragger, [data-file-drop-zone]'

/** 只管从系统拖进来的文件；页面内的拖拽排序（vuedraggable）和文字拖拽不带 Files 类型，不能误伤 */
export function isFileDrag(e: DragEvent): boolean {
  return !!e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')
}

/** 拖拽目标是否落在允许的上传区域内（目标可能是文本节点，取其父元素判断） */
function isInDropZone(e: DragEvent): boolean {
  var node = e.target as Node | null
  var el = node instanceof Element ? node : node?.parentElement
  return !!el?.closest(DROP_ZONE_SELECTOR)
}

/** 上传区域外拖动文件时显示禁止光标 */
function onDragOver(e: DragEvent) {
  if (!isFileDrag(e) || isInDropZone(e)) return
  e.preventDefault()
  e.dataTransfer!.dropEffect = 'none'
}

/** 上传区域外松手时阻止浏览器默认行为（打开或下载文件，页面跳走后已填的表单会丢失） */
function onDrop(e: DragEvent) {
  if (!isFileDrag(e) || isInDropZone(e)) return
  e.preventDefault()
}

/** 全站防拖偏：文件只能拖进上传区域，其他位置松手不做任何事。在根组件调用一次即可 */
export function useFileDropGuard() {
  onMounted(() => {
    window.addEventListener('dragover', onDragOver)
    window.addEventListener('drop', onDrop)
  })
  onUnmounted(() => {
    window.removeEventListener('dragover', onDragOver)
    window.removeEventListener('drop', onDrop)
  })
}
