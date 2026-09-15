<template>
  <div class="relation-cont">
    <div class="service-chain-info flex-h">
      <div class="prev-service flex-v" v-if='prevServices.length'>
        <div class="prev-service-wrapper flex-v">
          <div v-for='(value,key) in prevServiceTypeList' :key='key'
            @click="showServiceTypeList('prev', key)"
            :data-type="key"
            :class="['service-card-item', serviceListType === 'prev' && prevServiceListType === key ? 'active' : '']">
            <p>{{ value }}</p>
            <p class="ell">{{ key | serviceRequestTypeFilter }}</p>
            <span :class='["db-icon"]'>{{ getTypeIcon(key) }}</span>
          </div>
        </div>
      </div>
      <div class="service-chain-arrow" v-if='prevServices.length'></div>
      <div class="curr-service flex-v">
        <div class="request-overview mb-10">
          <div class="request-overview-info font-13 fw-500">
            <span class="mr-5">{{ avgRequest | NumberFilter }} req/s</span>
            <span class="">{{ $t('modules.views.appMonitor.resourceDetail.s_60d035cc') }}</span>
          </div>
          <div class="request-overview-chart">
            <ellipse-chart
              v-if='showEllipseChart'
              :source='requestCountSource'
              :colors="['#5582FF']"
            />
          </div>
        </div>
        <div :class="['curr-service-info cp', serviceListType === 'curr' ? 'active' : '']"
          @click="showServiceTypeList('curr')">
          <span class="db-icon mr-10">{{ getServiceType | DbIconFilter }}</span>
          <p>{{ currServices.length }}</p>
          <p class="current-service-label">{{ getServiceOriginType | ServiceTypeFilter }}</p>
        </div>
      </div>
      <div class="service-chain-arrow" v-if='nextServices.length'></div>
      <div class="next-service" v-if='nextServices.length'>
        <div class="next-service-wrapper flex-v">
          <div v-for='(value,key) in nextServiceTypeList' :key='key'
            @click="showServiceTypeList('next', key)"
            :data-type="key"
            :class="['next-service-item service-card-item', serviceListType === 'next' && nextServiceListType === key ? 'active' : '']">
            <p>{{ value }}</p>
            <p class="ell">{{ key | serviceRequestTypeFilter }}</p>
            <span :class='["db-icon"]'>{{ getTypeIcon(key) }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="service-list-info">
      <db-table
        :data='serviceTypeList'
        :columnConfig='getColumnConfig'
        tableKey='APM_SERVICE_DETAIL_LIST'
        ref='listTable'>
        <template slot='service' slot-scope="{ row }">
          <div class="ell blue">
            <span class="db-icon mr-5 vm">{{ (row.typeIcon || 'default') | DbIconFilter }}</span>
            <span @click="viewOtherServiceHandle(row)" class="cphu ell">{{ row.service || '-' }}</span>
          </div>
        </template>
        <template slot='hostName' slot-scope="{ row }">
          <div v-if='!row.hostLimit'>{{ row.hostName }}</div>
          <div v-else class="db-blue cphu" @click="viewHostDetail(row)">{{ row.hostName }}</div>
        </template>
        <template slot='node' slot-scope="{ row }">
          <div v-if='!row.hostLimit'>{{ row.hostName }}</div>
          <div v-else class="db-blue cphu" @click="viewHostDetail(row)">{{ row.hostName }}</div>
        </template>
        <template slot='overallScore' slot-scope="{ row }">
          <div v-if='!row.hasData' class="process-info-item">
            <el-progress class="process-info-bar" :show-text='false' :stroke-width="6"
              :percentage="row.overallScore === null ? 0 : row.overallScore"
              :status="row.overallScore < 60 ? 'exception' : row.overallScore < 90 ? 'warning' : 'success'"></el-progress>
            <span class="process-info-label">{{ row.overallScore || '-' }}</span>
          </div>
          <div v-else class="describe tc">{{ $t('modules.views.appMonitor.serviceDetail.s_b641b4d8') }}</div>
        </template>
        <!-- <template slot='containerName' slot-scope="{ row }">
          <div>{{ row.containerName }}</div>
        </template>
        <template slot='pname' slot-scope="{ row }">
          <div>{{ row.pname }}</div>
        </template> -->
        <template slot="suffix" v-if='serviceListType !== "curr"'>
          <el-table-column :label="$t('modules.views.aiPlatform.experts.s_2b6bc0f2')">
            <template slot-scope="{ row }">
              <span @click.stop="viewServiceCallHandle(row)" class="db-blue cphu">{{ $t('modules.views.appMonitor.resourceDetail.s_9fd2acba') }}</span>
            </template>
          </el-table-column>
        </template>
      </db-table>
    </div>
  </div>
</template>

<script lang='ts'>
import { toAsyncWait } from '@/utils/common';
import i18n from '@/i18n';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import EllipseChart from '@/views/appMonitor/serviceDetail/bar-stack.vue';
import ServiceApi from '@/api/service';
import { ServiceTypeFilter, RequestTypeFilter } from '@/utils/filters/service';
import dayjs from 'dayjs';
import { DbIconFilter } from '@/utils/filters/common';
import { StringIsEmpty } from '@/utils/common';
import { cloneDeep } from 'lodash';

const RequestTypeMapping: any = {
  'service.http': i18n.t('modules.views.appMonitor.resourceDetail.s_669262cd') as string,
  'service.rpc': i18n.t('modules.views.appMonitor.resourceDetail.s_4111408b') as string,
  'service.mq': i18n.t('modules.views.appMonitor.resourceDetail.s_82375db4') as string,
  'service.db': i18n.t('modules.views.appMonitor.resourceDetail.s_b3bf0a2d') as string,
  'service.redis': i18n.t('modules.views.appMonitor.resourceDetail.s_218e2ad9') as string,
  'service.config': i18n.t('modules.views.appMonitor.resourceDetail.s_88bdaf32') as string,
  'service.remote': i18n.t('modules.views.appMonitor.resourceDetail.s_71f31c96') as string,
  'service.other': i18n.t('modules.views.appMonitor.resourceDetail.s_b5667e29') as string,
  'service.browser': i18n.t('modules.views.appMonitor.serviceCall.s_ef367e82') as string,
  'service.ios': 'IOS',
  'service.android': 'Android',
}

const getRequestTypeAndCount = (list: any) => {
  const _types: any = {}
  const requestTypes = Object.keys(RequestTypeMapping);
  requestTypes.forEach((type: string) => {
    const count = list.filter((item: any) => item.type === type).length
    if (count) {
      _types[type] = count
    }
  });
  list.filter((item: any) => !requestTypes.includes(item.type)).forEach((item: any) => {
    _types[item.type] = (_types[item.type] || 0) + 1
  });
  return _types;
}

@Component({
  components: {
    EllipseChart,
  },
  filters: {
    serviceRequestTypeFilter (type: string) {
      const _type = ServiceTypeFilter(type)
      if (_type !== type) {
        return _type
      }
      return RequestTypeFilter(type)
    },
  }
})
export default class TabRelation extends Vue {
  @Prop({ default: {} }) private current!: any;

  @Watch('current', { immediate: true })
  private onCurrentChange (val: any, oldVal: any) {
    if (val && val?.serviceId !== oldVal?.serviceId && this.isMounted) {
      this.fetchAllData();
    }
  }

  // 监听上下游服务点击事件，重置为选中当前服务实例
  @Watch('$route.query.sid')
  private async onServiceRouteQueryChange (newSid: string, oldSid: string) {
    if (!oldSid) {
      return
    }
    this.serviceListType = 'curr';
    this.prevServiceListType = '';
    this.nextServiceListType = '';
  }
  
  private loading = {
    relation: true,
    instance: true,
    trend: true,
    list: true,
  }

  get isLoading () {
    const { relation, instance, trend, list } = this.loading;
    return relation || instance || trend || list;
  }

  @Watch('isLoading')
  private onIsLoading (newVal: boolean) {
    if (!newVal) {
      this.$emit('on-loaded')
    }
  }

  private isMounted = false;


  // prev curr next
  private serviceListType: 'curr'|'prev'|'next'|'' = 'curr';
  private prevServices: any[] = [];
  private prevServiceListType = '';
  private currServices: any[] = [];
  private nextServiceListType = '';
  private nextServices: any[] = [];


  // 近一小时趋势图
  private requestCountSource: any[] = [];

  private serviceTypeListModel = true;

  // 初始化完上下游的数据后再控制缩略图显示
  private showEllipseChart = false;
  // 当前服务平均请求
  private avgRequest = 0;

  get prevServiceTypeList () {
    return getRequestTypeAndCount(this.prevServices)
  }
  get nextServiceTypeList () {
    return getRequestTypeAndCount(this.nextServices)
  }

  get getServiceOriginType () {
    return this.current?.service_type || '-'
  }
  get getServiceType () {
    return this.current?.type || this.current?.language || this.current?.service_type || '-'
  }

  get serviceTypeList () {
    switch (this.serviceListType) {
      case 'prev':
        return this.prevServices.filter((item: any) => item.type === this.prevServiceListType);
      case 'curr':
        return this.currServices;
      case 'next':
        return this.nextServices.filter((item: any) => item.type === this.nextServiceListType);
      default:
        return [];
    }
  }

  // 获取服务列表columns
  get getColumnConfig () {
    switch (this.serviceListType) {
      case 'prev':
        if (['service.browser', 'service.ios', 'service.android', 'browser', 'ios', 'android'].includes(this.prevServiceListType)) {
          return [
            { field: 'service', label: i18n.t('modules.views.appMonitor.serviceDetail.s_27c3862a') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_27c3862a', minWidth: 120 },
            { field: 'reqOutCnt', label: i18n.t('modules.views.appMonitor.serviceDetail.s_00a7b43a') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_00a7b43a', minWidth: 120, unit: 'count' },
            { field: 'reqInCnt', label: i18n.t('modules.views.appMonitor.serviceDetail.s_772bd676') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_772bd676', minWidth: 120, unit: 'count' },
            { field: 'overallScore', label: i18n.t('modules.views.appMonitor.serviceDetail.s_ec0d6d16') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_ec0d6d16', slot: 'overallScore', width: 150, },
          ]
        } else {
          const prevColumns: any[] = [
            { field: 'service', label: i18n.t('modules.views.appMonitor.serviceDetail.s_309c20d5') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_309c20d5', slot: 'service', minWidth: 120 },
            { field: 'reqOutCnt', label: i18n.t('modules.views.appMonitor.serviceDetail.s_00a7b43a') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_00a7b43a', minWidth: 120, unit: 'count' },
            { field: 'reqInCnt', label: i18n.t('modules.views.appMonitor.serviceDetail.s_772bd676') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_772bd676', minWidth: 120, unit: 'count' },
          ];
          if (['service.http', 'service.rpc'].includes(this.prevServiceListType)) {
            prevColumns.push({ field: 'duration', label: i18n.t('modules.views.appMonitor.serviceDetail.s_26fd5af0') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_26fd5af0', minWidth: 120, unit: 'ns', describe: i18n.t('modules.views.appMonitor.serviceDetail.s_116681d9') as string, describeKey: 'modules.views.appMonitor.serviceDetail.s_116681d9' })
          }
          prevColumns.push({ field: 'errType', label: i18n.t('modules.views.appMonitor.cache.s_fb844b8b') as string, labelKey: 'modules.views.appMonitor.cache.s_fb844b8b', minWidth: 120, type: 'healthStatus' })
          return prevColumns;
        }
      case 'curr':
        const currColumns: any[] = [
          { field: 'serviceInstance', label: i18n.t('modules.views.appMonitor.serviceDetail.s_8124816e') as string, labelKey: 'modules.views.appMonitor.errors.s_8124816e', minWidth: 120, handleClick: this.viewServiceInstanceHandle },
          { field: 'serviceCall', label: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string, labelKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60', minWidth: 120, unit: 'count' },
        ];
        if (this.currServices.some(t =>
          (t.activeSize !== null && t.activeSize !== undefined)
          || (t.idleSize !== null && t.idleSize !== undefined)
          || (t.maxSize !== null && t.maxSize !== undefined)
        )) {
          currColumns.push({ field: 'activeSize', label: '最大活跃数', minWidth: 120, unit: 'count' })
          currColumns.push({ field: 'idleSize', label: '最大空闲数', minWidth: 120, unit: 'count' })
          currColumns.push({ field: 'maxSize', label: i18n.t('modules.views.appMonitor.serviceDetail.s_8914ac3b') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_8914ac3b', minWidth: 120, unit: 'count' })
        }
        if (!!this.currServices.find(t => t.k8sClusterId)) {
          currColumns.push({ field: 'hostName', label: 'Node', slot: 'node', minWidth: 120, handleClick: this.viewHostDetail })
          currColumns.push({ field: 'k8sPodName', label: 'Pod', minWidth: 120 })
          currColumns.push({ field: 'k8sNamespace', label: 'Namespace', minWidth: 120 })
        } else if (!!this.currServices.find(t => t.containerId)) {
          currColumns.push({ field: 'hostName', label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, labelKey: 'modules.views.alarmCenter.alarm.s_65227369', slot: 'hostName', minWidth: 120, handleClick: this.viewHostDetail })
          currColumns.push({ field: 'containerName', label: i18n.t('modules.views.appMonitor.serviceDetail.s_22c79904') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_22c79904', minWidth: 120, handleClick: this.viewContainerDetail })
        } else {
          currColumns.push({ field: 'hostName', label: i18n.t('modules.views.alarmCenter.alarm.s_65227369') as string, labelKey: 'modules.views.alarmCenter.alarm.s_65227369', slot: 'hostName', minWidth: 120, handleClick: this.viewHostDetail })
          currColumns.push({ field: 'pname', label: i18n.t('modules.views.alarmCenter.alarm.s_f88522cf') as string, labelKey: 'modules.views.alarmCenter.alarm.s_f88522cf', minWidth: 120, handleClick: this.viewProcessDetail })
        }
        currColumns.push({ field: 'errType', label: i18n.t('modules.views.appMonitor.cache.s_fb844b8b') as string, labelKey: 'modules.views.appMonitor.cache.s_fb844b8b', minWidth: 120, type: 'healthStatus' })
        return currColumns;
      case 'next':
        const nextColumns: any[] = [
          { field: 'service', label: i18n.t('modules.views.appMonitor.serviceDetail.s_dae7f0ae') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_dae7f0ae', slot: 'service', minWidth: 120 },
          { field: 'reqInCnt', label: i18n.t('modules.views.appMonitor.serviceCall.s_e4797519') as string, labelKey: 'modules.views.appMonitor.serviceCall.s_e4797519', minWidth: 120, unit: 'count' },
          { field: 'reqOutCnt', label: i18n.t('modules.views.appMonitor.serviceDetail.s_59fa446c') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_59fa446c', minWidth: 120, unit: 'count' },
        ];
        if (['service.http', 'service.rpc'].includes(this.nextServiceListType)) {
          nextColumns.push({ field: 'duration', label: i18n.t('modules.views.appMonitor.serviceDetail.s_26fd5af0') as string, labelKey: 'modules.views.appMonitor.serviceDetail.s_26fd5af0', minWidth: 120, unit: 'ns', describe: i18n.t('modules.views.appMonitor.serviceDetail.s_116681d9') as string, describeKey: 'modules.views.appMonitor.serviceDetail.s_116681d9' })
        }
        nextColumns.push({ field: 'errType', label: i18n.t('modules.views.appMonitor.cache.s_fb844b8b') as string, labelKey: 'modules.views.appMonitor.cache.s_fb844b8b', minWidth: 120, type: 'healthStatus' })
        return nextColumns;
      default:
        return [];
    }
  }


  private created () {
    this.$emit('on-created');
    const { group: routeGroup = 'curr', gtype: routeGtype = '' } = this.$route.query;
    this.showServiceTypeList((String(routeGroup) as any) || 'curr', routeGtype as any || '')
  }
  private async mounted () {
    if (this.current?.serviceId ) {
      this.refresh();
    }
    this.isMounted = true;
  }

  public refresh () {
    this.fetchAllData();
  }

  private async fetchAllData () {
    this.fetchServiceRelate();
    this.fetchServiceInstance();
    this.fetchRequestEllipseSource();
  }

  private async fetchServiceRelate () {
    this.loading.relation = true;
    this.loading.list = true;
    const { fromTime, toTime } = this.getGlobalTimeV2();
    const params = {
      start: new Date(fromTime).valueOf(),
      end: new Date(toTime).valueOf(),
      serviceId: this.current?.serviceId
    }
    const { result, error } = await toAsyncWait(ServiceApi.getServiceRelate(params));
    const basicServiceMap = this.$store.getters['Service/basicServiceMap']

    if (!error) {
      const { data = {} } = result;
      const { reqCnt = 0, upflowServiceStats = [], downflowServiceStats = [], serviceId2Name = [] } = data
      this.avgRequest = reqCnt || 0;
      const getServices = (list: any[]) => {
        return list.filter((item: any) => item.serviceId !== 'user').map((item: any) => {
          const match = serviceId2Name.find((_obj: any) => _obj.serviceId === item.serviceId) || {}
          const outTime = item.reqOutCnt ? (item.reqOutTime || 0) / item.reqOutCnt : 0
          const inTime = item.reqInCnt ? (item.reqInTime || 0) / item.reqInCnt : 0
          const duration = outTime - inTime
          const isComponentType = ['web', 'custom'].includes(this.getServiceOriginType) && ['web', 'custom'].includes(match.serviceType)
          const basicService = basicServiceMap[item.serviceId] || {}
          return {
            ...(match || {}),
            ...item,
            duration: (duration > 0 ? duration : 0) || '-',
            service: match.serviceName,
            type: isComponentType ? item.componentType || '' : match.serviceType,
            serviceType: match.serviceType,
            typeIcon: basicService.type || basicService.language || basicService.service_type,
            errType: match.alarmCount > 0 ? 1 : 0
          }
        });
      }
      this.prevServices = getServices(upflowServiceStats);
      this.nextServices = getServices(downflowServiceStats);
    }
    this.$nextTick(() => {
      this.showEllipseChart = true;
      this.loading.relation = false;
      this.loading.list = false;
    })
  }


  // 查询服务实力列表
  private async fetchServiceInstance () {
    this.loading.instance = true
    const { fromTime, toTime } = this.getGlobalTimeV2();
    const params = {
      start: new Date(fromTime).valueOf(),
      end: new Date(toTime).valueOf(),
      serviceId: this.current?.serviceId
    }
    const { result, error } = await toAsyncWait(ServiceApi.getServiceInstance(params));
    if (!error) {
      const { data = [] } = result || {};
      this.currServices = data.map((t: any) => {
        const datasource = String(t?.traceServiceEntity?.datasource || '').toLowerCase();
        return {
          ...t,
          hostLimit: datasource.indexOf('df-') === 0 || datasource === 'databuff',
          hostIp: t.hostIp !== 'unknown' ? t.hostIp : '',
          hostName: t.hostName !== 'unknown' ? t.hostName : '',
          errType: t.alarmCount > 0 ? 1 : 0,
        }
      });
    }
    this.loading.instance = false
  }

  // 获取请求趋势缩略图
  private async fetchRequestEllipseSource (params: any = {}) {
    this.loading.trend = true
    const serviceId = this.current?.serviceId;
    const _params: any = {
      serviceId,
      // serviceInstance: params.si || this.current.serviceInstance,
      startTime: dayjs(new Date( new Date().valueOf() - 1000 * 3600 )).format('YYYY-MM-DD HH:mm:ss'),
      endTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
      interval: 60,
      metric: 'reqCount'
    }
    const { result, error } = await toAsyncWait(ServiceApi.getServiceRequestMetric(_params))
    if (!error) {
      const values: any[] = (result?.data || [])[0]?.values || []
      this.requestCountSource = [{
        name: i18n.t('modules.views.appMonitor.relationMap.s_ae1e7b60') as string, nameKey: 'modules.views.appMonitor.relationMap.s_ae1e7b60',
        data: values.map(([timestamp, value]) => ({
          key: dayjs(Number(timestamp)).format('YYYY-MM-DD HH:mm:ss'),
          value
        }))
      }]
    }
    this.loading.trend = false
  }

  private getTypeIcon (type: string) {
    let _type = ServiceTypeFilter(type);
    if (_type !== type) {
      _type = type;
    } else {
      _type = RequestTypeFilter(type);
    }
    return DbIconFilter(_type);
  }

  // 展示服务链列表
  private showServiceTypeList (group: 'curr'|'prev'|'next'|'', serviceType: string) {
    const query = { ...this.$route.query };
    const { group: routeGroup, gtype: routeGtype } = this.$route.query;
    
    if (this.serviceListType !== group) {
      this.serviceListType = group;

      if (serviceType) {
        this.nextServiceListType = serviceType;
        this.prevServiceListType = serviceType;
      }
      this.serviceTypeListModel = true;
    } else {
      if ((this.serviceListType === 'prev' && serviceType !== this.prevServiceListType) ||
        (this.serviceListType === 'next' && serviceType !== this.nextServiceListType)) {
        this.nextServiceListType = serviceType;
        this.prevServiceListType = serviceType;
        this.serviceTypeListModel = true;
      }
    }

    if (routeGroup !== group || routeGtype !== serviceType ) {
      query.group = group;
      query.gtype = serviceType;
      if (group === 'curr') {
        delete query.gtype
      }
      this.$router.replace({ query: { ...query } });
    }
    
  }

  // 查看调用分析
  private viewServiceCallHandle (row: any) {
    const query: any = {
      ...this.getRouteTimeOrRange,
      componentType: row.componentType,
    }
    if (this.serviceListType === 'prev') {
      query.srcSid = encodeURIComponent(row.serviceId)
      query.srcSn = encodeURIComponent(row.service)
      query.srcSt = row.serviceType
      query.sid = encodeURIComponent(this.current.serviceId)
      query.sn = encodeURIComponent(this.current.name || this.current.service)
      query.st = this.current.service_type
      query.serviceInstance = encodeURIComponent(this.current.serviceInstance)
    } else {
      query.srcSid = encodeURIComponent(this.current.serviceId)
      query.srcSn = encodeURIComponent(this.current.name || this.current.service)
      query.srcSt = this.current.service_type
      query.sid = encodeURIComponent(row.serviceId)
      query.sn = encodeURIComponent(row.service)
      query.st = row.serviceType
      query.srcServiceInstance = encodeURIComponent(this.current.serviceInstance)
    }
    for (const key in query) {
      if (StringIsEmpty(query[key]) || query[key] === 'undefined') {
        delete query[key]
      }
    }
    this.$router.push({
      path: '/appMonitor/serviceCall',
      query: { ...query }
    });
  }

  // 查看服务详情
  private viewOtherServiceHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceDetail',
      query: {
        ...this.getRouteTimeOrRange,
        sid: encodeURIComponent(row.serviceId),
      }
    });
  }

  // 跳转到服务实例详情
  private viewServiceInstanceHandle (row: any) {
    this.$router.push({
      path: '/appMonitor/serviceInstance',
      query: {
        ...this.getRouteTimeOrRange,
        sn: encodeURIComponent(this.current?.service || this.current?.serviceName),
        sid: encodeURIComponent(this.current?.serviceId),
        si: encodeURIComponent(row.serviceInstance),
      }
    });
  }
  // 跳转到主机详情
  private viewHostDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/hostDetail',
      query: { hostName: encodeURIComponent(row.hostName || row.host) }
    })
  }
  // 跳转到进程详情
  private viewProcessDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/processDetail',
      query: {
        processName: encodeURIComponent(row.pname),
        hostName: encodeURIComponent(row.hostName),
      }
    })
  }
  // 跳转到容器详情
  private viewContainerDetail (row: any) {
    this.$router.push({
      path: '/infrastructure/dockerDetail',
      query: {
        containerId: encodeURIComponent(row.containerId || ''),
      }
    })
  }
}
</script>
<style lang='scss' scoped>
$card-bg: #E6ECFC;
$card-active-bg: #2962FF;
$card-icon-color: #5582FF;
$card-icon-active-color: #FFFFFF;
$card-side-width: 200px;
$card-width: 280px;
$card-height: 68px;

.relation-cont {
  overflow: hidden;
  position: relative;
}
.service-chain-info {
  position: relative;
  justify-content: center;
  padding-bottom: 14px;
  height: 316px;
  transition: height .35s ease;
  .prev-service {
    flex: none;
    position: relative;
    max-height: 302px;
    overflow-y: auto;
    overflow-x: hidden;
  }
  .curr-service {
    justify-content: center;
    align-items: center;
    overflow: hidden;
    .curr-service-info {
      width: $card-width;
      height: $card-height;
      border-radius: 4px;
      line-height: 24px;
      font-size: 12px;
      background-color: $card-bg;
      transition: background-color .3s ease;
      position: relative;

      & > p:first-of-type {
        font-size: 16px;
        font-weight: 500;
        margin: 15px 0 6px 150px;
        line-height: 16px;
      }
      & > p:last-of-type {
        font-size: 13px;
        margin: 0 0 0 150px;
        line-height: 14px;
      }
      

      & > .db-icon {
        font-size: 33px;
        position: absolute;
        left: 100px;
        top: 17px;
        color: $card-icon-color;
      }

      &.active {
        color: #fff;
        background-color: $card-active-bg;
        .db-icon {
          color: $card-icon-active-color;
        }
      }

      p {
        transition: color .3s ease;
      }
    }
    .request-overview {
      background-color: $card-bg;
      border-radius: 4px;
      overflow: hidden;
      height: $card-height;
    }
    .request-overview-info {
      height: 30px;
      line-height: 30px;
      text-align: center;
    }
    .request-overview-chart {
      width: $card-width;
      height: calc( $card-height - 30px );
      padding: 0 3px 3px;
      overflow: hidden;
    }
  }
  .next-service {
    flex: none;
    position: relative;
    max-height: 302px;
    overflow-y: auto;
    overflow-x: hidden;
  }

  .service-card-item {
    width: $card-side-width;
    height: $card-height;
    border-radius: 4px;
    background-color: $card-bg;
    cursor: pointer;
    transition: background-color .3s ease;
    position: relative;

    &:not(:last-of-type) {
      margin-bottom: 10px;
    }

    & > p:first-of-type {
      font-size: 16px;
      font-weight: 500;
      margin: 15px 0 6px 108px;
      line-height: 16px;
    }
    & > p:last-of-type {
      font-size: 13px;
      margin-left: 108px;
      line-height: 14px;
    }

    & > .db-icon {
      font-size: 33px;
      position: absolute;
      left: 60px;
      top: 17px;
      color: $card-icon-color;
    }

    &.active {
      background-color: $card-active-bg;
      & > p {
        color: #fff;
      }
      & > .db-icon {
        color: $card-icon-active-color;
      }
    }

    p {
      transition: color .3s ease;
      margin: 0;
      color: var(--color-text-primary);
    }
  }
  .service-chain-arrow {
    margin: 0 20px;
    width: 0;
    height: 0;
    border-style: solid;
    border: 1px solid transparent;
    border-width: 12px 0 12px 24px;
    border-color: transparent transparent transparent #D4D4D4;
    border-radius: 4px;
  }
}
.service-list-info {
  height: 286px;
}

</style>
<style lang='scss'>
.service-list-info .process-info-item{
  display: flex;
  align-items: center;
  justify-content: flex-end;
  .process-info-label{
    flex: 1;
    text-align: right;
    padding-right: 6px;
  }
  .process-info-bar{
    width: 80px;

    .el-progress-bar__outer {
      border-radius: 1px;
      .el-progress-bar__inner{
        border-radius: 1px;
      }
    }
  }
  .el-progress.is-exception .el-progress-bar__inner {
    background: linear-gradient(270deg, #ED3B3B 0%, #ED9495 100%);
  }
  .el-progress.is-warning .el-progress-bar__inner {
    background: linear-gradient(270deg, #F99B3B 0%, #F3C596 98%);
  }
  .el-progress.is-success .el-progress-bar__inner {
    background: linear-gradient(270deg, #08BE7E 0%, #7BD7B8 100%);
  }
}
</style>
