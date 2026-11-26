<template>
  <div class="alarm-wrapper">
    <div
      v-for="(group, index) in groupList" :key="index"
      :class="{ 'mt-20': index !== 0 }"
      v-loading="group.loading"
      class="bg-color p-16 br-4 pb-0 ovh">
      <div class="flex-h-jc mb-20">
        <span class="section-title">{{ group.nameKey ? $t(group.nameKey) : group.name }}</span>
      </div>

      <div class="pb-20 lh-26 flex-h flex-ai-end">
        <span class="mr-10">{{ group.totalName }}</span>
          <!-- @click="handleGroupClick(group.type)" -->
        <span
          class="font-32">{{ group.total | NumberFilter }}</span>

        <span class="ml-64 mr-64 grey">|</span>

        <el-tooltip
          v-for="t,i in ['important', 'secondary', 'nodata', 'noalarm']"
          :key="t"
          :class="{ 'ml-64': i !== 0 }"
          effect="light" placement="top" :visible-arrow="false" popper-class="bg-color">
          <div>
            <span
              :class="levelMap[t].color"
              class="db-icon db-icon-lamp font-16 lh-30 mr-6"></span>
            <span
              :class="levelMap[t].color"
              class="mr-10">{{ levelMap[t].name }}</span>
            <span class="font-32 mr-20">{{ group[t] | NumberFilter }}</span>
            <span :class="group[`${t}Rate`] > 0 ? 'red' : 'green'">{{ group[`${t}Rate`] | popRatioFilter }}</span>
          </div>
          <div slot="content" class="mw-200 p-6">
            <div>{{ getTimeRangeStr }}</div>
            <div class="flex-h-jc mt-8">
              <span>{{ $t('modules.views.cockpit.tab.s_3cb88418', { value0: t !== 'noalarm' ? $t('modules.views.cockpit.tab.s_dca95c85') : '', value1: levelMap[t].name, value2: group.nameKey ? $t(group.nameKey) : group.name }) }}</span>
              <span class="ml-30">{{ group[t] | NumberFilter }}</span>
            </div>
          </div>
        </el-tooltip>
      </div>

      <square-list
        :source="group.list"
        :timeStr="getTimeRangeStr"
        @click="handleClick"
        class="mb-20" />
    </div>
  </div>
</template>

<script lang="ts">import i18n from '@/i18n';

import { Vue, Component, Prop, Watch } from 'vue-property-decorator'
import SquareList from '@/views/cockpit/component/square-list.vue'
import { toAsyncWait } from '@/utils/common'
import Api from '../api'

@Component({
  components: {
    SquareList,
  },
  filters: {
    popRatioFilter (value: number) {
      if (!value && String(value) !== '0' || isNaN(+value) || !isFinite(+value)) {
        return '-'
      }
      const _val = Number(value)
      return (_val >= 0 ? '+' : '-') + `${+(Math.abs(_val) * 100).toFixed(2)}%`
    },
  }
})
export default class AlarmComp extends Vue {
  @Watch('globalTimeV2', { deep: true })
  private watchGlobalTime() {
    this.durationChangeHandle()
  }

  private queryParams: any = {
    fromTime: 0,
    toTime: 0,
    interval: 300,
  }

  private loading: boolean = false

  private groupList: any[] = [
    // {
    //   name: i18n.t('modules.views.cockpit.tab.s_5d83cfa4') as string, nameKey: 'modules.views.cockpit.tab.s_5d83cfa4',
    //   type: '',
    //   totalName: i18n.t('modules.views.cockpit.tab.s_9345f9db') as string,
    //   total: 0,
    //   important: 0,
    //   importantRate: 0,
    //   secondary: 0,
    //   secondaryRate: 0,
    //   nodata: 0,
    //   nodataRate: 0,
    //   noalarm: 0,
    //   noalarmRate: 0,
    //   list: [],
    //   loading: false,
    // },
    {
      name: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, nameKey: 'modules.views.alarmCenter.alarm.s_47d68cd0',
      type: 'SERVICE',
      totalName: i18n.t('modules.views.cockpit.tab.s_6d299a17') as string,
      total: 0,
      important: 0,
      importantRate: 0,
      secondary: 0,
      secondaryRate: 0,
      nodata: 0,
      nodataRate: 0,
      noalarm: 0,
      noalarmRate: 0,
      list: [],
      loading: false,
    },
    // {
    //   name: i18n.t('modules.views.cockpit.tab.s_6aa2fa41') as string, nameKey: 'modules.views.cockpit.tab.s_6aa2fa41',
    //   type: '',
    //   totalName: i18n.t('modules.views.cockpit.tab.s_dff7c311') as string,
    //   total: 0,
    //   important: 0,
    //   importantRate: 0,
    //   secondary: 0,
    //   secondaryRate: 0,
    //   nodata: 0,
    //   nodataRate: 0,
    //   noalarm: 0,
    //   noalarmRate: 0,
    //   list: [],
    //   loading: false,
    // },
  ]

