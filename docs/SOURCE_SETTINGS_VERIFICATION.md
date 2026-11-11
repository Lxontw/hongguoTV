# 五片源與彈幕驗證紀錄

日期：2026-11-12（Asia/Taipei）

## 已完成

- `./gradlew :core:test :app:assembleDebug --console=plain --max-workers=2`：成功，122 項核心測試，0 失敗。
- `apksigner verify`：測試 APK 簽章驗證成功。
- `git diff --check`：通過。
- 紅果抽查分集 `7704236617325612056`、劇集 `7697561772433099800`：彈幕請求 HTTP 200、code 0，回傳 6 條時間軸彈幕。
- ReelShort：首頁 12 部，抽查劇集 23 集；公開 HLS 清單 HTTP 200，內容以 `#EXTM3U` 開始。
- DramaBox：首頁 12 部，抽查劇集 60 集；官方 CDN MP4 分段讀取成功，檔案 9,339,738 bytes，已驗證 prepare 與首段讀取。
- 單元測試涵蓋舊設定預設值、關閉目前來源後回紅果、海外收藏及進度備份還原、來源路由、付費鎖定、分集錯配、官網同站轉址與拒絕跨站轉址。

## 交付

- 測試安裝包：`outputs/hongguotv-five-sources-test.apk`。
- 最初的本機 Debug 測試包維持 0.9.11 / versionCode 20。
- GitHub 測試版另使用 0.9.12-rc1 / versionCode 21 的簽署 Release APK：`outputs/v0.9.12-rc1/hongguotv-kotlin-0.9.12-rc1-android8.apk`，不發布正式 OTA。
- 發布前執行 `:core:test :app:lintRelease :app:assembleRelease` 成功，Release APK 簽章驗證通過。
- 使用方式見 [片源設定說明](SOURCE_SETTINGS.md)。

## 尚未驗證

- `adb devices -l` 無連線裝置，因此未進行電視畫面、遙控器、重啟後開關狀態及連播換集實機驗證。
- 網路抽查不代表全片庫或所有地區皆可播放。HLS 清單已確認可取得，尚未以電視實播影片分段。
- 既有 D8/Kotlin metadata 警告仍出現，未阻止本次 Debug APK 編譯。
