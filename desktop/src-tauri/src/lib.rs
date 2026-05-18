// Avvia il sidecar Python (dewarp-server) all'apertura dell'app e lo termina alla chiusura.
// La WebView punta alla SPA gia' servita dal sidecar via HTTP, niente bundle frontend.
use std::net::TcpListener;
use std::sync::Mutex;

use tauri::{AppHandle, Manager, RunEvent, WindowEvent};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

struct SidecarHandle(Mutex<Option<CommandChild>>);

fn find_free_port() -> u16 {
    // Lascia che l'OS scelga una porta libera; chiudiamo subito il listener.
    let l = TcpListener::bind("127.0.0.1:0").expect("bind failed");
    let port = l.local_addr().unwrap().port();
    drop(l);
    port
}

fn start_sidecar(app: &AppHandle) -> (u16, CommandChild) {
    let port = find_free_port();
    let cmd = app
        .shell()
        .sidecar("dewarp-server")
        .expect("missing dewarp-server sidecar; ricostruisci con scripts/build_sidecar.sh");
    let (mut rx, child) = cmd
        .args(["--host", "127.0.0.1", "--port", &port.to_string()])
        .spawn()
        .expect("impossibile avviare dewarp-server");

    // Pompa l'output del sidecar (utile per debug)
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(line) | CommandEvent::Stderr(line) => {
                    if let Ok(s) = String::from_utf8(line) {
                        eprintln!("[sidecar] {}", s.trim_end());
                    }
                }
                CommandEvent::Terminated(t) => {
                    eprintln!("[sidecar] terminato: {:?}", t);
                    break;
                }
                _ => {}
            }
        }
    });

    (port, child)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(SidecarHandle(Mutex::new(None)))
        .setup(|app| {
            let handle = app.handle().clone();
            let (port, child) = start_sidecar(&handle);
            *app.state::<SidecarHandle>().0.lock().unwrap() = Some(child);

            // Aspetta che il sidecar risponda prima di puntare la webview
            let url = format!("http://127.0.0.1:{}/", port);
            let win = app.get_webview_window("main").expect("main window mancante");
            let url_clone = url.clone();
            tauri::async_runtime::spawn(async move {
                for _ in 0..50 {
                    if reqwest_get_ok(&url_clone).await {
                        break;
                    }
                    tauri::async_runtime::spawn_blocking(|| {
                        std::thread::sleep(std::time::Duration::from_millis(200))
                    })
                    .await
                    .ok();
                }
                let _ = win.eval(&format!("window.location.replace({:?})", url_clone));
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| match event {
            RunEvent::ExitRequested { .. } | RunEvent::Exit => {
                if let Some(state) = app.try_state::<SidecarHandle>() {
                    if let Some(child) = state.0.lock().unwrap().take() {
                        let _ = child.kill();
                    }
                }
            }
            RunEvent::WindowEvent {
                event: WindowEvent::CloseRequested { .. },
                ..
            } => {
                if let Some(state) = app.try_state::<SidecarHandle>() {
                    if let Some(child) = state.0.lock().unwrap().take() {
                        let _ = child.kill();
                    }
                }
            }
            _ => {}
        });
}

async fn reqwest_get_ok(_url: &str) -> bool {
    // Tauri 2 espone http via plugin; per semplicita' usiamo std::net::TcpStream
    use std::net::TcpStream;
    // Estrai porta dall'URL "http://127.0.0.1:PORT/"
    if let Some(after) = _url.split("127.0.0.1:").nth(1) {
        if let Some(port_str) = after.split('/').next() {
            if let Ok(port) = port_str.parse::<u16>() {
                return TcpStream::connect(("127.0.0.1", port)).is_ok();
            }
        }
    }
    false
}
