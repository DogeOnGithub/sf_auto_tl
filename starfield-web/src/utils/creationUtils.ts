import type { Creation } from '@/types'

/**
 * 获取轮播图展示图片 URL
 * 优先级：bannerImageUrl > 首张普通图片 > null
 */
export function getCarouselImage(creation: Creation): string | null {
  if (creation.bannerImageUrl) {
    return creation.bannerImageUrl
  }
  if (creation.images && creation.images.length > 0 && creation.images[0].url) {
    return creation.images[0].url
  }
  return null
}
