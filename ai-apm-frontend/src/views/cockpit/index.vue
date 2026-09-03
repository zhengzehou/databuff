<template>
  <div class="cockpit-cont">
    <div class="cockpit-tabnav bg-color">
      <tabnav :value="activeTab" @input="onTabInput" @change="onTabChange" />
    </div>
    <div class="wrapper p-16">
      <component :is="currentComp" />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator'
import Tabnav from './component/tabnav.vue';
import Fault from './tab/fault/index.vue';
import Overview from './tab/overview.vue';
import Alarm from './tab/alarm.vue';
import Monitor from './tab/monitor.vue';

const TAB_COMPONENTS: any = {
  fault: Fault,
  overview: Overview,
  alarm: Alarm,
  monitor: Monitor,
};

@Component({
  components: {
    Tabnav,
    Fault,
    Overview,
    Alarm,
    Monitor,
  },
})
export default class Cockpit extends Vue {
  private activeTab = 'monitor';

  private get currentComp () {
    return TAB_COMPONENTS[this.activeTab] || Monitor;
  }

  private created () {
    this.syncRouteTab();
  }

  @Watch('$route.query.type')
  private syncRouteTab () {
    const type = this.$route.query.type;
    if (type && TAB_COMPONENTS[type]) {
      this.activeTab = type as string;
    }
  }

  private onTabInput (tab: string) {
    this.activeTab = tab;
  }

  private onTabChange (tab: string) {
    this.activeTab = tab;
    if (this.$route.query.type !== tab) {
      this.$router.replace({ query: { ...this.$route.query, type: tab } });
    }
  }
}
</script>

<style lang="scss" scoped>
.cockpit-cont {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.cockpit-tabnav {
  flex: none;
  padding: 0 16px;
  border-bottom: 1px solid var(--border-color-base);
}

.wrapper {
  flex: 1;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
}
</style>
