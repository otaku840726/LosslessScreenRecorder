# LosslessScreenRecorder (Android 視覺無損螢幕錄製工具)

針對原廠與市面螢幕錄影強制降採樣至 1080p 及低碼率壓縮導致畫質模糊的痛點，打造一款支援**點對點原生物理像素（Native Physical Resolution）**、**極高碼率視覺無損硬體編碼（HEVC/AVC All-I）**、**雙音源即時混音**與**三大控制入口**的專業級 Android 螢幕錄製工具。

### User Review & Critical Decisions

> [!IMPORTANT]
> 以下為根據您的回覆所確認的核心產品與工程決策，確認無誤後即可點擊 **Proceed** 開始建置完整 Android 專案與四大階段實作。

- **已確認決策 1（完整範疇一次到位）**：一次完整實作規劃書中的所有四大階段功能，包含底層硬體編碼器能力探測、Zero-Copy 影音管線、雙路 PCM 即時混音器、三大控制入口（常駐通知欄、桌面懸浮球、系統下拉快速設定磁貼）以及內建錄影作品檢視與硬體參數驗證列表。
- **已確認決策 2（安全結束與存檔機制）**：除通知欄、懸浮球與快速設定磁貼手動停止外，實作「螢幕關閉（`ACTION_SCREEN_OFF`）自動停止錄影並安全寫入 MP4 檔尾（`MediaMuxer.stop()`）」，避免鎖屏中斷導致錄影檔案損毀，且不啟用搖晃偵測以避免手持遊戲時誤觸。
- **已確認決策 3（視覺設計風格）**：採用「專業硬體監控深色風格（Studio Hardware Monitor Dark Theme）」，以高對比度深邃炭黑基底搭配電光青/錄影訊號紅指示色、等寬數據字體（`JetBrains Mono`）與即時碼率/容量/硬體對齊數據儀表板。

---

### 1. Overview & Core Concept

- **What It Does**:
  - **突破 1080p 限制（真・物理原生像素）**：透過 `Display.Mode` 與 `WindowMetrics` 直接讀取面板最高物理像素（如 `3088 × 1440`、`3120 × 1440`）與當前渲染解析度，並自動查詢 SoC 硬體編碼器 `VideoCapabilities` 的長寬對齊倍數（`widthAlignment` / `heightAlignment`）與最高支援更新率（60 / 90 / 120 Hz）。
  - **Surface Zero-Copy 視覺無損編碼**：透過 `MediaCodec.createInputSurface()` 直通 `VirtualDisplay` GPU 緩衝區，支援 **H.265 (HEVC)** / **H.264 (AVC)**、最高至硬體上限之自訂碼率（50 ~ 150 Mbps+）、CBR/VBR/CQ 模式，以及 **All-I 全關鍵影格模式**（`KEY_I_FRAME_INTERVAL = 0` 或 `1s`），徹底消除高速畫面色塊與運動模糊。
  - **四種音源模式與雙路即時混音**：支援「靜音」、「僅系統內錄 (`AudioPlaybackCapture`)」、「僅麥克風」、「系統內錄 + 麥克風雙路 16-bit PCM 即時混音（含獨立音量增益控制與防爆音軟限幅）」，並以 48kHz / 320kbps AAC-LC 高規格封裝。
  - **三大自由控制入口**：提供常駐前景通知欄控制、可拖曳靠邊並於錄影時自動隱藏/最小化避免入鏡的桌面懸浮球、以及系統下拉控制中心快速設定磁貼（`TileService`）。
- **Target Audience / Persona**:
  - 追求極致畫質的手游玩家（60/120fps 高刷無損錄製）、UI/UX 動效設計師、專業評測創作者與需要後期剪輯（All-I 幀內編碼）的影音工作者。
