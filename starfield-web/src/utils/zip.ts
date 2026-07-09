/**
 * 轻量 ZIP 打包工具（仅 store 存储、不压缩），无第三方依赖。
 * 用于把开启本地化 mod 的三个 Strings 文件在前端打包为 zip 后上传。
 * 文件体积很小，无需压缩；后端用 ZipInputStream 可正常读取 stored 条目。
 */

const CRC32_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    }
    table[n] = c >>> 0
  }
  return table
})()

/** 计算字节数组的 CRC-32 */
function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff
  for (let i = 0; i < bytes.length; i++) {
    crc = CRC32_TABLE[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

/** ZIP 条目：文件名 + 二进制内容 */
export interface ZipEntry {
  name: string
  data: Uint8Array
}

/**
 * 将多个文件打包为 ZIP（store 方式），返回 application/zip 的 Blob。
 *
 * @param entries 待打包的文件条目
 * @returns ZIP 文件 Blob
 */
export function createZip(entries: ZipEntry[]): Blob {
  const encoder = new TextEncoder()
  // UTF-8 文件名标记（general purpose bit 11）
  const FLAG_UTF8 = 0x0800
  // 固定的 1980-01-01 日期，避免部分工具对 0 日期报警
  const DOS_TIME = 0
  const DOS_DATE = 0x21

  const localParts: Uint8Array[] = []
  const centralParts: Uint8Array[] = []
  let offset = 0

  for (const entry of entries) {
    const nameBytes = encoder.encode(entry.name)
    const data = entry.data
    const crc = crc32(data)

    // 本地文件头（30 字节 + 文件名）
    const local = new Uint8Array(30 + nameBytes.length)
    const lv = new DataView(local.buffer)
    lv.setUint32(0, 0x04034b50, true) // 本地文件头签名
    lv.setUint16(4, 20, true) // version needed
    lv.setUint16(6, FLAG_UTF8, true) // flags
    lv.setUint16(8, 0, true) // method: 0 = stored
    lv.setUint16(10, DOS_TIME, true)
    lv.setUint16(12, DOS_DATE, true)
    lv.setUint32(14, crc, true)
    lv.setUint32(18, data.length, true) // compressed size
    lv.setUint32(22, data.length, true) // uncompressed size
    lv.setUint16(26, nameBytes.length, true)
    lv.setUint16(28, 0, true) // extra length
    local.set(nameBytes, 30)

    localParts.push(local)
    localParts.push(data)

    // 中央目录头（46 字节 + 文件名）
    const central = new Uint8Array(46 + nameBytes.length)
    const cv = new DataView(central.buffer)
    cv.setUint32(0, 0x02014b50, true) // 中央目录头签名
    cv.setUint16(4, 20, true) // version made by
    cv.setUint16(6, 20, true) // version needed
    cv.setUint16(8, FLAG_UTF8, true) // flags
    cv.setUint16(10, 0, true) // method
    cv.setUint16(12, DOS_TIME, true)
    cv.setUint16(14, DOS_DATE, true)
    cv.setUint32(16, crc, true)
    cv.setUint32(20, data.length, true)
    cv.setUint32(24, data.length, true)
    cv.setUint16(28, nameBytes.length, true)
    cv.setUint16(30, 0, true) // extra length
    cv.setUint16(32, 0, true) // comment length
    cv.setUint16(34, 0, true) // disk number start
    cv.setUint16(36, 0, true) // internal attrs
    cv.setUint32(38, 0, true) // external attrs
    cv.setUint32(42, offset, true) // 本地文件头偏移
    central.set(nameBytes, 46)

    centralParts.push(central)

    offset += local.length + data.length
  }

  const centralSize = centralParts.reduce((sum, p) => sum + p.length, 0)
  const centralOffset = offset

  // 中央目录结束记录（22 字节）
  const end = new Uint8Array(22)
  const ev = new DataView(end.buffer)
  ev.setUint32(0, 0x06054b50, true) // EOCD 签名
  ev.setUint16(4, 0, true) // disk number
  ev.setUint16(6, 0, true) // disk with central dir
  ev.setUint16(8, entries.length, true) // entries this disk
  ev.setUint16(10, entries.length, true) // total entries
  ev.setUint32(12, centralSize, true) // central dir size
  ev.setUint32(16, centralOffset, true) // central dir offset
  ev.setUint16(20, 0, true) // comment length

  return new Blob([...localParts, ...centralParts, end], { type: 'application/zip' })
}
