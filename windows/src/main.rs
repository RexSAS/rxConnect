// The whole app is ../web/index.html shown in Windows' built-in WebView2 (Edge), which does the WebRTC call.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod capture;
mod update;

use std::collections::HashMap;
use tauri::Manager;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .register_asynchronous_uri_scheme_protocol("rxcap", |_, request, responder| capture::serve(request, responder))
        .invoke_handler(tauri::generate_handler![
            update::check_update,
            update::install_update,
            update::version,
            set_shortcuts,
            capture::list_sources,
            capture::start_capture,
            capture::stop_capture
        ])
        .run(tauri::generate_context!())
        .expect("rxConnect failed to start");
}

/// Your shortcuts (action -> keys, from the settings) made system-wide, so they work while another app is in front.
/// The page sets them when a call starts and clears them (empty map) when it ends: while set, Windows gives those
/// keys to rxConnect only. Returns the actions it couldn't register (keys taken by another app, or not allowed).
#[tauri::command]
fn set_shortcuts(app: tauri::AppHandle, keys: HashMap<String, String>) -> Vec<String> {
    let shortcuts = app.global_shortcut();
    let _ = shortcuts.unregister_all();
    let mut failed = Vec::new();
    for (action, accelerator) in keys {
        if accelerator.is_empty() {
            continue;
        }
        let call = format!("window.rxShortcut && rxShortcut({})", serde_json::Value::from(action.as_str()));
        let registered = shortcuts.on_shortcut(accelerator.replace("Win+", "Super+").as_str(), move |app, _, event| {
            if event.state() == ShortcutState::Pressed {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.eval(&call);
                }
            }
        });
        if registered.is_err() {
            failed.push(action);
        }
    }
    failed
}

