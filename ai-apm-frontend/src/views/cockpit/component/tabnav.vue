<template>
  <div>
    <div v-for="tab in tabs" :key="tab.value" :class='["tab tc", value === tab.value ? "active" : ""]' @click="toggleHandle(tab)">
      {{ tab.labelKey ? $t(tab.labelKey) : tab.label }}
    </div>
  </div>
</template>

<script lang="ts">import i18n from '@/i18n';

import { Vue, Component, Prop } from 'vue-property-decorator'

@Component
export default class ContComp extends Vue {
  @Prop({ required: true }) private value!: string;

  private tabs = [
    { label: '运维监控', value: 'monitor' },
    { label: i18n.t('modules.views.cockpit.component.s_97dbb162') as string, labelKey: 'modules.views.cockpit.component.s_97dbb162', value: 'fault' },
    // { label: i18n.t('modules.views.cockpit.component.s_1bb33934') as string, labelKey: 'modules.views.cockpit.component.s_1bb33934', value: 'overview' },
    { label: i18n.t('modules.views.alarmCenter.alarm.s_aa0eab9d') as string, labelKey: 'modules.views.alarmCenter.alarm.s_aa0eab9d', value: 'alarm' },
  ];

  private created() {
    const type: any = this.$route.query.type
    const tabnavs = this.tabs.map(t => t.value)
    const activeName = tabnavs.includes(type) ? type : tabnavs[0]
    this.$emit('input', activeName);
    this.$emit('change', activeName);
  }

  private toggleHandle (tab: any) {
    if (this.value !== tab.value) {
      this.$emit('input', tab.value);
      this.$emit('change', tab.value);
    }
  }
}
</script>

<style lang="scss" scoped>

.tab {
  height: 38px;
  line-height: 36px;
  border-top-left-radius: 3px;
  border-top-right-radius: 3px;
  width: 110px;
  // border-top: 2px solid #2962ff;
  border-top: 1px solid #dfe0e2;
  display: inline-block;
  margin-right: 6px;
  position: relative;
  cursor: pointer;
  transition: color .3s ease, border-color .3s ease;
  &::before,
  &::after {
    content: '';
    position: absolute;
    height: calc( 100% - 1px );
    width: 1px;
    top: 1px;
    left: 0;
    background-color: #dfe0e2;
  }
  &::after {
    left: auto;
    right: 0;
  }
  &.active {
    color: #2962ff;
    font-weight: 500;
    border-top: 2px solid #2962ff;
  }
}
</style>