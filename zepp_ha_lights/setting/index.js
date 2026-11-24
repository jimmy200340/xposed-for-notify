AppSettingsPage({
  build(props) {
    return Section({}, [
      Text({ paragraph: true, bold: true }, 'Home Assistant 設定（官方 Zepp App 用；Notify 用戶請於 NotifyBalance2Fix App 設定）'),
      TextInput({
        label: 'Home Assistant URL',
        placeholder: 'http://192.168.1.100:8123',
        settingsKey: 'ha_url'
      }),
      TextInput({
        label: 'Long-Lived Access Token',
        placeholder: 'eyJ...',
        settingsKey: 'ha_token'
      }),
      TextInput({
        label: '指定電燈實體（選填，逗號分隔，留空顯示全部）',
        placeholder: 'light.living_room,light.bedroom',
        settingsKey: 'ha_filter'
      })
    ])
  }
})
