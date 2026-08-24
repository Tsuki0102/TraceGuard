import { analysisApi } from '@/api'

/** 分片大小：5MB/片 */
export const CHUNK_SIZE = 5 * 1024 * 1024

/** 单文件超过该大小（10MB）走分片上传（含断点续传），小文件保持直传 */
export const CHUNK_THRESHOLD = 10 * 1024 * 1024

/** 单个分片上传最大重试次数 */
const MAX_RETRIES = 3

/** 计算文件总分片数 */
export function calcChunks(file) {
  return Math.max(1, Math.ceil(file.size / CHUNK_SIZE))
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function uploadChunkWithRetry(uploadId, index, blob) {
  let lastError = null
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      await analysisApi.chunkUpload(uploadId, index, blob)
      return
    } catch (e) {
      lastError = e
      if (attempt < MAX_RETRIES) {
        await sleep(500 * attempt)
      }
    }
  }
  throw new Error(`分片 ${index} 上传失败（已重试${MAX_RETRIES}次）：${(lastError && lastError.message) || '网络错误'}`)
}

/**
 * 大文件分片上传：init -> 逐片上传（断点续传时跳过已传分片，失败单片自动重试）-> complete 合并落库
 * @param {File} file 待上传文件
 * @param {{type:'requirement'|'code', projectId:number, resumeUploadId?:string,
 *          onProgress?:(percent:number, uploadId:string)=>void}} options
 * @returns {Promise<string>} 服务端落库路径
 */
export async function uploadInChunks(file, options) {
  const { type, projectId, resumeUploadId, onProgress } = options
  const totalChunks = calcChunks(file)
  const initRes = await analysisApi.chunkInit(file.name, totalChunks, resumeUploadId)
  const uploadId = initRes.uploadId
  const uploaded = new Set(initRes.uploadedChunks || [])

  for (let i = 0; i < totalChunks; i++) {
    if (uploaded.has(i)) {
      continue
    }
    const start = i * CHUNK_SIZE
    const blob = file.slice(start, Math.min(start + CHUNK_SIZE, file.size))
    await uploadChunkWithRetry(uploadId, i, blob)
    if (onProgress) {
      onProgress(Math.round(((i + 1) / totalChunks) * 100), uploadId)
    }
  }
  return await analysisApi.chunkComplete(uploadId, projectId, type)
}
