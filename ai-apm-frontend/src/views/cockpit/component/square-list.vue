<template>
  <div class="square-list">
    <el-tooltip
      v-for="(item, index) in source"
      :key="index"
      effect="light" placement="top" :visible-arrow="false" popper-class="bg-color">
      <div
        :style="{
          '--inactive': colorMap[item.level] ? colorMap[item.level].inactive : '',
          '--active': colorMap[item.level] ? colorMap[item.level].active : '',
        }"
        @click="$emit('click', item)"
        class="square-item db-icon">
        <span v-if="item.level > 0 && item[item.level] > 0" class="square-badge">{{ item[item.level] }}</span>
        <template v-if="item.type">{{ item.type | DbIconFilter }}</template>
      </div>
      <div slot="content" class="mw-200 p-6">
        <div>{{ timeStr }}</div>
        <div class="mt-8"><span class="mr-6">{{ item.type | DbIconFilter }}</span>{{ item.nameKey ? $t(item.nameKey) : item.name }}</div>
        <div v-for="level in [3, 2, 1]" :key="level" class="flex-h-jc mt-8">
          <span><i
            :style="{
              width: '6px',
              height: '6px',
              borderRadius: '1px',
              backgroundColor: colorMap[level] ? colorMap[level].active : '',
            }"
            class="dif vm mr-6"></i>{{ levelMap[level] }}</span>
          <span class="ml-30">{{ item[level] | NumberFilter }}</span>
        </div>
      </div>
    </el-tooltip>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';

type SourceItem = {
  type: string;
  level: 3 | 2 | 1;
  3?: number;
  2?: number;
  1?: number;
  [key: string]: any
};

type LevelMap = {
  [key: string]: string;
};

@Component
export default class SquareList extends Vue {
  @Prop({ default: () => [] }) private source!: SourceItem[];
  @Prop({ default: true }) private showTooltip!: boolean;
  @Prop({ default: '' }) private timeStr!: string;
  @Prop({ default: () => ({}) }) private levelNameMap!: LevelMap;

  private colorMap = {
    3: {
      inactive: 'rgba(247, 149, 141, 0.7)',
      active: '#E12828',
    },
    2: {
      inactive: 'rgba(249, 217, 142, 0.7)',
      active: '#F79532',
    },
    1: {
      inactive: 'rgba(209, 210, 213, 0.7)',
      active: '#B5B7BB',
    },
    0: {
      inactive: 'rgba(147, 227, 169, 0.7)',
      active: '#08BE7E',
    },
  }

  get levelMap (): LevelMap {
    if (this.levelNameMap && Object.keys(this.levelNameMap).length) {
      return this.levelNameMap;
    }
    return {
      3: i18n.t('modules.views.cockpit.component.s_b07ce298') as string,
      2: i18n.t('modules.views.cockpit.component.s_78f2659c') as string,
      1: i18n.t('modules.views.cockpit.component.s_6b808555') as string,
      0: i18n.t('modules.views.cockpit.component.s_3c36425f') as string,
    }
  }
}
</script>

<style lang="scss" scoped>
.square-list {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 3px;

  .square-item {
    width: 72px;
    height: 72px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #FFFFFF;
    font-size: 24px;
    cursor: pointer;
    transition: all 0.2s;
    background-color: var(--inactive);
    position: relative;

    &:hover {
      background-color: var(--active);
    }
  }

  .square-badge {
    position: absolute;
    left: 4px;
    top: 2px;
    min-width: 16px;
    height: 14px;
    line-height: 14px;
    padding: 0 3px;
    font-size: 10px;
    font-weight: 600;
    color: #fff;
    background: rgba(0, 0, 0, 0.45);
    border-radius: 2px;
    text-align: center;
    pointer-events: none;
  }
}
</style>