- **Key Value**:
  - 釋放旗艦 SoC 硬體編碼器的真實極限，實現零 CPU 拷貝開銷的原生物理像素點對點錄製，同時解決錄影起訖時錄到控制介面自身的痛點。

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **硬體能力自動探測與儀表板首頁**：啟動 App 時自動掃描裝置螢幕物理面板模式（`Display.Mode`）與硬體 `MediaCodecList`（HEVC / AVC），在頂部「硬體能力監控儀表板」即時呈現面板物理像素、最大支援解析度、硬體長寬對齊要求（如 2 / 8 / 16 px）、最高幀率與硬體碼率上限（Mbps），以及每分鐘預估檔案容量（MB/min）。
  2. **畫質與無損編碼參數配置**：用戶可一鍵切換「物理面板最高像素」、「當前渲染像素」或「自訂比例」，選擇 H.265 (HEVC) / H.264 (AVC)、拖動高精準度碼率滑桿（10 Mbps ~ 硬體上限 150+ Mbps）、切換 60/90/120 FPS，以及開啟 **All-I 全關鍵影格（視覺無損剪輯模式）**。
  3. **雙路音源與增益調音台**：選擇 4 種音源模式；當啟用系統內錄或麥克風時，可獨立拉動「系統內錄增益（0% ~ 200%）」與「麥克風增益（0% ~ 200%）」滑桿，並附帶 DRM/系統安全限制友善說明。
  4. **三大控制入口與零入鏡錄影流程**：
     - 用戶可在控制中心開關「桌面懸浮球」、查看「快速設定磁貼」狀態或直接點擊底部主錄製按鈕。
     - 觸發開始錄影後（無論從主畫面、懸浮球、通知欄或下拉磁貼），系統若尚未取得 `MediaProjection` 授權會透過透明跳板完成授權，接著收起面板/隱藏懸浮球、執行倒數計時（可設 0 / 3 / 5 秒），於錄影瞬間將懸浮球完全隱藏或轉為極低不透明度微點（依設定），並在螢幕關閉時自動安全封裝存檔。
  5. **錄影作品與無損規格檢驗室**：切換至「錄影作品庫」分頁，直接讀取 `Movies/LosslessRecorder` 下的錄製檔案，顯示每部影片的實測解析度、編碼格式、實測平均碼率（Mbps）、幀率、時長與檔案大小，並支援一鍵預覽播放、分享或刪除。

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: **專業硬體監控深色風格（Studio Hardware Monitor & Telemetry Dark）**——靈感來自專業攝影機外錄監視器（Atomos / RED 監控介面），強調精準的技術參數回饋、清晰的狀態燈號與高密度無干擾操控。
  - *Color Palette & Mood*:
    - `--background`: `#0A0D12`（深邃光學黑，降低視覺疲勞並凸顯儀表數據）
    - `--surface`: `#121721` / `--surface-variant`: `#1A2130`（分層金屬灰藍卡片與控制艙）
    - `--primary`: `#00E5FF`（電光青 / 硬體遙測主色，用於解析度對齊、HEVC 標記與滑桿高亮）
    - `--accent-recording`: `#FF2A54`（訊號紅，用於錄影狀態指示燈、REC 計時與停止操作）
    - `--accent-warning`: `#FFB300`（琥珀黃，用於 All-I 高容量提示與高碼率預警）
  - *Typography & Hierarchy*:
    - 標題與技術數據採用本地封裝之 **`JetBrains Mono`** 與 **`Space Grotesk`**（透過 `font-util` 安裝至 `res/font/`），確保解析度（如 `3088 × 1440`）、即時碼率（`120.0 Mbps`）、計時器（`00:04:12.8`）呈現精準對齊的等寬儀表感；內文與說明採用高易讀性無襯線字體。
  - *Component Styling & Layout*:
    - 採用 Material 3 深色調色盤與自訂遙測卡片（Telemetry Card），頂部配置雙欄/三欄硬體規格矩陣（Native Resolution、Codec Cap、Estimated Size/Min），搭配分段式選擇器（Segmented Controls）與帶刻度提示的精準滑桿。
    - 支援響應式佈局（Window Size Classes）：直向手機採用底部導覽列（編碼中心 / 音源與控制 / 錄影作品庫），平板或橫向模式自動轉為側邊導覽列（NavigationRail）與雙欄並列監控佈局。
- **Interactive Feedback & Motion**:
  - 錄影進行中時，頂部狀態列顯示呼吸脈衝 `REC` 訊號燈與即時累計錄製時間/預估已用容量。
  - 參數調整時，即時動態重算「預估每分鐘檔案大小（MB/min）」與「硬體相容性驗證徽章（Supported / Aligned）」。

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: 原生物理像素擷取與硬體編碼器自動對齊（Auto-Alignment）**
  - *Chosen Approach*: 同時探測 `Display.getSupportedModes()` 的最大物理尺寸與 `WindowManager.currentWindowMetrics` 的當前尺寸，並透過 `MediaCodecInfo.VideoCapabilities` 讀取 `widthAlignment` 與 `heightAlignment`（通常為 2、8 或 16），向下或就近取整至硬體合規偶數倍數，再以 `areSizeAndRateSupported()`（含旋轉長寬互換檢查）確認可用性。
  - *Why*: 許多旗艦機物理像素（如 `3088 × 1440`）或縮小比例並非 16 的倍數，直接傳入 `MediaCodec.configure()` 會在部分 SoC 拋出 `CodecException`。自動對齊能在保留 99.9% 點對點銳利度的同時確保 100% 硬體編碼相容性。
