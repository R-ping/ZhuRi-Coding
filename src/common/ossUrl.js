/**
 * OSS 图片 URL 规范化工具
 *
 * 桶内 avatar/* 与 material/* 已授予 oss:GetObject（公共读）权限，
 * OSS 签名参数（?Expires=...&OSSAccessKeyId=...&Signature=...）不再需要，
 * 且会过期导致图片加载失败。此工具统一去掉这些签名参数，仅保留可公开访问的 URL。
 *
 * 支持纯 URL 字符串与内嵌 OSS 图片 URL 的 HTML 内容字符串。
 */

const ALIYUN_URL_REG = /(https?:\/\/[a-z0-9.\-]*aliyuncs\.com\/[^?"'\s]*)\?[^"'\s]*/gi

/**
 * 规范化单个字符串：去掉其中所有 aliyuncs.com URL 上的签名查询参数
 */
export function normalizeOssUrl(value) {
    if (typeof value !== 'string') return value
    if (value.indexOf('aliyuncs.com') === -1) return value
    return value.replace(ALIYUN_URL_REG, '$1')
}

/**
 * 递归规范化响应数据：对对象/数组/字符串中的 OSS URL 去签名参数
 */
export function normalizeResponseData(data) {
    if (data === null || data === undefined) return data
    if (typeof data === 'string') return normalizeOssUrl(data)
    if (Array.isArray(data)) {
        for (let i = 0; i < data.length; i++) {
            data[i] = normalizeResponseData(data[i])
        }
        return data
    }
    if (typeof data === 'object') {
        for (const key in data) {
            if (Object.prototype.hasOwnProperty.call(data, key)) {
                data[key] = normalizeResponseData(data[key])
            }
        }
        return data
    }
    return data
}