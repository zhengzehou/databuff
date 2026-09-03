import http from '../utils/axios'
import { orderBy } from 'lodash';
import { AxiosPromise } from 'axios'
import * as MetricTypes from './metric.types'

const tagTypeMap: any = {
  host: '主机标签',
  service: '服务标签',
}

// 格式化指标详细数据
const formatMetricInfo = (data: any) => {
  const _data = { ...data }
  const _types: string[] = []
  _data.type1 && _types.push(_data.type1)
  _data.type1 && _data.type2 && _types.push(_data.type2)
  _data.type1 && _data.type2 && _data.type3 && _types.push(_data.type3)
  // 指标状态选项
  const _options = Object.entries(_data.fieldValue || {}).map(t => ({
    label: t[0],
    value: Number(t[1]),
  })).filter((item: any) => !isNaN(item.value))
  const _tagKeyOptions = [
    // 指标维度/分组
    ...Object.entries(_data.tagKey || {}).map(t => ({
      label: t[1],
      value: t[0],
    })),
    // 指标维度 标签key
    ...Object.entries(_data.keys || {}).map((t: any[]) => {
      return (t[1] || []).map((k: string) => ({
        label: `${k} (${tagTypeMap[t[0]] || t[0]})`,
        value: `[[${t[0]}]]${k}`,
        tagType: t[0],
      }))
    }).flat()
  ]
  return {
    ..._data,
    _types,
    _isState: !!_options.length,
    _options: orderBy(_options, ['value'], ['asc']),
    _tagKeyOptions,
    metric: _data.identifier || '',
    metricCn: _data.metricCn || _data.desc || '',
    describeCn: _data.desc || '',
  }
}

export const formatMetricInfos = (dataMap: any) => {
  const _map: any = {}
  Object.entries(dataMap || {}).forEach(([key, data]: any) => {
    _map[key] = formatMetricInfo(data || {})
  })
  return _map;
}

const unwrapResponseData = (response: any) => {
  if (response && typeof response === 'object' && Object.prototype.hasOwnProperty.call(response, 'data')) {
    return response.data
  }
  return response
}

