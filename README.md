# LosslessScreenRecorder (Android 視覺無損螢幕錄製工具)

針對市面與原廠內建錄影（如 Samsung 內建錄影強制鎖定 1080p、低碼率導致畫質模糊）的限制，打造一款 **點對點原生物理像素（Native Physical Resolution）**、**極高碼率視覺無損（HEVC/AVC All-I）**、且具備 **高自由度雙音源混音與三大控制入口** 的專業級 Android 螢幕錄製 App。

## 核心特色
1. **突破 1080p 限制（真・物理原生像素）**：
   - 直接透過 `Display.Mode` 與 `WindowMetrics` 抓取面板最高物理像素（如 `3088 × 1440`、`3120 × 1440`），並自動對齊硬體編碼器 `VideoCapabilities` 倍數。
   - 支援 60 / 90 / 120 Hz 高更新率原生錄製。
2. **視覺無損硬體編碼 (`Surface` Zero-Copy)**：
   - 支援 **H.265 (HEVC)** 與 **H.264 (AVC)**。
   - 支援自訂極高碼率（最高達硬體上限 50 ~ 150 Mbps+）與 **All-I 全關鍵影格模式**（`KEY_I_FRAME_INTERVAL = 0`），消除運動模糊與壓縮色塊。
3. **自由音源組合與即時混音**：
   - 支援「靜音」、「僅系統內錄 (`AudioPlaybackCapture`)」、「僅麥克風」、「系統內錄 + 麥克風雙路即時混音（附獨立音量增益控制）」。
   - 音訊採用 48kHz / 320kbps AAC-LC 高音質編碼。
4. **三大操控入口與安全保護**：
   - **常駐通知欄控制器**（附 300ms 收起延遲防入鏡）
   - **桌面懸浮球**（支援倒數計時、錄影期間自動完全隱藏零入鏡）
   - **系統下拉快速設定磁貼 (`Quick Settings Tile`)**
   - **螢幕關閉自動停止並封裝存檔**（監聽 `ACTION_SCREEN_OFF` 寫入 MP4 索引檔尾）

## GitHub Actions 自動打包 APK
本專案已內建 GitHub Actions 自動化建置流程（`.github/workflows/android-build.yml`）：
- 每次推送（Push）至 `main` / `master` 分支，或手動觸發 `workflow_dispatch` 時，會自動配置 JDK 21、Android SDK 與 Gradle 9.3.1 並打包：
  - `LosslessRecorder-Debug-APK` (`app-debug.apk`)
  - `LosslessRecorder-Release-APK` (`app-release.apk`)
- 當推送 `v*` 標籤（例如 `v1.0.0`）時，將自動建立 GitHub Release 並附上編譯完成的 APK 檔案。

## 文件導覽
- [系統架構與開發規劃書 (docs/IMPLEMENTATION_PLAN.md)](docs/IMPLEMENTATION_PLAN.md)
