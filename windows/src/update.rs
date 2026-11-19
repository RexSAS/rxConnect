//! Self-update from GitHub releases (tag v<N>, from ../release.sh). The page (rxUpdate in web/index.html) asks
//! and shows the bar; this checks, downloads the installer and runs it silently over the installed app.
use std::io::Read;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use tauri::{AppHandle, WebviewWindow};

const LATEST: &str = "https://api.github.com/repos/RexSAS/rxConnect/releases/latest";
const ASSET: &str = "rxconnect-windows-x86_64-setup.exe";
const MAX_SIZE: usize = 100 << 20;

/// This build's N, set by the release workflow. Local builds have none and never update.
fn current() -> Option<u32> {
    option_env!("RXCONNECT_VERSION")?.parse().ok()
}

/// (N, download url) of the latest release if it's newer than this build and has the installer attached yet.
fn newer() -> Result<Option<(u32, String)>, String> {
    let Some(mine) = current() else { return Ok(None) };
    let latest: serde_json::Value = get(LATEST)?.into_json().map_err(|e| e.to_string())?;
    Ok(parse(&latest).filter(|(v, _)| *v > mine))
}

fn parse(release: &serde_json::Value) -> Option<(u32, String)> {
    let v = release["tag_name"].as_str()?.strip_prefix('v')?.parse().ok()?;
    let asset = release["assets"].as_array()?.iter().find(|a| a["name"] == ASSET)?;
    Some((v, asset["browser_download_url"].as_str()?.to_string()))
}

fn get(url: &str) -> Result<ureq::Response, String> {
    // GitHub's API refuses requests without a User-Agent.
    ureq::get(url).set("User-Agent", "rxConnect").call().map_err(|e| e.to_string())
}

fn js(window: &WebviewWindow, code: String) {
    let _ = window.eval(&code);
}

#[tauri::command]
pub fn check_update(window: WebviewWindow) {
    std::thread::spawn(move || match newer() {
        Ok(Some((v, _))) => js(&window, format!("rxUpdate.ask({v})")),
        Ok(None) => {}
        Err(e) => eprintln!("update check failed: {e}"),
    });
}

#[tauri::command]
pub fn install_update(app: AppHandle, window: WebviewWindow) {
    std::thread::spawn(move || match install(|p| js(&window, format!("rxUpdate.progress({p})"))) {
        Ok(setup) => {
            // After we quit: install silently (/S; it closes this app if it's still running), then start the new
            // version from the same place. raw_arg: cmd needs this exact quoting, which Rust's escaping would break.
            let exe = std::env::current_exe().unwrap_or_default();
            let _ = std::process::Command::new("cmd")
                .raw_arg(format!("/C \"\"{}\" /S & start \"\" \"{}\"\"", setup.display(), exe.display()))
                .creation_flags(0x0800_0000) // CREATE_NO_WINDOW: no console flashing up
                .spawn();
            app.exit(0);
        }
        Err(e) => js(&window, format!("rxUpdate.failed({})", serde_json::Value::from(e))),
    });
}

/// Downloads the installer (progress 0..100) to the temp folder; returns its path.
fn install(progress: impl Fn(u64)) -> Result<PathBuf, String> {
    let (_, url) = newer()?.ok_or("no newer version")?;
    let resp = get(&url)?;
    let total: u64 = resp.header("Content-Length").and_then(|l| l.parse().ok()).unwrap_or(0);
    let (mut reader, mut bytes, mut buf, mut last) = (resp.into_reader(), Vec::new(), vec![0; 64 << 10], u64::MAX);
    loop {
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        bytes.extend_from_slice(&buf[..n]);
        if bytes.len() > MAX_SIZE {
            return Err("download is too big".into());
        }
        let pct = if total > 0 { bytes.len() as u64 * 100 / total } else { 0 };
        if pct != last {
            progress(pct);
            last = pct;
        }
    }
    if total > 0 && bytes.len() as u64 != total {
        return Err("download cut short".into());
    }
    progress(100);
    let setup = std::env::temp_dir().join(ASSET);
    std::fs::write(&setup, bytes).map_err(|e| format!("can't save the installer: {e}"))?;
    Ok(setup)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_release() {
        let r = serde_json::json!({"tag_name": "v12", "assets": [
            {"name": "rxconnect.apk", "browser_download_url": "https://x/apk"},
            {"name": ASSET, "browser_download_url": "https://x/exe"}]});
        assert_eq!(parse(&r), Some((12, "https://x/exe".into())));
        // Release published but the exe not uploaded yet: nothing to offer.
        assert_eq!(parse(&serde_json::json!({"tag_name": "v13", "assets": []})), None);
        assert_eq!(parse(&serde_json::json!({"tag_name": "latest", "assets": []})), None);
    }
}