- **Decision 2: All-I 全關鍵影格模式與高碼率配置策略**
  - *Chosen Approach*: 支援將 `MediaFormat.KEY_I_FRAME_INTERVAL` 設為 `0`（All-I 全關鍵影格）或 `1` 秒，並將碼率上限開放至硬體 `VideoCapabilities.bitrateRange.upper`（最高可達 100~150 Mbps+）。
  - *Why*: 傳統螢幕錄影使用長 GOP（I-frame 間隔數秒），在高速滑動或遊戲畫面轉動時會產生明顯的區塊壓縮偽影。All-I 搭配高碼率能讓每一幀都具備獨立完整畫質，且在剪輯軟體中順暢倒放與逐幀檢視。
- **Decision 3: 雙路 16-bit PCM 即時軟體混音管線**
  - *Chosen Approach*: 當選擇「系統內錄 + 麥克風」時，啟動兩路相同取樣率（48kHz, Stereo/Mono 轉 Stereo, PCM 16-bit）的 `AudioRecord`，於高優先級音訊執行緒讀取雙路緩衝區，套用各自獨立的音量增益係數後以 32-bit 整數相加並進行防爆音限幅（Clamping 至 `Short.MIN_VALUE..Short.MAX_VALUE`），再送入單一 `AAC-LC 320kbps` 硬體編碼器。
  - *Why*: 部分播放器和社群平台不支援多音軌 MP4 同時發聲；即時混音為單一高碼率立體聲 AAC 音軌能保證所有播放器與剪輯軟體 100% 相容，同時允許用戶在錄影前/中調整內錄與解說的音量平衡。
- **Decision 4: 透明授權跳板與三大控制入口防入鏡機制**
  - *Chosen Approach*: 建立無邊框透明 `RecordingPermissionActivity` 供快速設定磁貼（`TileService`）與懸浮球在未持有有效 `MediaProjection` 時喚起系統授權；授權完成後先隱藏懸浮球/收起通知欄並執行倒數，再正式建立 `VirtualDisplay` 與寫入影格，並監聽 `Intent.ACTION_SCREEN_OFF` 自動完成封裝存檔。
  - *Why*: Android 10~14 規定 `MediaProjection` 必須透過 Activity 取得用戶授權且搭配 `foregroundServiceType="mediaProjection"` 前景服務運行；透明跳板加延遲啟動能確保錄製成品開頭乾淨無殘影。

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                        操控與顯示層 (UI & Triggers)                         │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────┐  │
│  │ 主監控與設定介面      │  │ 桌面懸浮球控制器      │  │ 快速設定磁貼     │  │
│  │ (Compose Dashboard)  │  │ (WindowManager Ball) │  │ (QSTileService)  │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └────────┬─────────┘  │
│             └─────────────────────────┼───────────────────────┘            │
│                                       ▼                                    │
│                     ┌───────────────────────────────────┐                  │
│                     │ 透明授權跳板與常駐通知欄控制器      │                  │
│                     │ (PermissionActivity / NotifCtrl)  │                  │
│                     └─────────────────┬─────────────────┘                  │
└───────────────────────────────────────┼────────────────────────────────────┘
                                        ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                   核心錄製前景服務 (ScreenRecordService)                     │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ 錄製狀態與生命週期管理 (StateFlow: Idle / Countdown / Recording /     │  │
│  │ Paused / Saving) + 螢幕關閉廣播監聽 (ACTION_SCREEN_OFF Auto-Save)     │  │
│  └──────────────────┬─────────────────────────────────┬─────────────────┘  │
│                     ▼                                 ▼                    │
│  ┌───────────────────────────────────┐ ┌────────────────────────────────┐  │
│  │ 硬體能力與物理像素探測器           │ │ 設定持久化倉庫                  │  │
│  │ (DeviceCapabilityDetector)        │ │ (RecorderPreferencesRepository │  │
│  │ • Display.Mode 物理像素擷取        │ │  Jetpack DataStore)            │  │
│  │ • MediaCodecList 極限與對齊計算    │ └────────────────────────────────┘  │
│  └──────────────────┬────────────────┘                                     │
└─────────────────────┼──────────────────────────────────────────────────────┘
                      ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                視覺無損影音硬體管線 (Zero-Copy Media Pipeline)              │
