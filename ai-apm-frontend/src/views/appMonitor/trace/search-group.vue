<template>
  <el-form :inline="true" size="small" class="trace-search-form" @submit.native.prevent>
    <el-form-item label="TraceID">
      <el-input
        v-model="queryParams.traceId"
        :disabled="paramsLoading"
        clearable
        placeholder="TraceID"
        class="search-ipt"
        @change="handleFieldChange" />
    </el-form-item>

    <el-form-item :label="$t('modules.views.alarmCenter.eventDetail.s_34cab80c')">
      <el-input
        v-model="queryParams.fuzzyTraceName"
        :disabled="paramsLoading"
        clearable
        :placeholder="$t('modules.views.alarmCenter.eventDetail.s_34cab80c')"
        class="search-ipt"
        @change="handleFieldChange" />
    </el-form-item>

    <el-form-item :label="$t('modules.views.alarmCenter.alarm.s_47d68cd0')">
      <el-select
        v-model="queryParams.sid"
        :disabled="paramsLoading"
        filterable
        clearable
        :filter-method="handleServiceFilter"
        :placeholder="$t('modules.views.alarmCenter.alarm.s_47d68cd0')"
        class="search-ipt"
        @change="handleServiceChange">
        <el-option
          v-for="item in filteredServiceList"
          :key="item.value"
          :label="item.label"
          :value="item.value" />
      </el-select>
    </el-form-item>

    <el-form-item
      v-if="queryParams.sid"
      :label="$t('modules.views.alarmCenter.alarm.s_71673bab')">
      <el-select
        v-model="queryParams.si"
        :disabled="paramsLoading"
        filterable
        clearable
        :placeholder="$t('modules.views.alarmCenter.alarm.s_71673bab')"
        class="search-ipt"
        @change="handleFieldChange">
        <el-option
          v-for="item in serviceInstanceList"
          :key="item.value"
          :label="item.label"
          :value="item.value" />
      </el-select>
    </el-form-item>
  </el-form>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { StringIsEmpty } from '@/utils/common'
import { toAsyncWait } from '@/utils/common';
import ServiceApi from '@/api/service';
import dayjs from 'dayjs';

const SERVICE_OPTION_LIMIT = 100;

@Component
export default class SearchGroup extends Vue {
  @Prop({ default: () => [] }) private serviceList!: any[];
  @Prop({ default: () => ({}) }) private timeParams!: any;

  private queryParams: any = {
    traceId: '',
    fuzzyTraceName: '',
    sid: '',
    si: '',
  }

  private paramsLoading = false

  private serviceInstanceMap: any = {} // 服务的实例map

  private serviceKeyword = '' // 服务下拉的本地搜索关键字

  get serviceInstanceList (): any[] {
    return (this.serviceInstanceMap[this.queryParams.sid] || []).map((t: string) => ({
      label: t,
      value: t,
    }))
  }

  // 服务列表可能非常大，只渲染前 N 条匹配项，避免下拉卡顿
  get filteredServiceList (): any[] {
    const kw = this.serviceKeyword.trim().toLowerCase()
    const source = kw
      ? this.serviceList.filter((t: any) => String(t.label).toLowerCase().includes(kw))
      : this.serviceList
    const result = source.slice(0, SERVICE_OPTION_LIMIT)
    const sid = this.queryParams.sid
    // 保证已选服务在列表内，便于回显
    if (sid && !result.some((t: any) => t.value === sid)) {
      const selected = this.serviceList.find((t: any) => t.value === sid)
      if (selected) {
        result.unshift(selected)
      }
    }
    return result
  }

  @Watch('timeParams', { deep: true })
  private watchQueryParams () {
    this.serviceInstanceMap = {}
  }

  public async init (timeParams: any) {
    let isNeedRemoveTraceId = false;
    const timeIsOverOneDay = new Date(timeParams.toTime).valueOf() - new Date(timeParams.fromTime).valueOf() > 24 * 60 * 60 * 1000;
    if (timeIsOverOneDay && this.queryParams?.traceId) {
      this.$confirm(i18n.t('modules.views.appMonitor.trace.s_b5908b88') as string, i18n.t('modules.views.appMonitor.trace.s_6dc613a6') as string,  {
        type: 'warning',
        showCancelButton: false,
      });
      this.queryParams.traceId = '';
      const _query = { ...this.$route.query }
      delete _query.traceId
      this.$router.replace({ query: { ..._query } });
      isNeedRemoveTraceId = true;
    }
    this.paramsLoading = true;

    // 搜索参数回显
    const routerQuery = { ...this.$route.query }
    if (isNeedRemoveTraceId) {
      delete routerQuery.traceId;
    }
    Object.keys(this.queryParams).forEach(k => {
      if (routerQuery[k]) {
        this.queryParams[k] = decodeURIComponent(String(routerQuery[k]))
      }
    })
    const serviceId = this.queryParams.sid
    if (serviceId) {
      await this.getServiceInstance(serviceId)
    } else {
      this.queryParams.si = ''
    }

    this.paramsLoading = false;

    return { filter: this.buildQuery() }
  }

  private handleServiceFilter (query: string) {
    this.serviceKeyword = query || ''
  }

  private handleServiceChange () {
    // 操作服务时，清空服务实例
    this.queryParams.si = ''
    const serviceId = this.queryParams.sid
    if (serviceId && !this.serviceInstanceMap[serviceId]) {
      this.getServiceInstance(serviceId).then(() => this.handleFieldChange())
      return
    }
    this.handleFieldChange()
  }

  private handleFieldChange () {
    this.$emit('on-change', this.buildQuery(), this.buildRouterQuery())
  }

  // 构造查询参数：去除空值、TraceID 逗号分隔转 traceIds
  private buildQuery () {
    const query = { ...this.queryParams }
    Object.entries(query).forEach(([key, value]) => {
      if (StringIsEmpty(value)) {
        delete query[key]
      }
    })
    if ((query.traceId || '').includes(',')) {
      const ids = query.traceId.split(',').slice(0, 101) // 显示101条，用于判断是否超出
      query.traceIds = ids
      delete query.traceId
    }
    return query
  }

  // 同步到路由 query，供页面刷新/回显使用
  private buildRouterQuery () {
    const query: any = { ...this.$route.query }
    Object.keys(this.queryParams).forEach((key) => {
      const value = this.queryParams[key]
      if (StringIsEmpty(value)) {
        delete query[key]
      } else {
        query[key] = encodeURIComponent(String(value))
      }
    })
    return query
  }

  private async getServiceInstance (sid: string) {
    const params = {
      serviceId: sid,
      fromTime: dayjs(this.timeParams.fromTime).format('YYYY-MM-DD HH:mm:ss'),
      toTime: dayjs(this.timeParams.toTime).format('YYYY-MM-DD HH:mm:ss'),
    }
    const { result, error } = await toAsyncWait(ServiceApi.getBasicServiceInstance(params))
    if (!error) {
      const list = ((result.data || {}).serviceInstances || []).map((t: any) => t.serviceInstance)
      this.$set(this.serviceInstanceMap, sid, list);
    }
  }
}
</script>

<style lang="scss" scoped>
.trace-search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;

  :deep(.el-form-item) {
    margin-bottom: 0;
    margin-right: 16px;

    &:last-child {
      margin-right: 0;
    }
  }

  .search-ipt {
    width: 220px;
  }
}
</style>
