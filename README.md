# DailyPowerMeter - 巴士電量與里程自動化採集系統

一個基於 Android Jetpack Compose 開發的工務專用「數位治具 (Digital Jig)」。透過手機鏡頭 OCR 辨識，取代傳統紙本黃卡紀錄，實現巴士營運數據的自動化錄入與雲端同步。

## 🌟 核心功能
* **多樁位分頁管理**：支援 1-5 號充電樁獨立分頁操作，符合場站實際作業流程。
* **AI OCR 文字辨識**：整合 Google ML Kit，自動抓取車號、駕駛編號、里程及電量 (SOC)，大幅降低人工輸入錯誤。
* **雙重存儲機制**：
    * **本地端**：使用 SQLite (Room) 儲存最近 20 筆紀錄，確保離線時資料不遺失。
    * **雲端同步**：透過 OkHttp 非同步將資料推送到 Google Apps Script (Spreadsheet)，達成即時報表化。
* **工業級 UI 設計**：採用純黑背景 (Dark Mode) 高對比設計，適合夜間場站高光/低光環境下使用。

## 🛠 關鍵技術
* **UI 框架**: Jetpack Compose (Material 3)
* **架構**: MVVM + Kotlin Coroutines & Flow
* **資料庫**: Room Persistence Library
* **影像處理**: CameraX + ML Kit Text Recognition
* **網路通訊**: OkHttp + Google Apps Script API

## 📸 介面演示
https://youtu.be/hS69kh_HXEE

## 🚀 快速開始
1. **設定雲端後台**：
   - 部署一個 Google Apps Script，接收 JSON POST 請求並寫入試算表。
   - 將 `BusViewModel.kt` 中的 `googleScriptUrl` 修改為你的部署網址。
2. **編譯環境**：
   - Android Studio Jellyfish 或更新版本。
   - 專案需開啟相機權限。

## 📂 專案結構
```text
com.example.dailypowermeter
├── MainActivity.kt        # 程式入口與主題設定
├── BusViewModel.kt       # 業務邏輯、網路請求與資料流管理
├── BusDatabase.kt        # Room 實體與 DAO 定義
└── CameraComponents.kt   # CameraX 預覽與 ML Kit OCR 核心邏輯

📝 開發者備註 (Digital Jig Concept)
本專案依循「數位治具」理念：工具應適應流程，而非流程適應工具。
透過 OCR 自動縮放 (Auto-zoom) 與數據過濾邏輯，專為解決巴士司機在昏暗環境下快速錄入數據的痛點。

Created by Kenshin San Li
