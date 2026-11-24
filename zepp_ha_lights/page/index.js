import { BasePage } from '@zeppos/zml/base-page'
import * as hmUI from '@zos/ui'
import { Vibrator, VIBRATOR_SCENE_SHORT_LIGHT } from '@zos/sensor'
import { setPageBrightTime } from '@zos/display'
import { showToast } from '@zos/interaction'

const SCREEN_W = 480
const CARD_X = 40
const CARD_W = 400
const CARD_H = 88
const CARD_GAP = 16
const LIST_START_Y = 126

const COLOR_BG_ON = 0x2e2308
const COLOR_BORDER_ON = 0xffb300
const COLOR_BADGE_ON = 0xffb300
const COLOR_TEXT_ON = 0xffffff
const COLOR_BADGE_TEXT_ON = 0x111827

const COLOR_BG_OFF = 0x16202e
const COLOR_BORDER_OFF = 0x2c3e55
const COLOR_BADGE_OFF = 0x293548
const COLOR_TEXT_OFF = 0xdbe5f1
const COLOR_BADGE_TEXT_OFF = 0x94a3b8

const COLOR_BTN_REFRESH = 0x0ea5e9

Page(
  BasePage({
    state: {
      lights: [],
      loading: false,
      busyId: null,
      statusText: '連線至手機橋接中...',
      dynamicWidgets: [],
      cardRefs: {}
    },

    onInit() {
      try {
        setPageBrightTime({ brightTime: 30 })
      } catch (e) {}
      try {
        this.vibrator = new Vibrator()
      } catch (e) {
        this.vibrator = null
      }
    },

    build() {
      // Header Title
      hmUI.createWidget(hmUI.widget.TEXT, {
        x: 60,
        y: 28,
        w: SCREEN_W - 120,
        h: 44,
        color: 0xffffff,
        text_size: 32,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V,
        text: 'HA 電燈控制'
      })

      // Status Subtitle
      this.statusWidget = hmUI.createWidget(hmUI.widget.TEXT, {
        x: 40,
        y: 74,
        w: SCREEN_W - 80,
        h: 36,
        color: 0x38bdf8,
        text_size: 22,
        align_h: hmUI.align.CENTER_H,
        align_v: hmUI.align.CENTER_V,
        text: this.state.statusText
      })

      this.renderBody()
      this.fetchLights()
    },

    vibrateShort() {
      try {
        if (this.vibrator) {
          this.vibrator.stop()
          this.vibrator.setMode(VIBRATOR_SCENE_SHORT_LIGHT)
          this.vibrator.start()
        }
      } catch (e) {}
    },

    setStatus(text, color = 0x38bdf8) {
      this.state.statusText = text
      if (this.statusWidget) {
        this.statusWidget.setProperty(hmUI.prop.MORE, {
          text: text,
          color: color
        })
      }
    },

    clearDynamicWidgets() {
      if (this.state.dynamicWidgets && this.state.dynamicWidgets.length > 0) {
        for (let i = 0; i < this.state.dynamicWidgets.length; i++) {
          try {
            hmUI.deleteWidget(this.state.dynamicWidgets[i])
          } catch (e) {}
        }
      }
      this.state.dynamicWidgets = []
      this.state.cardRefs = {}
    },

    addDynamic(widget) {
      this.state.dynamicWidgets.push(widget)
      return widget
    },

    renderBody() {
      this.clearDynamicWidgets()
      const lights = this.state.lights

      let nextY = LIST_START_Y

      if (!lights || lights.length === 0) {
        const msg = this.state.loading
          ? '正在讀取 Home Assistant\n電燈清單...'
          : '尚未取得電燈清單\n\n請確認手機已開啟 Notify\n並在 NotifyBalance2Fix\n設定 HA 網址與 Token'

        this.addDynamic(
          hmUI.createWidget(hmUI.widget.TEXT, {
            x: 44,
            y: 144,
            w: SCREEN_W - 88,
            h: 190,
            color: 0x94a3b8,
            text_size: 24,
            align_h: hmUI.align.CENTER_H,
            align_v: hmUI.align.CENTER_V,
            text_style: hmUI.text_style.WRAP,
            text: msg
          })
        )
        nextY = 348
      } else {
        for (let i = 0; i < lights.length; i++) {
          const item = lights[i]
          const y = nextY
          const isOn = !!item.on
          const isBusy = this.state.busyId === item.id

          // Card border background
          const borderRect = this.addDynamic(
            hmUI.createWidget(hmUI.widget.FILL_RECT, {
              x: CARD_X,
              y: y,
              w: CARD_W,
              h: CARD_H,
              radius: 26,
              color: isOn ? COLOR_BORDER_ON : COLOR_BORDER_OFF
            })
          )

          // Card inner fill
          const innerRect = this.addDynamic(
            hmUI.createWidget(hmUI.widget.FILL_RECT, {
              x: CARD_X + 2,
              y: y + 2,
              w: CARD_W - 4,
              h: CARD_H - 4,
              radius: 24,
              color: isOn ? COLOR_BG_ON : COLOR_BG_OFF
            })
          )

          // Status indicator dot
          const dot = this.addDynamic(
            hmUI.createWidget(hmUI.widget.CIRCLE, {
              center_x: CARD_X + 34,
              center_y: y + Math.floor(CARD_H / 2),
              radius: 10,
              color: isOn ? COLOR_BORDER_ON : 0x475569
            })
          )

          // Light friendly name
          const nameText = this.addDynamic(
            hmUI.createWidget(hmUI.widget.TEXT, {
              x: CARD_X + 58,
              y: y + 10,
              w: CARD_W - 166,
              h: CARD_H - 20,
              color: isOn ? COLOR_TEXT_ON : COLOR_TEXT_OFF,
              text_size: 26,
              align_h: hmUI.align.LEFT,
              align_v: hmUI.align.CENTER_V,
              text_style: hmUI.text_style.ELLIPSIS,
              text: item.n || item.name || item.id
            })
          )

          // ON/OFF pill badge
          const badgeBg = this.addDynamic(
            hmUI.createWidget(hmUI.widget.FILL_RECT, {
              x: CARD_X + CARD_W - 96,
              y: y + 22,
              w: 78,
              h: 44,
              radius: 22,
              color: isOn ? COLOR_BADGE_ON : COLOR_BADGE_OFF
            })
          )

          const badgeText = this.addDynamic(
            hmUI.createWidget(hmUI.widget.TEXT, {
              x: CARD_X + CARD_W - 96,
              y: y + 22,
              w: 78,
              h: 44,
              color: isOn ? COLOR_BADGE_TEXT_ON : COLOR_BADGE_TEXT_OFF,
              text_size: 22,
              align_h: hmUI.align.CENTER_H,
              align_v: hmUI.align.CENTER_V,
              text: isBusy ? '...' : isOn ? '開啟' : '關閉'
            })
          )

          // Full-card touch overlay button (transparent fill / click handler)
          const hitArea = this.addDynamic(
            hmUI.createWidget(hmUI.widget.FILL_RECT, {
              x: CARD_X,
              y: y,
              w: CARD_W,
              h: CARD_H,
              radius: 26,
              color: 0x000000,
              alpha: 0
            })
          )
          hitArea.addEventListener(hmUI.event.CLICK_UP, () => {
            this.toggleLight(item)
          })

          this.state.cardRefs[item.id] = {
            borderRect,
            innerRect,
            dot,
            nameText,
            badgeBg,
            badgeText
          }

          nextY += CARD_H + CARD_GAP
        }
      }

      // Bottom Refresh Button
      this.addDynamic(
        hmUI.createWidget(hmUI.widget.BUTTON, {
          x: 130,
          y: nextY + 12,
          w: 220,
          h: 64,
          radius: 32,
          normal_color: COLOR_BTN_REFRESH,
          press_color: 0x0284c7,
          color: 0xffffff,
          text_size: 24,
          text: '重新整理',
          click_func: () => {
            this.vibrateShort()
            this.fetchLights()
          }
        })
      )

      // Bottom scroll padding for round screen
      this.addDynamic(
        hmUI.createWidget(hmUI.widget.FILL_RECT, {
          x: 0,
          y: nextY + 96,
          w: 10,
          h: 60,
          color: 0x000000,
          alpha: 0
        })
      )
    },

    updateCardVisual(entityId, isOn, isBusy) {
      const ref = this.state.cardRefs[entityId]
      if (!ref) return
      ref.borderRect.setProperty(hmUI.prop.COLOR, isOn ? COLOR_BORDER_ON : COLOR_BORDER_OFF)
      ref.innerRect.setProperty(hmUI.prop.COLOR, isOn ? COLOR_BG_ON : COLOR_BG_OFF)
      ref.dot.setProperty(hmUI.prop.COLOR, isOn ? COLOR_BORDER_ON : 0x475569)
      ref.nameText.setProperty(hmUI.prop.COLOR, isOn ? COLOR_TEXT_ON : COLOR_TEXT_OFF)
      ref.badgeBg.setProperty(hmUI.prop.COLOR, isOn ? COLOR_BADGE_ON : COLOR_BADGE_OFF)
      ref.badgeText.setProperty(hmUI.prop.MORE, {
        text: isBusy ? '...' : isOn ? '開啟' : '關閉',
        color: isOn ? COLOR_BADGE_TEXT_ON : COLOR_BADGE_TEXT_OFF
      })
    },

    fetchLights() {
      if (this.state.loading) return
      this.state.loading = true
      this.setStatus('正在讀取 HA 電燈...', 0x38bdf8)

      this.request({
        method: 'HA_GET_LIGHTS',
        params: {}
      })
        .then((res) => {
          this.state.loading = false
          const payload = res && res.result ? res.result : res
          if (payload && payload.ok && Array.isArray(payload.lights)) {
            this.state.lights = payload.lights
            const onCount = payload.lights.filter((l) => l.on).length
            this.setStatus(
              `已連線 · ${onCount}/${payload.lights.length} 盞開啟`,
              0x4ade80
            )
            this.renderBody()
          } else {
            const errMsg = (payload && payload.err) || '讀取失敗'
            this.setStatus(errMsg, 0xf87171)
            this.renderBody()
          }
        })
        .catch((err) => {
          this.state.loading = false
          this.setStatus('手機未連線或橋接未啟用', 0xf87171)
          this.renderBody()
        })
    },

    toggleLight(item) {
      if (!item || !item.id || this.state.busyId) return
      this.vibrateShort()
      this.state.busyId = item.id
      this.updateCardVisual(item.id, !!item.on, true)
      this.setStatus(`切換 ${item.n || item.id}...`, 0xfbbf24)

      this.request({
        method: 'HA_TOGGLE_LIGHT',
        params: {
          entity_id: item.id,
          target_on: !item.on
        }
      })
        .then((res) => {
          this.state.busyId = null
          const payload = res && res.result ? res.result : res
          if (payload && payload.ok) {
            item.on = typeof payload.on === 'boolean' ? payload.on : !item.on
            this.updateCardVisual(item.id, item.on, false)
            const onCount = this.state.lights.filter((l) => l.on).length
            this.setStatus(
              `${item.n || item.id} 已${item.on ? '開啟' : '關閉'} (${onCount}/${this.state.lights.length})`,
              item.on ? 0xfbbf24 : 0x4ade80
            )
          } else {
            this.updateCardVisual(item.id, !!item.on, false)
            const errMsg = (payload && payload.err) || '切換失敗'
            this.setStatus(errMsg, 0xf87171)
            try {
              showToast({ content: errMsg })
            } catch (e) {}
          }
        })
        .catch(() => {
          this.state.busyId = null
          this.updateCardVisual(item.id, !!item.on, false)
          this.setStatus('藍牙通訊逾時', 0xf87171)
        })
    },

    onDestroy() {
      try {
        if (this.vibrator) this.vibrator.stop()
      } catch (e) {}
    }
  })
)