export default {
  // 查询指标分类列表，精确查询，性能好一点
  getMetricTypes: (params?: {
    type1?: string, type2?: string, type3?: string,
    system?: boolean, builtin?: boolean,
  }): AxiosPromise => {
    return http.request({
      url: '/metrics/getMetricTypes',
      method: 'get',
      params,
    })
  },
  // 查询指标分类列表及分类下指标，分类名、指标名模糊搜索
  getMetricTypesByQuery: (data?: { typeKey?: string, metricKey?: string }): AxiosPromise => {
    return http.request({
      url: '/metrics/searchMetricTypes',
      method: 'post',
      data: data || {},
    })
  },

  // 按分类查询指标列表，只返回指标名
  getMetricList: (params?: { type1?: string, type2?: string, type3?: string }): AxiosPromise => {
    return http.request({
      url: '/metrics/findMetric',
      method: 'get',
      params,
    })
  },

  // 查询分类或主机应用下的指标，返回指标名及详细数据的mapping
  getAllMetricListByQuery: (data?: {
    type1?: string, type2?: string, type3?: string,
    host?: string, app?: string,
  }): AxiosPromise => {
    return http.request({
      url: '/metrics/searchAllMetrics',
      method: 'post',
      data
    })
  },

  // 批量查询指标的详细信息
  getMetricInfos: (params: { metrics: string[] }): AxiosPromise => {
    return http.request({
      url: `/metrics/query/in?metrics=${params.metrics.join(',')}`,
      method: 'get',
      transformResponse: [(resData) => {
        try {
          const _resData = typeof resData === 'string' ? JSON.parse(resData) : resData
          const data = unwrapResponseData(_resData) || {}
          return { ...(_resData || {}), data: formatMetricInfos(data) }
        } catch (err) {
          return resData
        }
      }]
    })
  },

  // 查询单个指标的详细信息及相关tag等数据
  getMetricDetail: (params: { metric: string }): AxiosPromise => {
    return http.request({
      url: '/metrics/detail',
      method: 'get',
      params
    })
  },

  // 批量指标查询：一次请求返回多个指标的时序（对应后端 /cockpit/metricBatch）。
  // bodies 为扁平化的指标请求体列表（与 getMetricChart 单个请求同构），
  // 返回与入参位置对齐的 List<List<series>>，每个 series 形如
  // { values:[[tsMillis, v], ...], tags:{...}, units:["time","<unit>"] }。
  metricBatch: (bodies: any[]): AxiosPromise => {
    return http.request({
      url: '/cockpit/metricBatch',
      method: 'post',
      data: bodies,
    })
  },

  // 查询单个指标趋势图数据
  getMetricChart: (_data: MetricTypes.MetricChartParams): AxiosPromise => {
    let data = { ..._data }
    // start end 转为毫秒
    if (String(data.start).length === 10 && String(data.end).length === 10) {
      data.start = data.start * 1000
      data.end = data.end * 1000
    }
    // query.A 提到最外层
    if (data.query && data.query?.A) {
      data = { ...data, ...data.query.A }
      delete data.query
    }
    return http.request({
      url: '/metrics/exploreMetricByGroupGraph',
      method: 'post',
      data,
      transformResponse: [(resData) => {
        try {
          const _resData = typeof resData === 'string' ? JSON.parse(resData) : resData
          return {
            ..._resData,
            data: (_resData.data || []).map((item: any) => ({
              ...item,
              values: (item.values || []).map((t: number[]) => {
                // 时间戳转为秒
                const time = String(t[0]).length === 13 ? Math.floor(t[0] / 1000) : t[0]
                return [time, ...t.slice(1)]
              })
            })),
          }
        } catch (err) {
          return resData
        }
      }]
    })
  },

  // 查询多指标的聚合标签
  getMetricTags: (data: any): AxiosPromise => {
    return http.request({
      url: '/metrics/listTagValues',
      method: 'post',
      data,
    })
  },

  // 查询指标的最新标签
  getMetricLastTags: (data: any): AxiosPromise => {
    return http.request({
      url: '/metrics/lastLastTagValues',
      method: 'post',
      data,
    })
  },

  // 获取单位列表
  getMetaUnits () {
    return http.request({
      url: '/meta/conf/unit',
      method: 'get',
    })
  },

  // 获取所有指标的tagKey
  getAllTagKey () {
    return http.request({
      url: '/metrics/query/tagKey/all',
      method: 'get',
    })
  },

  // 启用/停用单个指标
  toggleMetricEnable (data: { metric: string, enabled: boolean }) {
    return http.request({
      url: `/metrics/core/${data.enabled ? 'enable' : 'disable'}/${data.metric}`,
      method: 'put',
    })
  },

  // 删除单个指标
  deleteMetric (data: { metric: string }) {
    return http.request({
      url: `/metrics/core/${data.metric}`,
      method: 'delete',
    })
  },

  // 编辑指标分类
  updateMetricTypes (data: {
    type1: string, oldType1?: string,
    type2?: string, oldType2?: string,
    type3?: string, oldType3?: string,
  }) {
    return http.request({
      url: '/metrics/core/directory/update',
      method: 'put',
      data,
    })
  },

  // 删除分类下指标
  deleteMetricsByTypes (data: { type1: string, type2?: string, type3?: string }) {
    return http.request({
      url: '/metrics/core/directory',
      method: 'delete',
      data,
    })
  },

  // 根据ID获取指标核心数据
  getMetricCoreDetail (params: { id: number }) {
    return http.request({
      url: `/metrics/core/${params.id}`,
      method: 'get',
    })
  },

  // 创建/编辑指标核心数据
  saveMetricCore (data: any) {
    return http.request({
      url: '/metrics/core/',
      method: !data.id ? 'post' : 'put',
      data
    })
  },

  // 获取指标分类的measurement、app、database等数据
  getMetricTypeValues (params: { type1: string, type2?: string, type3?: string }) {
    return http.request({
      url: '/metrics/core/typeValues',
      method: 'get',
      params,
    })
  },

  // 查询mysql指标
  getMysqlMetricsTrend (data: { startTime: string, endTime: string, serviceId: string, interval: number }) {
    return http.post('/metrics/searchAttentionMetrics', data)
  }
}
