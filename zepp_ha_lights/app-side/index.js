import { BaseSideService } from '@zeppos/zml/base-side'

function getConfig() {
  const rawUrl = (settings.settingsStorage.getItem('ha_url') || '').trim().replace(/\/+$/, '')
  const token = (settings.settingsStorage.getItem('ha_token') || '').trim()
  const filterRaw = (settings.settingsStorage.getItem('ha_filter') || '').trim()
  const filterList = filterRaw
    ? filterRaw
        .split(/[\n,]+/)
        .map((s) => s.trim())
        .filter(Boolean)
    : []
  return { url: rawUrl, token, filterList }
}

AppSideService(
  BaseSideService({
    onInit() {},

    async onRequest(req, res) {
      const { url, token, filterList } = getConfig()
      if (!url || !token) {
        res(null, { ok: false, err: '請先設定 HA 網址與 Token' })
        return
      }

      const headers = {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      }

      try {
        if (req.method === 'HA_GET_LIGHTS') {
          const resp = await fetch({
            url: `${url}/api/states`,
            method: 'GET',
            headers
          })
          const states = typeof resp.body === 'string' ? JSON.parse(resp.body) : resp.body
          if (!Array.isArray(states)) {
            res(null, { ok: false, err: `HA 回應異常 (${resp.status})` })
            return
          }
          const lights = states
            .filter((st) => st && typeof st.entity_id === 'string' && st.entity_id.startsWith('light.'))
            .filter((st) => filterList.length === 0 || filterList.includes(st.entity_id))
            .slice(0, 24)
            .map((st) => ({
              id: st.entity_id,
              n: (st.attributes && st.attributes.friendly_name) || st.entity_id.replace('light.', ''),
              on: st.state === 'on'
            }))
          res(null, { ok: true, lights })
          return
        }

        if (req.method === 'HA_TOGGLE_LIGHT') {
          const entityId = req.params && req.params.entity_id
          if (!entityId) {
            res(null, { ok: false, err: '缺少 entity_id' })
            return
          }
          const resp = await fetch({
            url: `${url}/api/services/light/toggle`,
            method: 'POST',
            headers,
            body: JSON.stringify({ entity_id: entityId })
          })
          const changed = typeof resp.body === 'string' ? JSON.parse(resp.body) : resp.body
          let newOn = req.params && typeof req.params.target_on === 'boolean' ? req.params.target_on : true
          if (Array.isArray(changed)) {
            const matched = changed.find((c) => c && c.entity_id === entityId)
            if (matched) newOn = matched.state === 'on'
          }
          res(null, { ok: true, id: entityId, on: newOn })
          return
        }

        res(null, { ok: false, err: '未知指令' })
      } catch (e) {
        res(null, { ok: false, err: '無法連線至 Home Assistant' })
      }
    },

    onRun() {},
    onDestroy() {}
  })
)
