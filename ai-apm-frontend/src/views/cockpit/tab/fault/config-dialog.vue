<template>
  <el-drawer
    :visible.sync="showDialog"
    :title="$t('modules.views.deployInstall.apm.s_224e2ccd')"
    size="596px"
    :before-close="() => cancelHandle()"
    :wrapper-closable="!postLoading"
    :close-on-press-escape="!postLoading"
    :show-close="!postLoading"
    destroy-on-close
    class="fault-config-drawer">
    <el-form
      ref="settingForm" :model="settingForm" :rules="settingRules"
      @submit.native.prevent
      size="small" label-position="left" label-width="128px"
      class="drawer-content">
      <div class="font-14 lh-22 fw-500 mb-10">{{ $t('modules.views.cockpit.tab.s_a9d130b5') }}</div>

      <el-form-item :label="$t('modules.views.cockpit.tab.s_cf4fa105')" prop="showServiceNumber" :show-message="false" class="mb-20">
        {{ $t('modules.views.cockpit.tab.s_f129d6f5') }}
        <el-input-number
          v-model="settingForm.showServiceNumber"
          size="small" :controls="false" :min="3" :max="50" :precision="0"
          class="input-number" />
        {{ $t('modules.views.cockpit.tab.s_d8934d1b') }}
      </el-form-item>

      <el-form-item :label="$t('modules.views.cockpit.tab.s_e745e86e')" prop="red" :show-message="false" class="mb-8">
        <el-input
          v-model="settingForm.red"
          @change="updateInputHandle($event, 'red')"
          class="style-config-input">
          <template slot="prepend">
            <span class="db-icon-alarm-fill font-16 mr-6" data-type="high"></span>
            <span class="font-13 default-text">>=</span>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="" prop="yellow" :show-message="false" class="mb-8">
        <el-input
          v-model="settingForm.yellow"
          @change="updateInputHandle($event, 'yellow')"
          class="style-config-input">
          <template slot="prepend">
            <span class="db-icon-alarm-fill font-16 mr-6" data-type="low"></span>
            <span class="font-13 default-text">>=</span>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="" :show-message="false" class="mb-20">
        <div class="style-config-extra">
          <span class="db-icon-alarm-fill font-16 mr-6" data-type="normal"></span>
          <span class="font-13 default-text">{{ $t('modules.views.observe.scene.s_0d98c747') }}</span>
        </div>
      </el-form-item>

      <div class="font-14 lh-22 fw-500 mb-10">长连接服务</div>
      <el-form-item label="服务列表" :show-message="false" class="mb-20">
        <el-input
          v-model="settingForm.longConnServices"
          type="textarea" :rows="3"
          placeholder="逗号分隔，如：livechat-im-server,cloudsearch6-control-monitorservice"
          class="long-conn-input" />
        <div class="font-12" style="color:var(--color-text-secondary);line-height:18px;">
          配置的服务不参与异常服务数与慢调用统计（适用于 IM、监控轮询等天然长耗时服务）
        </div>
      </el-form-item>
    </el-form>

    <div class="drawer-footer pt-12">
      <el-button size="small" type="primary" :loading="postLoading" @click="saveHandle">{{ $t('modules.views.configInstall.plugin.s_be5fbbe3') }}</el-button>
      <el-button size="small" :disabled="postLoading" @click="cancelHandle()">{{ $t('modules.views.aiPlatform.experts.s_625fb26b') }}</el-button>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import { Form } from 'element-ui';
import { toAsyncWait } from '@/utils/common';
import ServiceApi from '@/api/service';
import { FAULT_HEALTH_DEFAULTS, getFaultDefaultThresholds } from './fault-config';

@Component
export default class ConfigDialog extends Vue {
  @Prop() private value!: boolean;
  @Prop({ default: () => ({}) }) private config!: any;
  @Prop({ default: false }) private loading!: boolean;

  @Watch('value')
  private onShowChange (newVal: boolean) {
    this.showDialog = newVal
    if (this.showDialog && !this.loading) {
      this.initForm()
    }
  }

  @Watch('loading')
  private onLoadingChange (newVal: boolean) {
    if (this.showDialog && !this.loading) {
      this.initForm()
    }
  }

  @Watch('config', { deep: true })
  private onConfigChange () {
    if (this.showDialog && !this.loading) {
      this.initForm()
    }
  }

  public $refs!: {
    settingForm: Form
  }

  private showDialog = false;
  private postLoading = false;

  get type () {
    return this.config.type || 'alarm'
  }

  private get defaultThresholds () {
    return getFaultDefaultThresholds(this.type)
  }

