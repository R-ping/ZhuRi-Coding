import request from '@/common/reward_request'

export const getDashboard = () => {
  return request.get('/api/v1/lottery/dashboard')
}

export const doDraw = (type, useFree) => {
  return request.post('/api/v1/lottery/draw', { type, useFree })
}

export const claimPhysical = (data) => {
  return request.post('/api/v1/lottery/claim-physical', data)
}

export const getMyPrizes = (params) => {
  return request.get('/api/v1/lottery/my-prizes', { params })
}

export const getPhysicalOrderDetail = (orderId) => {
  return request.get(`/api/v1/lottery/physical-order/${orderId}`)
}

export const getBroadcast = () => {
  return request.get('/api/v1/lottery/broadcast/recent')
}

/** 我的虚拟道具（按 itemCode 聚合持有数量，来源抽奖入账） */
export const getMyVirtualAssets = () => {
  return request.get('/api/v1/virtual-assets')
}