│                                                                            │
│  [MediaProjection] ──► [VirtualDisplay (Native Physical Px)]               │
│                               │                                            │
│                               ▼ (Surface Zero-Copy GPU Direct)             │
│                        [VideoEncoderCore (HEVC / AVC)]                     │
│                        • 50~150+ Mbps CBR/VBR                              │
│                        • All-I (I-Frame Interval = 0 / 1s)                 │
│                        • 60 / 90 / 120 FPS                                 │
│                               │                                            │
│  [AudioPlaybackCapture] ──► [PCM 16-bit 即時混音與增益器]                   │
│  [Microphone AudioRecord] ─► (AudioCaptureMixerEngine)                     │
│                               │                                            │
│                               ▼                                            │
│                        [AudioEncoderCore (AAC-LC 48kHz / 320kbps)]         │
│                               │                                            │
│                               ▼ (PTS 微秒時間戳同步 & 暫停偏移補償)          │
│                        [MediaMuxerWrapper (MP4 封裝引擎)]                   │
│                               │                                            │
│                               ▼                                            │
│                 [MediaStore 輸出 (Movies/LosslessRecorder)]                │
└────────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - `HardwareCapabilities`: 包含面板最高物理模式（`nativeWidth`, `nativeHeight`, `maxRefreshRate`, `densityDpi`）、當前視窗像素，以及 HEVC / AVC 各自的 `CodecCapabilityInfo`（支援最大長寬、`widthAlignment`、`heightAlignment`、`maxBitrateMbps`、`maxSupportedFps`、是否支援 `Main10` / `CBR` / `CQ`）。
  - `RecorderConfig`: 儲存於 Jetpack DataStore 的用戶設定，包含解析度模式（`NATIVE_PHYSICAL` / `CURRENT_RENDER` / `CUSTOM_SCALE`）、目標編碼器（`HEVC` / `AVC`）、目標碼率（Mbps）、幀率（`30` / `60` / `90` / `120`）、關鍵影格模式（`ALL_I_ZERO` / `ONE_SECOND` / `TWO_SECONDS`）、音源模式（`MUTE` / `INTERNAL_ONLY` / `MIC_ONLY` / `INTERNAL_AND_MIC`）、內錄與麥克風音量增益（`0.0f..2.0f`）、倒數秒數、懸浮球開關與錄影時自動隱藏策略、螢幕關閉自動存檔開關。
  - `RecordingSessionState`: 全域單例 `StateFlow` 狀態機（`Idle`、`Countdown(remainingSeconds)`、`Recording(elapsedMs, estimatedBytes, isPaused)`、`Finalizing`、`Error(message)`），供主畫面、懸浮球、通知欄與 `TileService` 即時同步 UI。
  - `RecordedVideoItem`: 封裝自 `MediaStore.Video.Media` 與 `MediaMetadataRetriever` 讀取之錄影作品資訊（URI、檔名、實測解析度、編碼格式、實測平均碼率 Mbps、時長、檔案大小、建立時間）。

- **Interactive Component & State Mapping**:
  - **主監控與編碼設定頁 (`EncoderConfigScreen`)**：綁定 `HardwareCapabilities` 與 `RecorderConfig`，切換編碼器或解析度時即時觸發對齊計算（例如 `3088 × 1440` 對齊至硬體倍數）並更新預估每分鐘容量（`bitrateMbps * 60 / 8` MB/min）。
  - **音源與控制入口頁 (`AudioAndControlsScreen`)**：切換音源時動態檢查並請求 `RECORD_AUDIO` 與 `POST_NOTIFICATIONS` 權限；開啟桌面懸浮球時引導檢查 `Settings.canDrawOverlays(context)` 權限並立即啟動/關閉 `FloatingControlManager`。
  - **錄影作品檢驗頁 (`RecordingsLibraryScreen`)**：錄影結束寫入 `MediaStore` 後自動刷新清單，點擊項目可開啟內建影片資訊檢驗對話框與系統播放器預覽，或執行分享/刪除。