  private settingForm: any = {
    showServiceNumber: FAULT_HEALTH_DEFAULTS.showServiceNumber,
    red: FAULT_HEALTH_DEFAULTS.alarm.red,
    yellow: FAULT_HEALTH_DEFAULTS.alarm.yellow,
    longConnServices: '',
  }
  get settingRules () {
    return {
      showServiceNumber: { required: true, trigger: 'blur', message: i18n.t('modules.views.cockpit.tab.s_cd942f1c') as string, messageKey: 'modules.views.cockpit.tab.s_cd942f1c', type: 'number' },
      red: { required: true, trigger: 'blur', message: i18n.t('modules.views.cockpit.tab.s_3bc03e0c') as string, messageKey: 'modules.views.cockpit.tab.s_3bc03e0c', type: 'number' },
      yellow: { required: true, trigger: 'blur', message: i18n.t('modules.views.cockpit.tab.s_3bc03e0c') as string, messageKey: 'modules.views.cockpit.tab.s_3bc03e0c', type: 'number' },
    }
  }

  private initForm () {
    const defaults = this.defaultThresholds
    const typeCfg = this.config[this.type]
    this.settingForm.showServiceNumber = this.config.showServiceNumber ?? FAULT_HEALTH_DEFAULTS.showServiceNumber
    const longConn = this.config.longConnServices
    this.settingForm.longConnServices = Array.isArray(longConn) ? longConn.join(',') : (longConn || '')
    if (typeCfg && typeCfg.red != null && typeCfg.yellow != null) {
      this.settingForm.red = typeCfg.red
      this.settingForm.yellow = typeCfg.yellow
    } else if (this.config.red != null && this.config.yellow != null) {
      this.settingForm.red = this.config.red
      this.settingForm.yellow = this.config.yellow
    } else {
      this.settingForm.red = defaults.red
      this.settingForm.yellow = defaults.yellow
    }
  }

  private saveHandle () {
    // 阈值在 validate 前归一为数字：initForm 从配置读回的是 number，规则按 number 校验，
    // 否则 async-validator 报 "red is not a string" 并静默拦截保存
    this.settingForm.red = +this.settingForm.red || 0
    this.settingForm.yellow = +this.settingForm.yellow || 0
    this.$refs.settingForm.validate(async (valid: boolean) => {
      if (valid) {
        const params = {
          ...this.settingForm,
          longConnServices: (this.settingForm.longConnServices || '')
            .split(/[,，]/).map((s: string) => s.trim()).filter(Boolean).join(','),
          type: this.type,
        }
        this.postLoading = true
        const { result, error } = await toAsyncWait(ServiceApi.setHealthConfig(params))
        this.postLoading = false
        if (!error) {
          this.$message.success(i18n.t('modules.views.appMonitor.service.s_55aa6366') as string);
          const payload = { ...params };
          payload[payload.type] = {
            red: params.red,
            yellow: params.yellow,
          }
          delete payload.red
          delete payload.yellow
          this.cancelHandle({ ...payload })
        } else if (error.message !== 'interrupt') {
          this.$message.error(error.message || i18n.t('modules.views.cockpit.tab.s_6de920b4') as string);
        }
      }
    })
  }

  private cancelHandle (payload?: any) {
    this.postLoading = false
    this.settingForm = {
      showServiceNumber: FAULT_HEALTH_DEFAULTS.showServiceNumber,
      ...this.defaultThresholds,
    }
    this.$refs.settingForm.resetFields()
    this.showDialog = false
    this.$emit('on-close', payload);
    this.$emit('input', false);
  }

  // 输入框输入限制
  private updateInputHandle (val: string, key: string) {
    val = val.trim()
    const value = val === '' || isNaN(Number(val)) ? '' : `${val}`
    this.settingForm[key] = value.split('.')[0]
  }
}
</script>

<style lang="scss" scoped>
.fault-config-drawer {
  .mb-20 {
    margin-bottom: 20px !important;
  }
  .mb-8 {
    margin-bottom: 8px !important;
  }
  .mb-10 {
    margin-bottom: 10px !important;
  }

  :deep(.el-form-item__label::before) {
    display: none;
  }

  .input-number {
    margin: 0 4px;
    width: 64px;
    :deep(.el-input__inner) {
      padding-left: 10px;
      padding-right: 10px;
      text-align: left;
    }
  }

  span[data-type="high"] {
    color: #E12828;
  }
  span[data-type="low"] {
    color: #F1C42E;
  }
  span[data-type="normal"] {
    color: #08BE7E;
  }

  .style-config-input {
    width: 240px;
    :deep(.el-input-group__prepend) {
      padding-left: 11px;
      padding-right: 11px;
    }
    :deep(.el-input__inner) {
      padding-left: 10px;
      padding-right: 10px;
    }
  }

  .style-config-extra {
    width: 240px;
    height: 32px;
    padding: 0 11px;
    border: 1px solid var(--border-color-base);
    border-radius: 4px;
    background-color: var(--bg-color03);
    display: flex;
    align-items: center;
    line-height: 30px;
  }
}
</style>
