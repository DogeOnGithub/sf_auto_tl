import COS from 'cos-js-sdk-v5'
import api from './api'

interface CosCredentialResponse {
  cosKey: string
  tmpSecretId: string
  tmpSecretKey: string
  sessionToken: string
  startTime: number
  expiredTime: number
  bucket: string
  region: string
}

/**
 * 向后端请求 COS 临时上传凭证
 */
async function fetchCredential(fileName: string, category = 'files'): Promise<CosCredentialResponse> {
  var res = await api.post<CosCredentialResponse>('/api/cos/credential', { fileName, category })
  return res.data
}

/**
 * 前端分片直传文件到 COS
 *
 * @param file 要上传的文件
 * @param category 文件分类（files / patches）
 * @param onProgress 上传进度回调 0-100
 * @returns cosKey（COS 对象键）和 fileName（原始文件名）
 */
export async function uploadToCos(
  file: File,
  category = 'files',
  onProgress?: (percent: number) => void,
): Promise<{ cosKey: string; fileName: string }> {
  var credential = await fetchCredential(file.name, category)

  var cos = new COS({
    getAuthorization(_options, callback) {
      callback({
        TmpSecretId: credential.tmpSecretId,
        TmpSecretKey: credential.tmpSecretKey,
        SecurityToken: credential.sessionToken,
        StartTime: credential.startTime,
        ExpiredTime: credential.expiredTime,
      })
    },
  })

  return new Promise((resolve, reject) => {
    cos.uploadFile(
      {
        Bucket: credential.bucket,
        Region: credential.region,
        Key: credential.cosKey,
        Body: file,
        SliceSize: 1024 * 1024 * 5, // 5MB 分片
        onProgress: (info) => {
          if (onProgress) {
            onProgress(Math.round(info.percent * 100))
          }
        },
      },
      (err) => {
        if (err) {
          reject(new Error(err.message || 'COS 上传失败'))
        } else {
          resolve({ cosKey: credential.cosKey, fileName: file.name })
        }
      },
    )
  })
}
