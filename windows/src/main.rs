// The whole app is ../web/index.html shown in Windows' built-in WebView2 (Edge), which does the WebRTC call.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod capture;
mod update;

use std::collections::HashMap;
use tauri::Manager;
use tauri_plugin_deep_link::DeepLinkExt;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

fn main() {
    tauri::Builder::default()
        // First plugin: a second launch (an invite link) gives its link to this app (deep-link feature) and quits.
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .setup(|app| {
            let window = app.get_webview_window("main").expect("main window");
            // Registers rxconnect:// for this user (the installer does too; this also covers moved installs).
            #[cfg(windows)]
            let _ = app.deep_link().register_all();
            // Started by a link: open the page on its room (index.html reads it from the #).
            if let Some(hash) = app.deep_link().get_current()?.and_then(|urls| room_hash(&urls)) {
                let mut url = window.url()?;
                url.set_fragment(Some(&hash));
                window.navigate(url)?;
            }
            app.deep_link().on_open_url(move |event| {
                if let Some(hash) = room_hash(&event.urls()) {
                    let _ = window.eval(&format!("window.rxOpenRoom && rxOpenRoom({})", serde_json::Value::from(hash)));
                }
            });
            Ok(())
        })
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

/// The room of an invite link rxconnect://join#room, still URL-encoded (the page decodes it).
fn room_hash(urls: &[tauri::Url]) -> Option<String> {
    urls.iter().find_map(|u| u.fragment().filter(|f| !f.is_empty()).map(str::to_owned))
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

