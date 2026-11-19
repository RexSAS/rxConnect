// The whole app is ../web/index.html shown in Windows' built-in WebView2 (Edge), which does the WebRTC call.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod update;

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![update::check_update, update::install_update])
        .run(tauri::generate_context!())
        .expect("rxConnect failed to start");
}