  private levelMap: any = {
    important: {
      name: i18n.t('modules.views.alarmCenter.alarm.s_fc7e3846') as string, nameKey: 'modules.utils.filters.s_fc7e3846',
      color: 'red',
    },
    secondary: {
      name: i18n.t('modules.views.alarmCenter.alarm.s_bde77082') as string, nameKey: 'modules.utils.filters.s_bde77082',
      color: 'yellow',
    },
    nodata: {
      name: i18n.t('modules.views.alarmCenter.alarm.s_01ceb3ed') as string, nameKey: 'modules.utils.filters.s_01ceb3ed',
      color: 'describe',
    },
    noalarm: {
      name: i18n.t('modules.views.cockpit.component.s_3c36425f') as string, nameKey: 'modules.views.cockpit.component.s_3c36425f',
      color: 'green',
    },
  }

  get getBasicServiceMap () {
    return this.$store.getters['Service/basicServiceMap']
  }

  get getTimeRangeStr () {
    const { fromTime, toTime } = this.getGlobalTimeV2();
    // 如果是同一天，显示 YYYY-MM-DD HH:mm - HH:mm
    // 如果不是同一天，显示 YYYY-MM-DD HH:mm - YYYY-MM-DD HH:mm
    const fromDateStr = fromTime.slice(0, 10);
    const toDateStr = toTime.slice(0, 10);
    const fromTimeStr = fromTime.slice(11, 16);
    const toTimeStr = toTime.slice(11, 16);
    if (fromDateStr === toDateStr) {
      return `${fromDateStr} ${fromTimeStr} ～ ${toTimeStr}`;
    } else {
      return `${fromDateStr} ${fromTimeStr} ～ ${toDateStr} ${toTimeStr}`;
    }
  }

  private async mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => {
      this.durationChangeHandle()
    });
    this.durationChangeHandle()
  }

  private beforeDestroy () {
    this.$eventBus.$off('GlobalRefresh')
  }

  private durationChangeHandle () {
    const { fromTime, toTime, interval } = this.getGlobalTime();
    this.queryParams.fromTime = fromTime.valueOf();
    this.queryParams.toTime = toTime.valueOf();
    this.queryParams.interval = interval;
    this.getData();
  }

  private async getData () {
    this.groupList.forEach(async (group) => {
      const params = {
        ...this.queryParams,
        type: group.type,
      }
      group.loading = true;
      const { error, result } = await toAsyncWait(Api.getEntityAlarmList(params));
      group.loading = false;
      if (!error) {
        const data = result?.data || {};
        group.total = data.total || 0;
        group.important = data.matterDataCount || 0;
        group.secondary = data.minorDataCount || 0;
        group.nodata = data.noDataCount || 0;
        group.noalarm = data.noAlarmCount || 0;
        group.importantRate = +data.matterDataCountRate / 100;
        group.secondaryRate = +data.minorDataCountRate / 100;
        group.nodataRate = +data.noDataCountRate / 100;
        group.noalarmRate = +data.noAlarmCountRate / 100;
        group.list = (data.alarmEntityList || []).map((item: any) => {
          let typeIcon = ''
          if (params.type === 'BUSINESS') {
            typeIcon = item.type === 1 ? 'bs' : item.type === 2 ? 'subbs' : ''
          } else if (params.type === 'SERVICE') {
            const { service_type, type, language } = (this.getBasicServiceMap || {})[item?.entityId] || {}
            typeIcon = type || language || service_type || 'default'
          }
          const matter = item.matterDataCount || 0
          const minor = item.minorDataCount || 0
          const noData = item.noDataCount || 0
          return {
            group: group.type,
            name: item.entityName,
            id: item.entityId,
            type: typeIcon,
            level: matter > 0 ? 3 : minor > 0 ? 2 : noData > 0 ? 1 : 0,
            3: matter,
            2: minor,
            1: noData,
          }
        });
      }
    })
  }

  // private handleGroupClick (type: string) {
  //   if (type === 'BUSINESS') {
  //     this.$router.push('/appMonitor/businessSystem');
  //   } else if (type === 'SERVICE') {
  //     this.$router.push('/appMonitor/service');
  //   }
  // }

  private handleClick (row: any) {
    if (row.group === 'BUSINESS') {
      return;
    } else if (row.group === 'SERVICE') {
      this.$router.push({
        path: '/appMonitor/serviceDetail',
        query: {
          sid: encodeURIComponent(row.id),
          sn: encodeURIComponent(row.name),
        }
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.alarm-wrapper {
  .section-title {
    font-weight: 500;
    font-size: 14px;
    line-height: 1;
  }
  .ml-64 {
    margin-left: 64px;
  }
  .mr-64 {
    margin-right: 64px;
  }
  .font-32 {
    font-size: 32px;
    font-weight: 500;
    line-height: 36px;
  }
  .mw-200 {
    min-width: 200px;
  }
}
</style>
