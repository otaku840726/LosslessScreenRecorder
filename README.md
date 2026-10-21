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
4. **三大操控入口（由用戶自由選擇）**：
   - **常駐通知欄控制器**
   - **桌面懸浮球（支援倒數、自動隱藏避免入鏡）**
   - **系統下拉快速設定磁貼 (`Quick Settings Tile`)**

## 文件導覽
- [系統架構與開發規劃書 (docs/IMPLEMENTATION_PLAN.md)](docs/IMPLEMENTATION_PLAN.md)
