import request from '@/common/article_request'

const API_CHANNELS = '/api/v1/channel/channels'

export const getChannels = () => {
  return request.get(API_CHANNELS)
}

export const getPostSignature = () => {
  return request({
    url: '/api/v1/media/oss/post_signature',
    method: 'get'
  })
}

export const getTagList = (keyword) => {
  return request({
    url: '/api/v1/tag/list',
    method: 'get',
    params: { keyword }
  })
}

export const getTopicList = (keyword) => {
  return request({
    url: '/api/v1/inspiration/topics',
    method: 'get',
    params: { keyword, themeType: 1, page: 1, size: 20, sort: 'hot' }
  })
}

export const importMarkdown = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request({
    url: '/api/v1/article/import',
    method: 'post',
    data: formData
  })
}