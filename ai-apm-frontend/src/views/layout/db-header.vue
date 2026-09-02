<template>
  <header :class="['db-header flex-v', collapse ? 'collapse' : '']">
    <div v-if="headerIcon || (!collapse && headerWordmark)" class="logo-cont flex-h" @click="backHomeHandle">
      <div class="header-logo-row">
        <div v-if="headerIcon" class="header-logo-wrap">
          <img :src="headerIcon" alt="header-logo" class="header-logo" />
        </div>
        <img
          v-if="!collapse && headerWordmark"
          :src="headerWordmark"
          alt=""
          class="header-brand-wordmark"
        />
      </div>
    </div>

    <db-menu :collapse="collapse" menuTheme="dark" class="db-header-menu" />

    <div class="db-header-bottom">
      <div v-if="isOfficial" class="lock-info" :class="collapse ? 'lock-info-collapse' : ''">
        <i class="db-icon-lock lock-icon"></i>
        <span>{{ $t('layout.trialEdition') }}</span>
      </div>

      <user-info :collapse='collapse' />

      <div class="toggle-sidebar-collapse cp" @click="toggleCollapseHandle">
        <span v-show="!collapse" class="db-icon-fold svg-toggle cp trans"></span>
        <span v-show="collapse" class="db-icon-unfold svg-toggle cp trans"></span>
      </div>
    </div>

  </header>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import UserInfo from './user-info.vue';
import DbMenu from '@/components/db-menu/index.vue';

@Component({
  components: {
    UserInfo,
    DbMenu,
  }
})
export default class Header extends Vue {
  get headerIcon () {
    if (this.collapse) {
      return this.$store.getters['User/getLogoConfig']?.headerCollapseLogo || '';
    }
    return this.$store.getters['User/getLogoConfig']?.headerLogo || '';
  }

  get headerWordmark () {
    return import.meta.env.VITE_BASE + 'img/logo_wordmark_header.svg';
  }

  get isOfficial () {
    return location.host.indexOf('.databuff.com') !== -1
  }

  private collapse = false;
  private locked = false; // 锁定后不在触发resize

  private mounted () {
    this.resize()
    window.addEventListener('resize', this.resize);
  }

  private beforeDestroy () {
    if (!this.locked) {
      window.removeEventListener('resize', this.resize);
    }
  }

  private resize () {
    const width = document.body.clientWidth
    this.collapse = width < 1280
  }

  private toggleCollapseHandle () {
    this.collapse = !this.collapse
    if (!this.locked) {
      this.locked = true
      window.removeEventListener('resize', this.resize);
    }
  }

  private backHomeHandle () {
    this.$router.push({ path: '/', query: { __ps: 'm' } })
  }
}
</script>

<style lang="scss" scoped>
.db-header {
  flex-shrink: 0;
  width: 160px;
  height: 100%;
  padding: 8px 10px 0;
  font-size: 13px;
  z-index: 100;
  background-color: #2F313B;
  transition: width 0.3s;
  overflow-x: hidden;
  overflow-y: auto;

  .logo-cont {
    flex: none;
    height: 32px;
    align-items: center;
    padding: 0 8px;
    margin-bottom: 12px;
    will-change: background-color;
    transition: background-color 0.3s;
    border-radius: 4px;
    cursor: pointer;
    overflow: hidden;
    font-size: 0;

    &:hover {
      background: rgba(255, 255, 255, 0.08);
    }

    .header-logo-row {
      display: flex;
      align-items: center;
      gap: 8px;
      width: 100%;
      overflow: hidden;
    }

    .header-logo-wrap {
      flex: none;
      height: 20px;
      overflow: hidden;
      display: flex;
      align-items: center;
    }

    .header-logo {
      display: block;
      height: 100%;
    }

    .header-brand-wordmark {
      flex: 1;
      min-width: 0;
      height: 14px;
      width: auto;
      display: block;
    }
  }

  &.collapse {
    width: 56px;

    .header-logo-wrap {
      width: 20px;
    }
  }

  .db-header-menu {
    margin-right: -10px;
    width: calc(100% + 10px);
  }

  .db-header-bottom {
    flex: none;
    padding: 8px 0 10px;
    .toggle-sidebar-collapse {
      width: 36px;
      padding: 9px 11px;
      border-radius: 4px;

      &:hover {
        background: rgba(255, 255, 255, 0.08);
        .svg-toggle {
          color: #fff;
        }
      }
      .svg-toggle {
        display: block;
        color: #CCCED4;
        font-size: 14px;
        transition: color .3s ease;
      }
    }
    .lock-info {
      margin-bottom: 10px;
      background: rgba(255, 255, 255, 0.15);
      display: flex;
      align-items: center;
      padding: 6px 20px 6px 34px;
      height: 32px;
      border-radius: 4px;
      transition: all 0.3s;
      line-height: 1;
      text-overflow: ellipsis;
      white-space: nowrap;
      overflow: hidden;
      font-family: PingFang SC;
      font-size: 12px;
      color: #FFFFFF;
      position: relative;
      .lock-icon {
        width: 16px !important;
        height: 16px !important;
        visibility: visible !important;
        font-size: 14px;
        text-align: center;
        line-height: 16px;
        transform: translate(0, -50%);
        position: absolute;
        top: 50%;
        left: 10px;
      }
      &.lock-info-collapse {
        transition: none;
        width: 36px;
        padding-right: 0;
        span {
          display: none;
        }
      }
    }
  }
}
</style>
