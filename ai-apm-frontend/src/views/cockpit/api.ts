import http from '@/utils/axios'

const makeOverviewMockData = () => {
  const field = ['business', 'service', 'tech', 'docker', 'k8s', 'host', 'process'];
  const data: any = {};
  field.forEach(item => {
    data[item] = {
      pct1: Number((Math.random() * 2 - 1).toFixed(3)),
      pct2: Number((Math.random() * 2 - 1).toFixed(3)),
      trend: Array.from({ length: 30 }).map((_, index) => ({ key: index, value: Math.ceil(Math.random() * 100) })),
    };
  });
  return data;
}

export default {
  getOverview (data: any) {
    return Promise.resolve({
      status: 200,
      message: 'SUCCESS',
      data: makeOverviewMockData(),
    })
  },
  getEntityData (data: any = {}) {
    return http.request({
      url: '/cockpit/workbench/getEntityDataCount',
      method: 'post',
      data,
    })
  },
  getAlarmData (data: any = {}) {
    return http.request({
      url: '/cockpit/workbench/getAlarmCount',
      method: 'post',
      data,
    })
  },
  getEntityAlarmList (data: any = {}) {
    return http.post('/cockpit/alarm/getEntityAlarmList', data)
  },
  /** 按数据块拆分 - 摘要块（统计与环比，不含列表） */
  getEntityAlarmSummary (data: any = {}) {
    return http.post('/cockpit/alarm/getEntityAlarmSummary', data)
  },
  /** 按数据块拆分 - 服务信息块分页（默认 100/页） */
  getEntityAlarmPage (data: any = {}) {
    return http.post('/cockpit/alarm/getEntityAlarmPage', data)
  },
}
