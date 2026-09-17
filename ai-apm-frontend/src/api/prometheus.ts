import http from '../utils/axios'

export interface CvmMetricQueryParams {
  serviceId: string
  instanceIp: string
  start: number
  end: number
  interval: number
}

export default {
  queryCvmMetrics (data: CvmMetricQueryParams) {
    return http.request({
      url: '/prometheus/cvm/metrics',
      method: 'post',
      data,
    })
  },
  getStatus () {
    return http.request({
      url: '/prometheus/status',
      method: 'get',
    })
  },
}
